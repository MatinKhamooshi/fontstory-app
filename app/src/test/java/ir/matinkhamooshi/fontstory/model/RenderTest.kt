package ir.matinkhamooshi.fontstory.model

import android.graphics.Bitmap
import android.graphics.Color
import android.text.SpannableStringBuilder
import androidx.test.core.app.ApplicationProvider
import ir.matinkhamooshi.fontstory.font.FontImporter
import ir.matinkhamooshi.fontstory.font.FontLibrary
import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderTest {
    @Before
    fun requireFonts() = Assume.assumeTrue("Test fonts missing (see README)", File(FONTS).isDirectory)

    @Test
    fun importRenderAndCrop() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        FontLibrary.init(ctx)
        val vf = File(FONTS + "/fonts/variable/Vazirmatn[wght].ttf")
        val st = File(FONTS + "/fonts/ttf/Vazirmatn-Black.ttf")
        val res = FontImporter(ctx.contentResolver).run(listOf(Uri.fromFile(vf), Uri.fromFile(st)), emptyList()) { _, _ -> }
        println("imported styles=${res.styles.size} invalid=${res.invalid}")
        val added = FontLibrary.addStyles(res.styles)
        val fams = FontLibrary.userFamilies()
        fams.forEach { f -> println("family ${f.name}: " + f.styles.joinToString { it.label }) }
        assertTrue(added.isNotEmpty())
        val fam = fams.first { it.name == "Vazirmatn" }
        val bold = FontLibrary.closest(fam, 900, false)
        val thin = FontLibrary.closest(fam, 100, false)

        val sp = SpannableStringBuilder("سلام دنیا\nفونت استوری")
        Marks.setRange(sp, FontMark::class.java, 0, 4, FontMark(bold.id)) { FontMark(it.styleId) }
        Marks.setRange(sp, ColorMark::class.java, 10, 14, ColorMark(Color.YELLOW)) { ColorMark(it.color) }
        Marks.setRange(sp, SizeMark::class.java, 10, 14, SizeMark(180f)) { SizeMark(it.px) }
        val l1 = TextLayer(sp, thin.id).apply { anchorX = 540f; top = 600f; baseSize = 110f }
        val l2 = TextLayer(SpannableStringBuilder("Second"), bold.id).apply { anchorX = 540f; top = 1200f; baseSize = 60f; baseColor = Color.RED }

        // JSON round trip keeps marks
        val back = TextLayer.fromJson(l1.toJson())
        assertEquals(l1.toJson().toString(), back.toJson().toString())

        val out = Exporter.render(listOf(l1, l2), 1f)
        assertNotNull(out)
        val bmp = out!!.bitmap
        println("export ${bmp.width}x${bmp.height}")
        // 1px transparent border, ink touching row/col 1 on every side
        fun rowHasInk(y: Int) = (0 until bmp.width).any { Color.alpha(bmp.getPixel(it, y)) != 0 }
        fun colHasInk(x: Int) = (0 until bmp.height).any { Color.alpha(bmp.getPixel(x, it)) != 0 }
        assertTrue(!rowHasInk(0) && rowHasInk(1) && rowHasInk(bmp.height - 2) && !rowHasInk(bmp.height - 1))
        assertTrue(!colHasInk(0) && colHasInk(1) && colHasInk(bmp.width - 2) && !colHasInk(bmp.width - 1))
        FileOutputStream(OUT + "export_1x.png").use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val out2 = Exporter.render(listOf(l1, l2), 2f)!!
        println("export2x ${out2.width}x${out2.height}")
        assertTrue(out2.width > bmp.width * 1.9)
    }
}

private val FONTS = System.getProperty("fontstory.testFonts", "testfonts/package")
private val OUT = System.getProperty("fontstory.testOut", "build/test-out/").also { File(it).mkdirs() }
