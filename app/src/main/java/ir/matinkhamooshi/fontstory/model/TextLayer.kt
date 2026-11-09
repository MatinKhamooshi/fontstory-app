package ir.matinkhamooshi.fontstory.model

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import ir.matinkhamooshi.fontstory.font.FontLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

enum class Align { RIGHT, CENTER, LEFT }

/**
 * A text box on the artboard. Position is stored as an anchor that matches the
 * alignment (right edge for right-aligned text, etc.) so editing never makes the
 * box jump away from where the user placed it.
 */
class TextLayer(text: Spannable, var baseStyle: String) {
    /** While the layer is being edited this is the EditText's own Editable. */
    var text: Spannable = text
        set(v) { field = v; invalidate() }

    var anchorX = 540f
    var top = 800f
    var baseSize = 110f
    var baseColor = Color.WHITE
    var align = Align.CENTER
        private set
    var lineSpacing = 1.15f
    /** Live pinch factor; baked into font sizes when the gesture ends. */
    var tempScale = 1f

    private var cached: StaticLayout? = null
    private val tmpRect = RectF()

    fun invalidate() { cached = null }

    fun isBlank() = text.isBlank()

    fun layout(): StaticLayout {
        cached?.let { return it }
        val src = text
        val out = SpannableStringBuilder(src.toString())
        for (m in src.getSpans(0, src.length, FontMark::class.java)) {
            span(out, RenderFont(FontLibrary.typeface(m.styleId)), src, m)
        }
        for (m in src.getSpans(0, src.length, SizeMark::class.java)) span(out, RenderSize(m.px), src, m)
        for (m in src.getSpans(0, src.length, ColorMark::class.java)) span(out, ForegroundColorSpan(m.color), src, m)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = FontLibrary.typeface(baseStyle)
            textSize = baseSize
            color = baseColor
        }
        val rtl = TextDirectionHeuristics.FIRSTSTRONG_RTL.isRtl(out, 0, out.length)
        val alignment = when (align) {
            Align.CENTER -> Layout.Alignment.ALIGN_CENTER
            Align.RIGHT -> if (rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
            Align.LEFT -> if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        }
        // Width = widest paragraph, so text only breaks where the user pressed Enter.
        val width = max(ceil(Layout.getDesiredWidth(out, paint)).toInt() + 2, (baseSize * 0.5f).toInt().coerceAtLeast(8))
        val l = StaticLayout.Builder.obtain(out, 0, out.length, paint, width)
            .setAlignment(alignment)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setLineSpacing(0f, lineSpacing)
            .setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .build()
        cached = l
        return l
    }

