package ir.matinkhamooshi.fontstory.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.TextView
import ir.matinkhamooshi.fontstory.font.FontFamily
import ir.matinkhamooshi.fontstory.font.FontLibrary
import ir.matinkhamooshi.fontstory.font.FontStyle

object UiKit {
    /** Edge-to-edge aware padding (status bar, nav bar, keyboard, cutout). */
    fun applyInsets(act: Activity, root: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            act.window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, ins ->
                val b = ins.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        } else {
            root.fitsSystemWindows = true
        }
    }

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun itemView(ctx: Context, convert: View?, text: String, tf: Typeface, dropDown: Boolean): TextView {
        val tv = (convert as? TextView) ?: TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        val pad = dp(ctx, if (dropDown) 16 else 12)
        tv.setPadding(pad, dp(ctx, if (dropDown) 12 else 0), pad, dp(ctx, if (dropDown) 12 else 0))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (dropDown) 17f else 15f)
        tv.minHeight = dp(ctx, if (dropDown) 48 else 44)
        tv.text = text
        tv.typeface = tf
        return tv
    }
}

/** Spinner of families, each shown in its own font. */
class FamilyAdapter(private val ctx: Context, val families: List<FontFamily>) : BaseAdapter() {
    override fun getCount() = families.size
    override fun getItem(p: Int) = families[p]
    override fun getItemId(p: Int) = p.toLong()
    private fun tf(f: FontFamily) = FontLibrary.typeface(FontLibrary.closest(f, 400, false).id)
    override fun getView(p: Int, v: View?, parent: ViewGroup?) =
        UiKit.itemView(ctx, v, families[p].name, tf(families[p]), false)
    override fun getDropDownView(p: Int, v: View?, parent: ViewGroup?) =
        UiKit.itemView(ctx, v, families[p].name, tf(families[p]), true)
}

/** Spinner of the weights registered for one family. */
class WeightAdapter(private val ctx: Context, val family: String, val styles: List<FontStyle>) : BaseAdapter() {
    override fun getCount() = styles.size
    override fun getItem(p: Int) = styles[p]
    override fun getItemId(p: Int) = p.toLong()
    override fun getView(p: Int, v: View?, parent: ViewGroup?) =
        UiKit.itemView(ctx, v, styles[p].label, FontLibrary.typeface(styles[p].id), false)
    override fun getDropDownView(p: Int, v: View?, parent: ViewGroup?) =
        UiKit.itemView(ctx, v, styles[p].label, FontLibrary.typeface(styles[p].id), true)
}
