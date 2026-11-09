package ir.matinkhamooshi.fontstory.font

import java.nio.charset.Charset
import kotlin.math.roundToInt

class FontFormatException(message: String) : Exception(message)

data class WghtAxis(val min: Float, val def: Float, val max: Float)

data class NamedInstance(val name: String, val wght: Float, val italic: Boolean)

/** One face inside a font file (a .ttc collection may hold several). */
data class ParsedFace(
    val ttcIndex: Int,
    val family: String,
    val styleName: String,
    val weight: Int,
    val italic: Boolean,
    val wghtAxis: WghtAxis?,
    val instances: List<NamedInstance>,
)

/**
 * Minimal, defensive reader for TrueType / OpenType / TTC files.
 * It reads only what the app needs (names, weight class, italic flags and the
 * variable `wght` axis). Every read is bounds-checked, so malformed files fail
 * with [FontFormatException] instead of crashing or reading out of range.
 */
object FontParser {
    const val MAX_FONT_BYTES = 40 * 1024 * 1024

    private const val MAX_FACES = 64
    private const val MAX_TABLES = 512
    private const val MAX_NAME_RECORDS = 4000
    private const val MAX_INSTANCES = 1000

    private val TAG_TTCF = tag("ttcf")
    private val TAG_OTTO = tag("OTTO")
    private val TAG_TRUE = tag("true")
    private const val SFNT_V1 = 0x00010000L
    private val TAG_NAME = tag("name")
    private val TAG_OS2 = tag("OS/2")
    private val TAG_HEAD = tag("head")
    private val TAG_CMAP = tag("cmap")
    private val TAG_FVAR = tag("fvar")
    private val TAG_WGHT = tag("wght")
    private val TAG_ITAL = tag("ital")

    private fun tag(s: String): Long = s.fold(0L) { acc, c -> (acc shl 8) or (c.code.toLong() and 0xFF) }

    fun parse(data: ByteArray): List<ParsedFace> {
        if (data.size < 12) throw FontFormatException("file too small")
        if (data.size > MAX_FONT_BYTES) throw FontFormatException("file too large")
        val r = Reader(data)
        if (r.u32(0) == TAG_TTCF) {
            val count = r.u32(8)
            if (count < 1 || count > MAX_FACES) throw FontFormatException("bad collection header")
            val faces = ArrayList<ParsedFace>()
            for (i in 0 until count.toInt()) {
                val off = r.u32(12 + 4 * i)
                if (off >= data.size) continue
                try {
                    faces += parseSfnt(r, off.toInt(), i)
                } catch (_: FontFormatException) {
                    // skip broken face, keep the rest of the collection
                }
            }
            if (faces.isEmpty()) throw FontFormatException("no valid faces")
            return faces
        }
        return listOf(parseSfnt(r, 0, 0))
    }

    private class Table(val offset: Int, val length: Int) {
        val end get() = offset + length
    }

