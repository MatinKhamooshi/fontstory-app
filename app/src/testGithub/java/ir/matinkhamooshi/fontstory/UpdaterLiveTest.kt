package ir.matinkhamooshi.fontstory

import ir.matinkhamooshi.fontstory.update.Updater
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hits the real GitHub API; runs only with -Pfontstory.netTests=true. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterLiveTest {
    @Test
    fun findsLatestRelease() {
        Assume.assumeTrue(System.getProperty("fontstory.netTests") == "true")
        val info = Updater.check(currentCode = 1L)
        assertNotNull(info)
        println("latest=${info!!.version} code=${info.versionCode} url=${info.apkUrl}")
        assertNull(Updater.check(currentCode = info.versionCode)) // same version -> no update

        assert(info.apkUrl.startsWith("https://github.com/MatinKhamooshi/fontstory-app/releases/download/v"))
    }
}
