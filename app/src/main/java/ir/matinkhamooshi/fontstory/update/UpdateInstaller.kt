package ir.matinkhamooshi.fontstory.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import ir.matinkhamooshi.fontstory.R
import java.io.File

/** Installs a verified APK through the platform PackageInstaller (user confirms in the system UI). */
object UpdateInstaller {
    const val ACTION_STATUS = "ir.matinkhamooshi.fontstory.action.INSTALL_STATUS"

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Explicit intent to our own non-exported receiver; mutable so the system can add the status.
                val intent = Intent(ctx, InstallStatusReceiver::class.java).setAction(ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(ctx, id, intent, flags)
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (_: Exception) { fail(ctx) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and restarted by the system
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // user cancelled
            else -> fail(ctx)
        }
    }

    private fun fail(ctx: Context) =
        Toast.makeText(ctx, R.string.update_install_failed, Toast.LENGTH_LONG).show()
}
