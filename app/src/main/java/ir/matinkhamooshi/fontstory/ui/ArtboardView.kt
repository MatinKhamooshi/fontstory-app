package ir.matinkhamooshi.fontstory.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import ir.matinkhamooshi.fontstory.model.Document
import ir.matinkhamooshi.fontstory.model.TextLayer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class ArtboardView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {

    interface Listener {
        fun onLayerSelected(layer: TextLayer?)
        fun onLayerChanged(layer: TextLayer)
        fun onLayerDoubleTap(layer: TextLayer)
    }

    var doc = Document()
        set(v) { field = v; selected = null; invalidate() }
    var selected: TextLayer? = null
        private set
    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val margin = 14 * density
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f

    private val boardPaint = Paint()
    private val checkerPaint = Paint().apply {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, 0xFF9E9E9E.toInt()); bmp.setPixel(1, 1, 0xFF9E9E9E.toInt())
        bmp.setPixel(1, 0, 0xFFD6D6D6.toInt()); bmp.setPixel(0, 1, 0xFFD6D6D6.toInt())
        shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(10 * density, 10 * density) })
        }
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFC107.toInt()
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFF4081.toInt()
    }
    private val rect = RectF()

    // drag state
    private var dragLayer: TextLayer? = null
    private var dragging = false
    private var scaling = false
    private var downX = 0f
    private var downY = 0f
    private var startAnchor = 0f
    private var startTop = 0f
    private var guideV = false
    private var guideH = false
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            if (selected == null) return false
            scaling = true
            dragging = false
            dragLayer = null
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val l = selected ?: return false
            l.tempScale = (l.tempScale * d.scaleFactor).coerceIn(0.05f, 20f)
            invalidate()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            scaling = false
            selected?.let { it.bakeScale(); listener?.onLayerChanged(it) }
            invalidate()
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            select(hitTest(e.x, e.y))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            hitTest(e.x, e.y)?.let { select(it); listener?.onLayerDoubleTap(it) }
            return true
        }
    })

    fun select(layer: TextLayer?) {
        if (selected !== layer) {
            selected = layer
            listener?.onLayerSelected(layer)
        }
        invalidate()
    }

    private fun computeTransform() {
        val aw = width - 2 * margin
        val ah = height - 2 * margin
        if (aw <= 0 || ah <= 0) return
        scale = min(aw / doc.artW, ah / doc.artH)
        ox = (width - doc.artW * scale) / 2f
        oy = (height - doc.artH * scale) / 2f
    }

    override fun onDraw(c: Canvas) {
        computeTransform()
        c.drawColor(0xFF161616.toInt())
        c.save()
        c.translate(ox, oy)
        c.scale(scale, scale)
        rect.set(0f, 0f, doc.artW.toFloat(), doc.artH.toFloat())
        if (doc.previewBg == 0) {
            c.save(); c.scale(1 / scale, 1 / scale)
            c.drawRect(0f, 0f, doc.artW * scale, doc.artH * scale, checkerPaint)
            c.restore()
        } else {
            boardPaint.color = doc.previewBg
            c.drawRect(rect, boardPaint)
        }
        c.clipRect(rect)
        for (l in doc.layers) l.draw(c)

        guidePaint.strokeWidth = 1.2f * density / scale
        guidePaint.pathEffect = DashPathEffect(floatArrayOf(6 * density / scale, 4 * density / scale), 0f)
        if (guideV) c.drawLine(doc.artW / 2f, 0f, doc.artW / 2f, doc.artH.toFloat(), guidePaint)
        if (guideH) c.drawLine(0f, doc.artH / 2f, doc.artW.toFloat(), doc.artH / 2f, guidePaint)

        selected?.let { l ->
            selPaint.strokeWidth = 1.5f * density / scale
            l.visualBounds(rect)
            val p = 6 * density / scale
            rect.inset(-p, -p)
            c.drawRect(rect, selPaint)
        }
        c.restore()
    }

    private fun hitTest(sx: Float, sy: Float): TextLayer? {
        val x = (sx - ox) / scale
        val y = (sy - oy) / scale
        val tol = 14 * density / scale
        for (i in doc.layers.indices.reversed()) {
            val l = doc.layers[i]
            l.visualBounds(rect)
            rect.inset(-tol, -tol)
            if (rect.contains(x, y)) return l
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragLayer = hitTest(e.x, e.y)
                dragging = false
                downX = e.x; downY = e.y
                dragLayer?.let { startAnchor = it.anchorX; startTop = it.top }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val l = dragLayer
                if (l != null && !scaling && e.pointerCount == 1) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        if (selected !== l) select(l)
                    }
                    if (dragging) {
                        l.anchorX = startAnchor + dx / scale
                        l.top = startTop + dy / scale
                        snap(l)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging) dragLayer?.let { listener?.onLayerChanged(it) }
                dragLayer = null; dragging = false; guideV = false; guideH = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) dragLayer?.let { listener?.onLayerChanged(it) }
                dragLayer = null; dragging = false; guideV = false; guideH = false
                invalidate()
            }
        }
        return true
    }

    /** Snaps the box centre to the artboard's centre lines. */
    private fun snap(l: TextLayer) {
        val th = 10 * density / scale
        l.bounds(rect)
        val dxc = doc.artW / 2f - rect.centerX()
        val dyc = doc.artH / 2f - rect.centerY()
        guideV = abs(dxc) < th
        guideH = abs(dyc) < th
        if (guideV) l.anchorX += dxc
        if (guideH) l.top += dyc
    }

    init {
        isClickable = true
        contentDescription = "Artboard"
        setBackgroundColor(Color.TRANSPARENT)
    }
}
