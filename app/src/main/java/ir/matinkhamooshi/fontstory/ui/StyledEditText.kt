package ir.matinkhamooshi.fontstory.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText

/** EditText that reports selection changes. */
class StyledEditText(ctx: Context, attrs: AttributeSet?) : EditText(ctx, attrs) {
    var onSelection: ((start: Int, end: Int) -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke(selStart, selEnd)
    }
}
