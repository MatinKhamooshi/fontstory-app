package ir.matinkhamooshi.fontstory.model

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

object Exporter {
    /** Upper bound for the working bitmap (≈ 160 MB at 4 bytes/pixel would be too much). */
    private const val MAX_PIXELS = 28_000_000L

    class Output(val bitmap: Bitmap, val width: Int, val height: Int)

    /**
     * Renders [layers] (already detached copies) on a transparent canvas at [scale],
     * then crops to the exact pixel bounds of the drawn text with a 1px margin.
     */
    fun render(layers: List<TextLayer>, scale: Float): Output? {
        val visible = layers.filter { !it.isBlank() }
        if (visible.isEmpty()) return null

        // Union of all boxes, padded for glyph overhang (accents, swashes, italics).
        val union = RectF()
        val r = RectF()
        for (l in visible) {
            l.bounds(r)
            val pad = l.maxSize() * 0.75f + 4f
            r.inset(-pad, -pad)
            if (union.isEmpty) union.set(r) else union.union(r)
        }

        var s = scale
        val area = union.width().toDouble() * union.height() * s * s
        if (area > MAX_PIXELS) s *= sqrt(MAX_PIXELS / area).toFloat()

        val ox = floor(union.left)
        val oy = floor(union.top)
        val w = (ceil((union.right - ox) * s).toInt() + 1).coerceAtLeast(1)
        val h = (ceil((union.bottom - oy) * s).toInt() + 1).coerceAtLeast(1)
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        c.scale(s, s)
        c.translate(-ox, -oy)
        visible.forEach { it.draw(c) }

        val crop = alphaBounds(full) ?: run { full.recycle(); return null }
        val outW = crop[2] - crop[0] + 1 + 2 // inclusive pixels + 1px each side
        val outH = crop[3] - crop[1] + 1 + 2
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(full, (1 - crop[0]).toFloat(), (1 - crop[1]).toFloat(), null)
        full.recycle()
        return Output(out, outW, outH)
    }

    /** [minX, minY, maxX, maxY] of non-transparent pixels, or null if empty. */
    private fun alphaBounds(b: Bitmap): IntArray? {
        val w = b.width; val h = b.height
        val row = IntArray(w)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var first = -1; var last = -1
            for (x in 0 until w) if ((row[x] ushr 24) != 0) { first = x; break }
            if (first < 0) continue
            for (x in w - 1 downTo first) if ((row[x] ushr 24) != 0) { last = x; break }
            if (y < minY) minY = y
            maxY = y
            if (first < minX) minX = first
            if (last > maxX) maxX = last
        }
        return if (maxY < 0) null else intArrayOf(minX, minY, maxX, maxY)
    }

    /** Saves to Pictures/FontStory via MediaStore (no storage permission needed). */
    fun savePng(resolver: ContentResolver, bmp: Bitmap): Uri {
        val name = "FontStory_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FontStory")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("insert failed")
        try {
            resolver.openOutputStream(uri)?.use { os ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) throw IllegalStateException("encode failed")
            } ?: throw IllegalStateException("open failed")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
