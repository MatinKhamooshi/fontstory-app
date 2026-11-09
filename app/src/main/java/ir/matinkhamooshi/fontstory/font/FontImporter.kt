package ir.matinkhamooshi.fontstory.font

import android.content.ContentResolver
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * Imports fonts from content URIs (single files, ZIP archives, or a whole folder tree).
 * Runs on a background thread; never touches UI. Everything read is size-limited.
 */
class FontImporter(private val resolver: ContentResolver) {

    data class Result(
        val styles: List<FontStyle>,
        val fontFiles: Int,
        val invalid: Int,
    )

    private class Source(val name: String, val uri: Uri)

    private val found = ArrayList<FontStyle>()
    private var fontFiles = 0
    private var invalid = 0
    private var zipBytesTotal = 0L

    fun run(uris: List<Uri>, treeUris: List<Uri>, progress: (done: Int, total: Int) -> Unit): Result {
        val sources = ArrayList<Source>()
        for (u in uris) sources += Source(displayName(u), u)
        for (t in treeUris) collectTree(t, sources)

        sources.forEachIndexed { i, src ->
            progress(i, sources.size)
            try {
                resolver.openInputStream(src.uri)?.use { raw ->
                    val input = BufferedInputStream(raw)
                    if (isZip(input, src.name)) importZip(input) else importFont(readLimited(input, FontParser.MAX_FONT_BYTES))
                } ?: invalid++
            } catch (_: Exception) {
                invalid++
            } catch (_: OutOfMemoryError) {
                invalid++
            }
        }
        progress(sources.size, sources.size)
        return Result(found, fontFiles, invalid)
    }

    private fun isZip(input: BufferedInputStream, name: String): Boolean {
        input.mark(4)
        val b = ByteArray(4)
        val n = input.read(b)
        input.reset()
        val magic = n == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4
        return magic || (n < 4 && name.endsWith(".zip", true))
    }

    private fun importZip(input: InputStream) {
        ZipInputStream(input).use { zip ->
            var entries = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) break
                if (e.isDirectory || !FONT_EXT.matches(e.name.substringAfterLast('/'))) continue
                if (e.name.contains("__MACOSX")) continue
                val bytes = try {
                    readLimited(zip, FontParser.MAX_FONT_BYTES)
                } catch (_: Exception) {
                    invalid++; continue
                }
                zipBytesTotal += bytes.size
                if (zipBytesTotal > MAX_ZIP_TOTAL) break
                importFont(bytes)
            }
        }
    }

    private fun importFont(bytes: ByteArray) {
        val faces = try {
            FontParser.parse(bytes)
        } catch (_: FontFormatException) {
            invalid++; return
        }

        val hash = sha256(bytes).take(40)
        val target = FontLibrary.fontFile(hash)
        val createdNow = !target.isFile
        if (createdNow) {
            val tmp = FontLibrary.tmpFile()
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) { tmp.delete(); invalid++; return }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

        val styles = ArrayList<FontStyle>()
        for (face in faces) {
            // Let Android's own loader confirm the face is usable before we list it.
            val ok = try {
                Typeface.Builder(target).setTtcIndex(face.ttcIndex).build() != null
            } catch (_: Exception) {
                false
            }
            if (!ok) continue
            styles += stylesFor(face, hash)
        }
        if (styles.isEmpty()) {
            if (createdNow) target.delete()
            invalid++
            return
        }
        fontFiles++
        found += styles
    }

    private fun stylesFor(face: ParsedFace, hash: String): List<FontStyle> {
        val axis = face.wghtAxis
        if (axis == null) {
            return listOf(
                FontStyle("$hash:${face.ttcIndex}:s", face.family, face.styleName, face.weight, face.italic, hash, face.ttcIndex, null)
            )
        }
        // Variable font: one entry per named instance, or standard steps across the axis.
        val list = ArrayList<FontStyle>()
        val seen = HashSet<String>()
        val named = face.instances.ifEmpty {
            val steps = (100..900 step 100).map { it.toFloat() }.filter { it in axis.min..axis.max }.toMutableList()
            if (steps.isEmpty()) steps += axis.def
            steps.map { NamedInstance(FontNames.weightName(it.roundToInt()), it, face.italic) }
        }
        for (inst in named) {
            val key = "${inst.wght.roundToInt()}:${inst.italic}"
            if (!seen.add(key)) continue
            list += FontStyle(
                id = "$hash:${face.ttcIndex}:v${inst.wght.roundToInt()}${if (inst.italic) "i" else ""}",
                family = face.family,
                name = inst.name,
                weight = inst.wght.roundToInt().coerceIn(1, 1000),
                italic = inst.italic,
                file = hash,
                ttc = face.ttcIndex,
                wght = inst.wght,
            )
        }
        return list
    }

    // ---- folder (tree) scanning ----

    private fun collectTree(tree: Uri, out: MutableList<Source>) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        walk(tree, rootId, 0, out)
    }

    private fun walk(tree: Uri, docId: String, depth: Int, out: MutableList<Source>) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val dirs = ArrayList<String>()
        resolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < MAX_FILES) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    dirs += id
                } else if (FONT_EXT.matches(name) || name.endsWith(".zip", true)) {
                    out += Source(name, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
        }
        for (d in dirs) walk(tree, d, depth + 1, out)
    }

    private fun displayName(uri: Uri): String = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    } catch (_: Exception) {
        uri.lastPathSegment.orEmpty()
    }

    companion object {
        private val FONT_EXT = Regex(".+\\.(ttf|otf|ttc|otc)$", RegexOption.IGNORE_CASE)
        private const val MAX_ZIP_ENTRIES = 2000
        private const val MAX_ZIP_TOTAL = 400L * 1024 * 1024
        private const val MAX_DEPTH = 8
        private const val MAX_FILES = 3000

        fun readLimited(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw FontFormatException("file too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        private fun sha256(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