    private fun span(out: SpannableStringBuilder, what: Any, src: Spanned, mark: Any) {
        val s = src.getSpanStart(mark)
        val e = src.getSpanEnd(mark)
        if (s in 0 until e && e <= out.length) out.setSpan(what, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Box in artboard coordinates (unscaled by [tempScale]). */
    fun bounds(out: RectF): RectF {
        val l = layout()
        val w = l.width.toFloat()
        val left = when (align) {
            Align.RIGHT -> anchorX - w
            Align.CENTER -> anchorX - w / 2f
            Align.LEFT -> anchorX
        }
        out.set(left, top, left + w, top + l.height)
        return out
    }

    /** Box including the live pinch scale. */
    fun visualBounds(out: RectF): RectF {
        bounds(out)
        if (tempScale != 1f) {
            val cx = out.centerX(); val cy = out.centerY()
            val hw = out.width() / 2f * tempScale; val hh = out.height() / 2f * tempScale
            out.set(cx - hw, cy - hh, cx + hw, cy + hh)
        }
        return out
    }

    fun draw(c: Canvas) {
        val l = layout()
        val b = bounds(tmpRect)
        c.save()
        if (tempScale != 1f) c.scale(tempScale, tempScale, b.centerX(), b.centerY())
        c.translate(b.left, b.top)
        l.draw(c)
        c.restore()
    }

    fun changeAlign(a: Align) {
        val b = bounds(tmpRect)
        align = a
        anchorX = when (a) {
            Align.RIGHT -> b.right
            Align.CENTER -> b.centerX()
            Align.LEFT -> b.left
        }
        invalidate()
    }

    fun maxSize(): Float {
        var m = baseSize
        for (s in text.getSpans(0, text.length, SizeMark::class.java)) m = max(m, s.px)
        return m
    }

    /** Multiplies every size in the layer, keeping the box centred where it was. */
    fun scaleSizes(f: Float) {
        if (f == 1f) return
        val before = RectF(bounds(tmpRect))
        baseSize = (baseSize * f).coerceIn(MIN_SIZE, MAX_SIZE)
        for (m in text.getSpans(0, text.length, SizeMark::class.java)) {
            val s = text.getSpanStart(m); val e = text.getSpanEnd(m)
            text.removeSpan(m)
            if (s < e) text.setSpan(SizeMark((m.px * f).coerceIn(MIN_SIZE, MAX_SIZE)), s, e, Marks.FLAGS)
        }
        invalidate()
        val after = bounds(tmpRect)
        anchorX += before.centerX() - after.centerX()
        top += before.centerY() - after.centerY()
        invalidate()
    }

    fun bakeScale() {
        val f = tempScale
        tempScale = 1f
        scaleSizes(f)
    }

    fun copy(): TextLayer = TextLayer(Marks.cleanCopy(text), baseStyle).also {
        it.anchorX = anchorX; it.top = top; it.baseSize = baseSize; it.baseColor = baseColor
        it.align = align; it.lineSpacing = lineSpacing
    }

    fun toJson(): JSONObject {
        val marks = JSONArray()
        fun add(k: String, m: Any, v: Any) {
            val s = text.getSpanStart(m); val e = text.getSpanEnd(m)
            if (s in 0 until e) marks.put(JSONObject().put("k", k).put("s", s).put("e", e).put("v", v))
        }
        text.getSpans(0, text.length, FontMark::class.java).forEach { add("f", it, it.styleId) }
        text.getSpans(0, text.length, SizeMark::class.java).forEach { add("s", it, it.px.toDouble()) }
        text.getSpans(0, text.length, ColorMark::class.java).forEach { add("c", it, it.color) }
        return JSONObject()
            .put("text", text.toString())
            .put("x", anchorX.toDouble()).put("y", top.toDouble())
            .put("style", baseStyle).put("size", baseSize.toDouble()).put("color", baseColor)
            .put("align", align.name).put("ls", lineSpacing.toDouble())
            .put("marks", marks)
    }

    companion object {
        const val MIN_SIZE = 4f
        const val MAX_SIZE = 2000f
        const val MAX_TEXT = 2000

        fun fromJson(o: JSONObject): TextLayer {
            val str = o.optString("text").take(MAX_TEXT)
            val sp = SpannableStringBuilder(str)
            val marks = o.optJSONArray("marks") ?: JSONArray()
            for (i in 0 until marks.length()) {
                val m = marks.optJSONObject(i) ?: continue
                val s = m.optInt("s", -1); val e = m.optInt("e", -1)
                if (s < 0 || e <= s || e > str.length) continue
                val span: Any = when (m.optString("k")) {
                    "f" -> FontMark(m.optString("v"))
                    "s" -> SizeMark(m.optDouble("v", 100.0).toFloat().coerceIn(MIN_SIZE, MAX_SIZE))
                    "c" -> ColorMark(m.optInt("v", Color.WHITE))
                    else -> continue
                }
                sp.setSpan(span, s, e, Marks.FLAGS)
            }
            return TextLayer(sp, o.optString("style", "sys:400")).apply {
                anchorX = o.optDouble("x", 540.0).toFloat()
                top = o.optDouble("y", 800.0).toFloat()
                baseSize = o.optDouble("size", 110.0).toFloat().coerceIn(MIN_SIZE, MAX_SIZE)
                baseColor = o.optInt("color", Color.WHITE)
                align = runCatching { Align.valueOf(o.optString("align")) }.getOrDefault(Align.CENTER)
                lineSpacing = o.optDouble("ls", 1.15).toFloat().coerceIn(0.5f, 3f)
            }
        }
    }
}

private class RenderFont(private val tf: Typeface) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) { p.typeface = tf }
    override fun updateDrawState(p: TextPaint) { p.typeface = tf }
}

private class RenderSize(private val px: Float) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) { p.textSize = px }
    override fun updateDrawState(p: TextPaint) { p.textSize = px }
}

class Document {
    var artW = 1080
    var artH = 1920
    /** Preview-only background (0 = checkerboard). Never exported. */
    var previewBg = 0xFF3A3A3A.toInt()
    val layers = ArrayList<TextLayer>()

    fun toJson(): JSONObject = JSONObject()
        .put("w", artW).put("h", artH).put("bg", previewBg)
        .put("layers", JSONArray().apply { layers.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject) = Document().apply {
            artW = o.optInt("w", 1080).coerceIn(100, 4096)
            artH = o.optInt("h", 1920).coerceIn(100, 4096)
            previewBg = o.optInt("bg", previewBg)
            val arr = o.optJSONArray("layers") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { layers += TextLayer.fromJson(it) }
        }
    }
}

/** Auto-saves the current design in private storage. */
object ProjectStore {
    private fun file(dir: File) = File(dir, "project.json")

    fun load(dir: File): Document = try {
        val f = file(dir)
        if (f.isFile) Document.fromJson(JSONObject(f.readText())) else Document()
    } catch (_: Exception) {
        Document()
    }

    fun save(dir: File, json: String) {
        try {
            val tmp = File(dir, "project.json.tmp")
            tmp.writeText(json)
            tmp.renameTo(file(dir))
        } catch (_: Exception) {
        }
    }
}
