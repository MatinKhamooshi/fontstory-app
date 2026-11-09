package ir.matinkhamooshi.fontstory.update

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** A newer release found on GitHub. */
data class UpdateInfo(
    val version: String,
    val versionCode: Long,
    val notes: String,
    val apkUrl: String,
    val size: Long,
)

class UpdateException(message: String) : IOException(message)

/**
 * Checks GitHub Releases for a newer version (github flavor only).
 *
 * The app never downloads or installs code itself (Google Play "Device and Network Abuse" policy):
 * it only shows the direct link to this repository's signed APK, which the user's browser
 * downloads and Android's own installer installs. Android refuses the update unless it is
 * signed with the same key as the installed app.
 *
 *  - HTTPS only (also enforced app-wide by network_security_config), no redirects followed.
 *  - Only this exact repository's `FontStory-X.Y.Z.apk` release asset URL is accepted.
 *  - Response size is limited.
 */
object Updater {
    private const val OWNER_REPO = "MatinKhamooshi/fontstory-app"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$OWNER_REPO/releases/download/"
    private val ALLOWED_HOSTS = setOf("github.com", "api.github.com")
    private const val MAX_JSON = 512 * 1024
    private const val MAX_APK = 60L * 1024 * 1024

    private val TAG = Regex("^v(\\d{1,3})\\.(\\d{1,2})\\.(\\d{1,2})$")

    /** "v1.2.3" -> (code, "1.2.3"); code = major*10000 + minor*100 + patch, same as Gradle. */
    fun parseTag(tag: String): Pair<Long, String>? {
        val m = TAG.matchEntire(tag.trim()) ?: return null
        val (a, b, c) = m.destructured
        return (a.toLong() * 10000 + b.toLong() * 100 + c.toLong()) to "$a.$b.$c"
    }

    fun currentVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** Returns the latest release if it is newer than [currentCode], otherwise null. Throws on network errors. */
    fun check(currentCode: Long): UpdateInfo? {
        val conn = open(API_LATEST).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            if (conn.responseCode == 404) return null // no release yet
            if (conn.responseCode != 200) throw UpdateException("HTTP ${conn.responseCode}")
            val json = JSONObject(String(readLimited(conn.inputStream, MAX_JSON), Charsets.UTF_8))
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

            val tag = json.optString("tag_name")
            val (code, version) = parseTag(tag) ?: return null
            if (code <= currentCode) return null

            val expectedName = "FontStory-$version.apk"
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name") != expectedName) continue
                val url = a.optString("browser_download_url")
                if (url != "$DOWNLOAD_PREFIX$tag/$expectedName") return null
                val size = a.optLong("size", -1)
                if (size !in 1..MAX_APK) return null
                val notes = json.optString("body").replace("\r", "").take(3000)
                return UpdateInfo(version, code, notes, url, size)
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpsURLConnection {
        val u = URL(url)
        if (u.protocol != "https") throw UpdateException("insecure URL")
        val host = u.host.lowercase()
        if (host !in ALLOWED_HOSTS) {
            throw UpdateException("unexpected host")
        }
        val c = u.openConnection() as HttpsURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.useCaches = false
        c.setRequestProperty("User-Agent", "FontStory-Android")
        return c
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray = input.use {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw UpdateException("response too large")
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
}