    private fun parseSfnt(r: Reader, base: Int, index: Int): ParsedFace {
        val version = r.u32(base)
        if (version != SFNT_V1 && version != TAG_OTTO && version != TAG_TRUE) {
            throw FontFormatException("not a TrueType/OpenType font")
        }
        val numTables = r.u16(base + 4)
        if (numTables == 0 || numTables > MAX_TABLES) throw FontFormatException("bad table count")

        val tables = HashMap<Long, Table>(numTables * 2)
        for (i in 0 until numTables) {
            val rec = base + 12 + 16 * i
            val tag = r.u32(rec)
            val off = r.u32(rec + 8)
            val len = r.u32(rec + 12)
            if (off + len > r.size) throw FontFormatException("table out of bounds")
            tables[tag] = Table(off.toInt(), len.toInt())
        }
        if (tables[TAG_CMAP] == null || tables[TAG_HEAD] == null) {
            throw FontFormatException("missing required tables")
        }

        val names = tables[TAG_NAME]?.let { readNames(r, it) } ?: emptyMap()

        // --- static weight / italic ---
        var weight = 400
        var italic = false
        tables[TAG_OS2]?.let { os2 ->
            if (os2.length >= 6) weight = r.u16(os2.offset + 4)
            if (os2.length >= 64) italic = (r.u16(os2.offset + 62) and 0x1) != 0
        }
        tables[TAG_HEAD]?.let { head ->
            if (head.length >= 46 && (r.u16(head.offset + 44) and 0x2) != 0) italic = true
        }
        if (weight in 1..9) weight *= 100

        val typoFamily = names[16]
        val rawFamily = typoFamily ?: names[1] ?: names[4] ?: "Unknown"
        val split = FontNames.splitFamily(rawFamily)
        val family = if (typoFamily != null) FontNames.clean(typoFamily) else split.first
        val stripped = if (typoFamily != null) "" else split.second

        val subfamily = names[17] ?: names[2] ?: "Regular"
        val nameHint = if (stripped.isNotEmpty()) "$stripped $subfamily" else subfamily
        val nameWeight = FontNames.weightFromName(nameHint)
        // Many Persian fonts ship every weight with usWeightClass = 400; trust the name then.
        if ((weight == 400 || weight == 0) && nameWeight != null) weight = nameWeight
        if (weight <= 0) weight = 400
        weight = weight.coerceIn(1, 1000)
        if (FontNames.isItalicName(nameHint)) italic = true

        val styleName = when {
            names[17] != null -> FontNames.clean(names[17]!!)
            stripped.isNotEmpty() -> FontNames.clean(stripped)
            else -> FontNames.clean(subfamily)
        }.ifEmpty { "Regular" }

        // --- variable font axis + named instances ---
        var axis: WghtAxis? = null
        val instances = ArrayList<NamedInstance>()
        tables[TAG_FVAR]?.let { fv ->
            if (fv.length < 16) return@let
            val o = fv.offset
            val axesOff = o + r.u16(o + 4)
            val axisCount = r.u16(o + 8)
            val axisSize = r.u16(o + 10)
            val instCount = r.u16(o + 12)
            val instSize = r.u16(o + 14)
            if (axisSize < 20 || axisCount !in 1..64) return@let
            if (axesOff.toLong() + axisCount.toLong() * axisSize > fv.end) return@let

            var wIdx = -1
            var iIdx = -1
            for (a in 0 until axisCount) {
                val ar = axesOff + a * axisSize
                when (r.u32(ar)) {
                    TAG_WGHT -> {
                        wIdx = a
                        val min = r.fixed(ar + 4)
                        val def = r.fixed(ar + 8)
                        val max = r.fixed(ar + 12)
                        if (min.isFinite() && max.isFinite() && min <= def && def <= max && min >= 1f && max <= 1000f) {
                            axis = WghtAxis(min, def, max)
                        }
                    }
                    TAG_ITAL -> iIdx = a
                }
            }
            val ax = axis ?: return@let
            if (instSize < 4 + 4 * axisCount || instCount > MAX_INSTANCES) return@let
            val instBase = axesOff + axisCount * axisSize
            for (k in 0 until instCount) {
                val ir = instBase + k * instSize
                if (ir + instSize > fv.end) break
                val nameId = r.u16(ir)
                val w = r.fixed(ir + 4 + 4 * wIdx).coerceIn(ax.min, ax.max)
                val itl = iIdx >= 0 && r.fixed(ir + 4 + 4 * iIdx) >= 0.5f
                val nm = names[nameId]?.let { FontNames.clean(it) }?.ifEmpty { null }
                    ?: FontNames.weightName(w.roundToInt())
                instances += NamedInstance(nm, w, itl || FontNames.isItalicName(nm))
            }
        }

        return ParsedFace(index, family.ifEmpty { "Unknown" }, styleName, weight, italic, axis, instances)
    }

