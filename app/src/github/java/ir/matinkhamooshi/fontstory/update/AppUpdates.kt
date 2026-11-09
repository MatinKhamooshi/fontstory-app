package ir.matinkhamooshi.fontstory.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import ir.matinkhamooshi.fontstory.R
import java.util.concurrent.Executors

/**
 * GitHub (sideload) build: tells the user about a new release and opens its signed APK link
 * in the browser. Installation is done by the system installer, never by the app itself.
 */
object AppUpdates {
    const val ENABLED = true

    private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "update-check").apply { isDaemon = true } }
    private var dialogShown = false

    fun check(act: Activity, silent: Boolean) {
        if (!silent) toast(act, R.string.update_checking)
        val current = Updater.currentVersionCode(act)
        net.execute {
            val result = runCatching { Updater.check(current) }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                val info = result.getOrNull()
                when {
                    info != null -> show(act, info)
                    silent -> Unit
                    result.isFailure -> toast(act, R.string.update_check_failed)
                    else -> toast(act, R.string.update_none)
                }
            }
        }
    }

    private fun show(act: Activity, info: UpdateInfo) {
        if (dialogShown) return
        dialogShown = true
        val notes = info.notes.lines()
            .filterNot { it.isBlank() }
            .joinToString("\n") { it.trim().removePrefix("### ").replace(Regex("^[-*] "), "• ") }
            .take(1200)
        AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.update_title, info.version))
            .setMessage(act.getString(R.string.update_message) + if (notes.isNotEmpty()) "\n\n$notes" else "")
            .setPositiveButton(R.string.update_now) { _, _ -> open(act, info.apkUrl) }
            .setNegativeButton(R.string.update_later, null)
            .setOnDismissListener { dialogShown = false }
            .show()
    }

    private fun open(act: Activity, url: String) {
        // URL was already validated by Updater to be this repo's release asset over HTTPS.
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            toast(act, R.string.update_browser_hint)
        } catch (_: Exception) {
            toast(act, R.string.update_check_failed)
        }
    }

    private fun toast(act: Activity, res: Int) = Toast.makeText(act, res, Toast.LENGTH_LONG).show()
}
