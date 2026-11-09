package ir.matinkhamooshi.fontstory.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

class FontParserTest {
    @Before
    fun requireFonts() = Assume.assumeTrue("Test fonts missing (see README)", File(FONTS).isDirectory)

    private fun fonts(root: String) = File(root).walk()
        .filter { it.isFile && it.name.matches(Regex(".*\\.(ttf|otf|ttc)$", RegexOption.IGNORE_CASE)) }.toList()

    @Test
    fun parsesRealFonts() {
        val files = fonts(FONTS + "") + fonts("/usr/share/fonts")
        assertTrue(files.isNotEmpty())
        val report = StringBuilder()
        var faces = 0
        for (f in files.sortedBy { it.path }) {
            val parsed = FontParser.parse(f.readBytes())
            faces += parsed.size
            for (p in parsed) {
                report.append("${f.path.substringAfterLast("/")}#${p.ttcIndex} | ${p.family} | ${p.styleName} | ${p.weight}${if (p.italic) " italic" else ""}")
                p.wghtAxis?.let { report.append(" | VF ${it.min}-${it.max} inst=${p.instances.joinToString { i -> "${i.name}:${i.wght}" }}") }
                report.append('\n')
            }
        }
        File(OUT + "report.txt").writeText("files=${files.size} faces=$faces\n$report")
    }

    @Test
    fun vazirmatnWeightsDetected() {
        val dir = FONTS + "/fonts/ttf"
        val expect = mapOf("Thin" to 100, "ExtraLight" to 200, "Light" to 300, "Regular" to 400, "Medium" to 500,
            "SemiBold" to 600, "Bold" to 700, "ExtraBold" to 800, "Black" to 900)
        for ((n, w) in expect) {
            val p = FontParser.parse(File("$dir/Vazirmatn-$n.ttf").readBytes()).single()
            assertEquals("Vazirmatn", p.family)
            assertEquals(n, w, p.weight)
        }
    }

    @Test
    fun rejectsGarbageAndTruncation() {
        val good = File(FONTS + "/fonts/ttf/Vazirmatn-Bold.ttf").readBytes()
        var rejected = 0
        val rnd = Random(42)
        repeat(3000) { k ->
            val data = when (k % 3) {
                0 -> good.copyOf(rnd.nextInt(good.size))                          // truncated
                1 -> good.copyOf().also { b -> repeat(50) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() } } // corrupted
                else -> ByteArray(rnd.nextInt(1, 5000)).also { rnd.nextBytes(it) } // random
            }
            try { FontParser.parse(data) } catch (_: FontFormatException) { rejected++ }
            // Any other exception type fails the test (must never crash / OOB).
        }
        println("rejected=$rejected / 3000")
    }

    @Test
    fun namesHeuristics() {
        assertEquals("IRANSans" to "Bold", FontNames.splitFamily("IRANSans Bold"))
        assertEquals("Farhang" to "ExtraBold", FontNames.splitFamily("Farhang ExtraBold"))
        assertEquals("Farhang FaNum" to "", FontNames.splitFamily("Farhang FaNum"))
        assertEquals(600, FontNames.weightFromName("Semi Bold"))
        assertEquals(200, FontNames.weightFromName("UltraLight"))
        assertEquals(900, FontNames.weightFromName("Black"))
        assertNotNull(FontNames.weightFromName("Regular"))
    }
}

private val FONTS = System.getProperty("fontstory.testFonts", "testfonts/package")
private val OUT = System.getProperty("fontstory.testOut", "build/test-out/").also { File(it).mkdirs() }
