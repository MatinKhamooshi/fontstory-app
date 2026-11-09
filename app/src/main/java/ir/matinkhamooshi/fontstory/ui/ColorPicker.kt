package ir.matinkhamooshi.fontstory.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import ir.matinkhamooshi.fontstory.R

/** Simple HSV + hex + swatches colour picker. Remembers recent colours. */
object ColorPicker {
    private val PRESETS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD400.toInt(), 0xFFE53935.toInt(),
        0xFFFF7043.toInt(), 0xFFF5F0E6.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(),
        0xFF8E24AA.toInt(), 0xFFEC407A.toInt(), 0xFF00BCD4.toInt(), 0xFF795548.toInt(),
        0xFF9E9E9E.toInt(), 0xFF424242.toInt(), 0xFF212121.toInt(), 0xFF607D8B.toInt(),
    )
    private const val PREFS = "colors"
    private const val KEY_RECENT = "recent"

    fun show(act: Activity, initial: Int, allowTransparent: Boolean = false, onPick: (Int) -> Unit) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val hsv = FloatArray(3)
        Color.colorToHSV(if (initial == 0) Color.WHITE else initial, hsv)
        var color = if (initial == 0) Color.WHITE else initial or 0xFF000000.toInt()
        var updating = false

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val preview = View(act)
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(44)))

        val hex = EditText(act).apply {
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(7))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER
            textSize = 16f
        }
        val hue = SeekBar(act).apply { max = 360 }
        val sat = SeekBar(act).apply { max = 100 }
        val val_ = SeekBar(act).apply { max = 100 }

        fun refresh(fromHex: Boolean = false) {
            updating = true
            (preview.background as? GradientDrawable ?: GradientDrawable().also { preview.background = it }).apply {
                cornerRadius = dp(10).toFloat(); setColor(color); setStroke(dp(1), 0x55FFFFFF)
            }
            if (!fromHex) hex.setText("#%06X".format(color and 0xFFFFFF))
            hue.progress = hsv[0].toInt(); sat.progress = (hsv[1] * 100).toInt(); val_.progress = (hsv[2] * 100).toInt()
            updating = false
        }

        fun setColor(c: Int, fromHex: Boolean = false) {
            color = c or 0xFF000000.toInt()
            Color.colorToHSV(color, hsv)
            refresh(fromHex)
        }

        fun row(label: Int, bar: SeekBar) {
            root.addView(TextView(act).apply { setText(label); textSize = 12f; alpha = 0.7f; setPadding(0, dp(8), 0, 0) })
            root.addView(bar)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    if (updating || !user) return
                    hsv[0] = hue.progress.toFloat(); hsv[1] = sat.progress / 100f; hsv[2] = val_.progress / 100f
                    color = Color.HSVToColor(hsv)
                    refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }

        // swatches
        val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recent = prefs.getString(KEY_RECENT, "").orEmpty().split(',').mapNotNull { it.toLongOrNull()?.toInt() }
        val grid = GridLayout(act).apply { columnCount = 8; setPadding(0, dp(10), 0, 0) }
        for (c in (PRESETS.toList() + recent).distinct().take(24)) {
            grid.addView(View(act).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(1), 0x66FFFFFF) }
                setOnClickListener { setColor(c) }
            }, GridLayout.LayoutParams().apply { width = dp(30); height = dp(30); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        root.addView(grid)
        row(R.string.color_hue, hue)
        row(R.string.color_sat, sat)
        row(R.string.color_val, val_)
        root.addView(hex)

        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val t = s.toString().trim().removePrefix("#")
                if (t.length == 6) t.toLongOrNull(16)?.let { setColor(it.toInt(), fromHex = true) }
            }
        })
        refresh()

        val b = AlertDialog.Builder(act)
            .setView(root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val list = (listOf(color) + recent).distinct().take(8)
                prefs.edit().putString(KEY_RECENT, list.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply()
                onPick(color)
            }
            .setNegativeButton(R.string.cancel, null)
        if (allowTransparent) b.setNeutralButton(R.string.checker) { _, _ -> onPick(0) }
        b.show()
    }
}
