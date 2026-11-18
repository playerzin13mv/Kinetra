package com.kinetra.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class PreviewView(ctx: Context) : View(ctx) {
    interface Listener {
        fun onSelect(id: String?)
        fun onDragStart()
        fun onMove(l: Layer, dx: Float, dy: Float)
        fun onPinch(l: Layer, scaleFactor: Float, rotDeg: Float)
    }

    var listener: Listener? = null
    var renderer: Renderer? = null
    var project: Project? = null
    var t = 0L
    var selectedId: String? = null
    var showGrid = false

    private var vs = 1f
    private var ox = 0f
    private var oy = 0f
    private val checkPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var pushed = false
    private var pinching = false
    private var tapEmpty = false
    private var dragId: String? = null
    private var pd = 0f
    private var pa = 0f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        val bm = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.drawColor(0xFFE6E6E6.toInt())
        val p = Paint()
        p.color = 0xFFBDBDBD.toInt()
        c.drawRect(0f, 0f, 10f, 10f, p)
        c.drawRect(10f, 10f, 20f, 20f, p)
        checkPaint.shader = BitmapShader(bm, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onDraw(c: Canvas) {
        val p = project ?: return
        val r = renderer ?: return
        c.drawColor(C.BG)
        val pad = 12 * resources.displayMetrics.density
        vs = min((width - 2 * pad) / p.width, (height - 2 * pad) / p.height)
        ox = (width - p.width * vs) / 2f
        oy = (height - p.height * vs) / 2f
        if (p.bg == 0) c.drawRect(ox, oy, ox + p.width * vs, oy + p.height * vs, checkPaint)
        c.save()
        c.translate(ox, oy)
        c.scale(vs, vs)
        r.renderScene(c, t)
        if (showGrid) {
            linePaint.color = 0x88FFFFFF.toInt()
            linePaint.strokeWidth = 1f / vs
            val w = p.width.toFloat()
            val h = p.height.toFloat()
            c.drawLine(w / 3, 0f, w / 3, h, linePaint)
            c.drawLine(w * 2 / 3, 0f, w * 2 / 3, h, linePaint)
            c.drawLine(0f, h / 3, w, h / 3, linePaint)
            c.drawLine(0f, h * 2 / 3, w, h * 2 / 3, linePaint)
        }
        val sel = p.layers.find { it.id == selectedId }
        if (sel != null && sel.visible && t >= sel.start && t < sel.end() && sel.type != LT.AUDIO) {
            r.drawSelection(c, sel, t, vs)
        }
        c.restore()
        linePaint.style = Paint.Style.STROKE
        linePaint.color = 0x55FFFFFF
        linePaint.strokeWidth = 1f
        c.drawRect(ox, oy, ox + p.width * vs, oy + p.height * vs, linePaint)
        linePaint.style = Paint.Style.FILL
    }

    private fun dist(e: MotionEvent): Float = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun ang(e: MotionEvent): Float =
        Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = renderer ?: return false
        val p = project ?: return false
        val slop = 8 * resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                moved = false
                pushed = false
                pinching = false
                val h = r.hitTest((e.x - ox) / vs, (e.y - oy) / vs, t)
                dragId = h?.id
                tapEmpty = h == null
                if (h != null && h.id != selectedId) {
                    selectedId = h.id
                    listener?.onSelect(h.id)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    pinching = true
                    pd = dist(e)
                    pa = ang(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && e.pointerCount >= 2) {
                    val sel = p.layers.find { it.id == selectedId } ?: return true
                    val d = dist(e)
                    val a = ang(e)
                    var da = a - pa
                    while (da > 180f) da -= 360f
                    while (da < -180f) da += 360f
                    if (!pushed) {
                        listener?.onDragStart()
                        pushed = true
                    }
                    if (pd > 0f) listener?.onPinch(sel, d / pd, da)
                    pd = d
                    pa = a
                    moved = true
                } else if (!pinching && dragId != null) {
                    if (!moved && hypot(e.x - downX, e.y - downY) < slop) return true
                    moved = true
                    val sel = p.layers.find { it.id == dragId } ?: return true
                    if (!pushed) {
                        listener?.onDragStart()
                        pushed = true
                    }
                    listener?.onMove(sel, (e.x - lastX) / vs, (e.y - lastY) / vs)
                    lastX = e.x
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && tapEmpty && !pinching) {
                    selectedId = null
                    listener?.onSelect(null)
                }
            }
        }
        return true
    }
}
