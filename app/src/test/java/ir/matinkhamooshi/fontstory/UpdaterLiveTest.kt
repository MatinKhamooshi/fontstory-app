package ir.matinkhamooshi.fontstory

import ir.matinkhamooshi.fontstory.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** Hits the real GitHub API; runs only with -Pfontstory.netTests=true. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterLiveTest {
    @Test
    fun checksAndDownloadsLatestRelease() {
        Assume.assumeTrue(System.getProperty("fontstory.netTests") == "true")
        val info = Updater.check(currentCode = 1L)
        assertNotNull(info)
        println("latest=${info!!.version} code=${info.versionCode} size=${info.size} sha=${info.sha256}")
        assertNull(Updater.check(currentCode = info.versionCode)) // same version -> no update

        val f = File.createTempFile("upd", ".apk")
        Updater.download(info, f, { _, _ -> }, { false })
        assertEquals(info.size, f.length())
        val hex = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        info.sha256?.let { assertEquals(it, hex) }
        println("downloaded ok sha=$hex")
    }
}
