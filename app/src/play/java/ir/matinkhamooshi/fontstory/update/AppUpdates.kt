package ir.matinkhamooshi.fontstory.update

import android.app.Activity

/** Google Play build: updates are delivered only by Google Play, so there is nothing to do here. */
object AppUpdates {
    const val ENABLED = false

    @Suppress("UNUSED_PARAMETER")
    fun check(act: Activity, silent: Boolean) = Unit
}