    private fun readNames(r: Reader, t: Table): Map<Int, String> {
        if (t.length < 6) return emptyMap()
        val count = r.u16(t.offset + 2)
        if (count > MAX_NAME_RECORDS) throw FontFormatException("too many names")
        val storage = t.offset + r.u16(t.offset + 4)
        val best = HashMap<Int, Pair<Int, String>>()
        for (i in 0 until count) {
            val rec = t.offset + 6 + 12 * i
            if (rec + 12 > t.end) break
            val platform = r.u16(rec)
            val encoding = r.u16(rec + 2)
            val language = r.u16(rec + 4)
            val id = r.u16(rec + 6)
            val len = r.u16(rec + 8)
            val start = storage + r.u16(rec + 10)
            if (len == 0 || len > 1024 || start + len > t.end || start + len > r.size) continue

            val score: Int
            val cs: Charset
            when {
                platform == 3 && (encoding == 0 || encoding == 1 || encoding == 10) -> {
                    score = if (language == 0x409) 4 else 3; cs = Charsets.UTF_16BE
                }
                platform == 0 -> { score = 2; cs = Charsets.UTF_16BE }
                platform == 1 && encoding == 0 -> { score = 1; cs = Charsets.ISO_8859_1 }
                else -> continue
            }
            val prev = best[id]
            if (prev != null && prev.first >= score) continue
            val s = FontNames.clean(String(r.data, start, len, cs))
            if (s.isNotEmpty()) best[id] = score to s
        }
        return best.mapValues { it.value.second }
    }

    private class Reader(val data: ByteArray) {
        val size = data.size
        fun u8(o: Int): Int {
            if (o < 0 || o >= size) throw FontFormatException("unexpected end of file")
            return data[o].toInt() and 0xFF
        }
        fun u16(o: Int): Int = (u8(o) shl 8) or u8(o + 1)
        fun u32(o: Int): Long = (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        fun fixed(o: Int): Float = u32(o).toInt() / 65536f
    }
}

object FontNames {
    private val WEIGHT_WORDS = listOf(
        "extrablack" to 950, "ultrablack" to 950,
        "extrabold" to 800, "ultrabold" to 800,
        "semibold" to 600, "demibold" to 600,
        "extralight" to 200, "ultralight" to 200,
        "hairline" to 100, "thin" to 100,
        "light" to 300, "medium" to 500,
        "black" to 900, "heavy" to 900,
        "bold" to 700, "regular" to 400, "normal" to 400, "book" to 400,
    )

    private val TRAILING_STYLE = Regex(
        "^(.*?)[\\s_-]+((?:(?:extra|ultra|semi|demi)[\\s_-]?)?(?:thin|hairline|light|regular|normal|book|medium|bold|black|heavy|fat)(?:[\\s_-]?(?:italic|oblique))?|italic|oblique)$",
        RegexOption.IGNORE_CASE,
    )

    /** Removes control characters and trims to a sane length. */
    fun clean(s: String): String =
        s.filter { !it.isISOControl() && it != '﻿' }.trim().take(64)

    /** "IRANSans Bold" -> ("IRANSans", "Bold"). */
    fun splitFamily(name: String): Pair<String, String> {
        val c = clean(name)
        val m = TRAILING_STYLE.matchEntire(c) ?: return c to ""
        val fam = m.groupValues[1].trim()
        return if (fam.isEmpty()) c to "" else fam to m.groupValues[2].trim()
    }

    fun weightFromName(name: String): Int? {
        val compact = name.lowercase().replace(Regex("[\\s_-]"), "")
        for ((w, v) in WEIGHT_WORDS) if (compact.contains(w)) return v
        return null
    }

    fun isItalicName(name: String): Boolean =
        name.contains("italic", ignoreCase = true) || name.contains("oblique", ignoreCase = true)

    fun weightName(w: Int): String = when {
        w < 150 -> "Thin"
        w < 250 -> "ExtraLight"
        w < 350 -> "Light"
        w < 450 -> "Regular"
        w < 550 -> "Medium"
        w < 650 -> "SemiBold"
        w < 750 -> "Bold"
        w < 850 -> "ExtraBold"
        w < 925 -> "Black"
        else -> "ExtraBlack"
    }
}
