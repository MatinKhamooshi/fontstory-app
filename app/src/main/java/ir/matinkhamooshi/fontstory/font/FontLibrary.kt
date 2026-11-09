package ir.matinkhamooshi.fontstory.font

import android.content.Context
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** One selectable weight/style of a family. [wght] is set for variable-font instances. */
data class FontStyle(
    val id: String,
    val family: String,
    val name: String,
    val weight: Int,
    val italic: Boolean,
    val file: String,
    val ttc: Int,
    val wght: Float?,
) {
    val isSystem get() = file.isEmpty()
    val label: String
        get() = buildString {
            append(name)
            append(" · ")
            append(wght?.roundToInt() ?: weight)
            if (italic && !FontNames.isItalicName(name)) append(" Italic")
            if (wght != null) append(" · VF")
        }
}

data class FontFamily(val name: String, val styles: List<FontStyle>) {
    val isSystem get() = name == FontLibrary.SYSTEM_FAMILY
}

/**
 * The user's font collection. Font files are stored privately in
 * `filesDir/fonts/<sha256>.font` and described by `library.json`.
 * All methods are synchronized because export rendering reads typefaces off the main thread.
 */
object FontLibrary {
    const val SYSTEM_FAMILY = "‎فونت سیستم"
    private const val INDEX_FILE = "library.json"

    private lateinit var dir: File
    private val styles = LinkedHashMap<String, FontStyle>()
    private val systemStyles: List<FontStyle> = (100..900 step 100).map {
        FontStyle("sys:$it", SYSTEM_FAMILY, FontNames.weightName(it), it, false, "", 0, null)
    }
    private val typefaces = HashMap<String, Typeface>()

    /** Bumped on every change so screens know when to refresh. */
    @Volatile
    var version = 0
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "fonts").apply { mkdirs() }
        load()
        removeUnreferencedFiles()
    }

    fun fontFile(hash: String): File = File(dir, "$hash.font")

    fun tmpFile(): File = File.createTempFile("import", ".tmp", dir)

    @Synchronized
    fun style(id: String): FontStyle? = styles[id] ?: systemStyles.firstOrNull { it.id == id }

    @Synchronized
    fun userFamilies(): List<FontFamily> =
        styles.values.groupBy { it.family }
            .map { (name, list) -> FontFamily(name, list.sortedWith(compareBy({ it.wght ?: it.weight.toFloat() }, { it.italic }, { it.name }))) }
            .sortedBy { it.name.lowercase() }

    @Synchronized
    fun families(): List<FontFamily> = userFamilies() + FontFamily(SYSTEM_FAMILY, systemStyles)

    @Synchronized
    fun defaultStyleId(): String {
        val fam = userFamilies().firstOrNull() ?: return "sys:400"
        return closest(fam, 400, false).id
    }

    fun closest(family: FontFamily, weight: Int, italic: Boolean): FontStyle =
        family.styles.minByOrNull { s ->
            abs((s.wght ?: s.weight.toFloat()) - weight) + if (s.italic != italic) 10_000f else 0f
        }!!

    @Synchronized
    fun typeface(id: String): Typeface {
        typefaces[id]?.let { return it }
        val tf = build(style(id)) ?: Typeface.DEFAULT
        typefaces[id] = tf
        return tf
    }

    private fun build(s: FontStyle?): Typeface? {
        if (s == null) return null
        if (s.isSystem) return Typeface.create(Typeface.DEFAULT, s.weight, s.italic)
        val f = fontFile(s.file)
        if (!f.isFile) return null
        return try {
            Typeface.Builder(f).apply {
                setTtcIndex(s.ttc)
                s.wght?.let { setFontVariationSettings("'wght' $it") }
                setWeight(s.weight.coerceIn(1, 1000))
                setItalic(s.italic)
            }.build()
        } catch (_: Exception) {
            null
        }
    }

    /** Adds imported styles, skipping exact duplicates. Returns the ones that were new. */
    @Synchronized
    fun addStyles(newStyles: List<FontStyle>): List<FontStyle> {
        val added = ArrayList<FontStyle>()
        for (s in newStyles) {
            if (styles.containsKey(s.id)) continue
            val dup = styles.values.any {
                it.family.equals(s.family, true) && it.name.equals(s.name, true) &&
                    it.weight == s.weight && it.italic == s.italic && it.wght == s.wght
            }
            if (dup) continue
            styles[s.id] = s
            added += s
        }
        if (added.isNotEmpty()) changed()
        removeUnreferencedFiles()
        return added
    }

    @Synchronized
    fun removeStyles(ids: Collection<String>) {
        var any = false
        for (id in ids) if (styles.remove(id) != null) { typefaces.remove(id); any = true }
        if (any) changed()
        removeUnreferencedFiles()
    }

    private fun changed() {
        version++
        save()
    }

    private fun removeUnreferencedFiles() {
        val used = styles.values.mapTo(HashSet()) { "${it.file}.font" }
        dir.listFiles()?.forEach { f ->
            if (f.name != INDEX_FILE && f.name !in used) f.delete()
        }
    }

    private fun load() {
        val f = File(dir, INDEX_FILE)
        if (!f.isFile) return
        try {
            val arr = JSONObject(f.readText()).getJSONArray("styles")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val file = o.getString("file")
                if (!file.matches(Regex("[0-9a-f]{16,64}"))) continue
                val s = FontStyle(
                    id = o.getString("id"),
                    family = o.getString("family"),
                    name = o.getString("name"),
                    weight = o.getInt("weight"),
                    italic = o.getBoolean("italic"),
                    file = file,
                    ttc = o.optInt("ttc", 0),
                    wght = if (o.has("wght")) o.getDouble("wght").toFloat() else null,
                )
                if (fontFile(s.file).isFile) styles[s.id] = s
            }
        } catch (_: Exception) {
            // corrupted index: start empty rather than crash
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (s in styles.values) {
            arr.put(JSONObject().apply {
                put("id", s.id); put("family", s.family); put("name", s.name)
                put("weight", s.weight); put("italic", s.italic)
                put("file", s.file); put("ttc", s.ttc)
                s.wght?.let { put("wght", it.toDouble()) }
            })
        }
        val tmp = File(dir, "$INDEX_FILE.tmp")
        tmp.writeText(JSONObject().put("v", 1).put("styles", arr).toString())
        tmp.renameTo(File(dir, INDEX_FILE))
    }
}
