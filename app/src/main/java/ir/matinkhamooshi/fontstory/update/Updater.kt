package ir.matinkhamooshi.fontstory.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** A newer release found on GitHub. */
data class UpdateInfo(
    val version: String,
    val versionCode: Long,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    /** Lower-case hex SHA-256 published by GitHub for the asset, if available. */
    val sha256: String?,
)

class UpdateException(message: String) : IOException(message)

/**
 * Checks GitHub Releases for a newer version and downloads/verifies the APK.
 *
 * Security model:
 *  - HTTPS only (also enforced app-wide by network_security_config), every redirect re-checked.
 *  - Only assets of this exact repository, named `FontStory-X.Y.Z.apk`, are accepted.
 *  - Size limits on every response; downloaded size and SHA-256 must match GitHub's metadata.
 *  - Before installing, the APK's package name, version and signing certificate must match
 *    this installed app ([verifyApk]). Android enforces the same-signature rule again at install.
 */
object Updater {
    private const val OWNER_REPO = "MatinKhamooshi/fontstory-app"
    private const val API_LATEST = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$OWNER_REPO/releases/download/"
    private val ALLOWED_HOSTS = setOf("github.com", "api.github.com")
    private val ALLOWED_HOST_SUFFIXES = listOf(".githubusercontent.com")
    private const val MAX_JSON = 512 * 1024
    private const val MAX_APK = 60L * 1024 * 1024
    private const val MAX_REDIRECTS = 5

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
                val digest = a.optString("digest").lowercase()
                val sha = if (digest.matches(Regex("^sha256:[0-9a-f]{64}$"))) digest.removePrefix("sha256:") else null
                val notes = json.optString("body").replace("\r", "").take(3000)
                return UpdateInfo(version, code, notes, url, size, sha)
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads into [dest] (app-private). Verifies size and SHA-256. */
    fun download(info: UpdateInfo, dest: File, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        var url = info.apkUrl
        var conn: HttpsURLConnection
        var redirects = 0
        while (true) {
            conn = open(url)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw UpdateException("redirect without location")
                conn.disconnect()
                if (++redirects > MAX_REDIRECTS) throw UpdateException("too many redirects")
                url = URL(URL(url), loc).toString()
                continue
            }
            if (code != 200) { conn.disconnect(); throw UpdateException("HTTP $code") }
            break
        }

        val tmp = File(dest.parentFile, dest.name + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (cancelled()) throw UpdateException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > info.size) throw UpdateException("file larger than expected")
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        progress(total, info.size)
                    }
                    if (total != info.size) throw UpdateException("incomplete download")
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && hex != info.sha256) throw UpdateException("checksum mismatch")
            if (!tmp.renameTo(dest)) throw UpdateException("cannot save file")
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    /** True only if [apk] is this app, newer, and signed with exactly the same certificate(s). */
    fun verifyApk(ctx: Context, apk: File, info: UpdateInfo): Boolean {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != ctx.packageName) return false
        if (archive.longVersionCode != info.versionCode) return false
        if (archive.longVersionCode <= currentVersionCode(ctx)) return false

        val installed = pm.getPackageInfo(ctx.packageName, flags)
        val mine = signerDigests(installed) ?: return false
        val theirs = signerDigests(archive) ?: return false
        return mine.isNotEmpty() && mine == theirs
    }

    private fun signerDigests(p: PackageInfo): Set<String>? {
        val si = p.signingInfo ?: return null
        val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        if (certs.isNullOrEmpty()) return null
        // For a single signer compare the current certificate (last in history).
        val chosen = if (si.hasMultipleSigners()) certs.toList() else listOf(certs.last())
        return chosen.mapTo(HashSet()) { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    /** Removes leftover downloads. */
    fun cleanup(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun open(url: String): HttpsURLConnection {
        val u = URL(url)
        if (u.protocol != "https") throw UpdateException("insecure URL")
        val host = u.host.lowercase()
        if (host !in ALLOWED_HOSTS && ALLOWED_HOST_SUFFIXES.none { host.endsWith(it) }) {
            throw UpdateException("unexpected host")
        }
        val c = u.openConnection() as HttpsURLConnection
        c.instanceFollowRedirects = false // each redirect is re-validated in download()
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
