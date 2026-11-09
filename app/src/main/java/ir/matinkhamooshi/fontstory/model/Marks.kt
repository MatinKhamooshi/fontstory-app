package ir.matinkhamooshi.fontstory.model

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import ir.matinkhamooshi.fontstory.font.FontLibrary

/*
 * "Marks" are the app's own style spans stored inside a layer's text.
 * They move with the text while the user edits (Android adjusts span ranges),
 * and are converted into real drawing spans only when rendering.
 */

/** Font override for a range. Also renders in the edit box so the user sees the font. */
class FontMark(val styleId: String) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) { p.typeface = FontLibrary.typeface(styleId) }
    override fun updateDrawState(p: TextPaint) { p.typeface = FontLibrary.typeface(styleId) }
}

/** Size override (artboard pixels). Not drawn in the edit box. */
class SizeMark(val px: Float)

/** Colour override (ARGB). Not drawn in the edit box. */
class ColorMark(val color: Int)

object Marks {
    const val FLAGS = Spanned.SPAN_EXCLUSIVE_INCLUSIVE

    /** Replaces marks of [cls] inside [s, e) with [mark], splitting partially covered marks. */
    fun <T : Any> setRange(sp: Spannable, cls: Class<T>, s: Int, e: Int, mark: T?, copy: (T) -> T) {
        if (s >= e) return
        for (m in sp.getSpans(s, e, cls)) {
            val ms = sp.getSpanStart(m)
            val me = sp.getSpanEnd(m)
            if (ms < 0 || me <= s || ms >= e) continue
            sp.removeSpan(m)
            if (ms < s) sp.setSpan(copy(m), ms, s, FLAGS)
            if (me > e) sp.setSpan(copy(m), e, me, FLAGS)
        }
        if (mark != null) sp.setSpan(mark, s, e, FLAGS)
    }

    fun <T : Any> clear(sp: Spannable, cls: Class<T>) {
        for (m in sp.getSpans(0, sp.length, cls)) sp.removeSpan(m)
    }

    /** The mark of [cls] that applies to character [index], or null. */
    fun <T : Any> at(sp: Spanned, cls: Class<T>, index: Int): T? {
        if (sp.isEmpty()) return null
        val i = index.coerceIn(0, sp.length - 1)
        var found: T? = null
        for (m in sp.getSpans(i, i + 1, cls)) {
            if (sp.getSpanStart(m) <= i && sp.getSpanEnd(m) > i) found = m
        }
        return found
    }

    /** Copy containing only the text and our marks (drops IME / spell-check spans). */
    fun cleanCopy(src: CharSequence): SpannableStringBuilder {
        val out = SpannableStringBuilder(src.toString())
        if (src !is Spanned) return out
        fun put(span: Any, s: Int, e: Int) {
            if (s in 0 until e && e <= out.length) out.setSpan(span, s, e, FLAGS)
        }
        for (m in src.getSpans(0, src.length, FontMark::class.java)) put(FontMark(m.styleId), src.getSpanStart(m), src.getSpanEnd(m))
        for (m in src.getSpans(0, src.length, SizeMark::class.java)) put(SizeMark(m.px), src.getSpanStart(m), src.getSpanEnd(m))
        for (m in src.getSpans(0, src.length, ColorMark::class.java)) put(ColorMark(m.color), src.getSpanStart(m), src.getSpanEnd(m))
        return out
    }
}
