package ir.matinkhamooshi.fontstory

import ir.matinkhamooshi.fontstory.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterTagTest {
    @Test
    fun parsesTags() {
        assertEquals(10000L to "1.0.0", Updater.parseTag("v1.0.0"))
        assertEquals(10203L to "1.2.3", Updater.parseTag("v1.2.3"))
        assertEquals(20100L to "2.1.0", Updater.parseTag("v2.1.0"))
        assertNull(Updater.parseTag("1.0.0"))
        assertNull(Updater.parseTag("v1.0"))
        assertNull(Updater.parseTag("v1.0.0-beta"))
        assertNull(Updater.parseTag("v1.0.0/../../x"))
    }
}
