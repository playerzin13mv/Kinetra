package com.kinetra.motion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class TimelineView(ctx: Context) : View(ctx) {
    interface Listener {
        fun onSeek(t: Long)
        fun onSelect(id: String?)
        fun onEditStart()
        fun onChanged()
    }

    var listener: Listener? = null
    var project: Project? = null
    var t: Long = 0L
        set(v) {
            field = v
            invalidate()
        }
    var selectedId: String? = null
        set(v) {
            field = v
            invalidate()
        }
    var activeProp: String = Props.X
        set(v) {
            field = v
            invalidate()
        }
    var snapOn = true

    private val dn = resources.displayMetrics.density
    private var pxPerMs = 0.09f * dn
    private var scrollY = 0f
    private val rulerH = 32 * dn
    private val rowH = 34 * dn
    private val gap = 5 * dn
    private val rowsTop = rulerH + 10 * dn
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private var mode = 0
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var tgt: Layer? = null
    private var keyTapT = -1L
    private var oStart = 0L
    private var oDur = 0L
    private var oTrim = 0L
    private var oKeyT: Map<String, List<Long>> = emptyMap()
    private var pd = 0f

    private fun xOf(ms: Long): Float = width / 2f + (ms - t) * pxPerMs

    private val icons = mapOf(
        LT.IMAGE to IconDrawable(Ic.IMAGE, 0xFFFFFFFF.toInt(), 1),
        LT.GIF to IconDrawable(Ic.FILM, 0xFFFFFFFF.toInt(), 1),
        LT.VIDEO to IconDrawable(Ic.VIDEO, 0xFFFFFFFF.toInt(), 1),
        LT.AUDIO to IconDrawable(Ic.AUDIO, 0xFFFFFFFF.toInt(), 1),
        LT.TEXT to IconDrawable(Ic.TEXT, 0xFFFFFFFF.toInt(), 1),
        LT.SHAPE to IconDrawable(Ic.SHAPE, 0xFFFFFFFF.toInt(), 1)
    )

    private fun colorFor(tp: LT): Int = when (tp) {
        LT.VIDEO -> 0xFF5B8CFF.toInt()
        LT.IMAGE -> 0xFF3DDC97.toInt()
        LT.GIF -> 0xFFB48CFF.toInt()
        LT.AUDIO -> 0xFF2BD4D4.toInt()
        LT.TEXT -> 0xFFFFB84D.toInt()
        LT.SHAPE -> 0xFFFF6B9A.toInt()
    }

    private fun fmt(ms: Long, fps: Int): String {
        val m = ms / 60000
        val s = (ms / 1000) % 60
        val f = (ms % 1000) * fps / 1000
        return String.format("%02d:%02d:%02d", m, s, f)
    }

    private fun diamond(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        path.reset()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r, cy)
        path.lineTo(cx, cy + r)
        path.lineTo(cx - r, cy)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawPath(path, paint)
    }

    override fun onDraw(c: Canvas) {
        val pr = project ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(C.PANEL)
        val order = pr.layers.asReversed()

        c.save()
        c.clipRect(0f, rulerH, w, h)
        if (order.isEmpty()) {
            textP.color = C.MUTED
            textP.textSize = 13 * dn
            textP.textAlign = Paint.Align.CENTER
            c.drawText("Toque em + para adicionar mídia", w / 2, rowsTop + 40 * dn, textP)
        }
        for ((i, l) in order.withIndex()) {
            val top = rowsTop + i * (rowH + gap) - scrollY
            if (top + rowH < rulerH || top > h) continue
            val x1 = xOf(l.start)
            val x2 = xOf(l.end())
            rect.set(x1, top, x2, top + rowH)
            val base = colorFor(l.type)
            paint.style = Paint.Style.FILL
            paint.color = (base and 0x00FFFFFF) or (if (l.visible) 0x55000000 else 0x22000000)
            c.drawRoundRect(rect, 10 * dn, 10 * dn, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1 * dn
            paint.color = base
            c.drawRoundRect(rect, 10 * dn, 10 * dn, paint)

            c.save()
            c.clipRect(rect)
            paint.style = Paint.Style.FILL
            paint.color = base
            c.drawRect(x1, top, x1 + 5 * dn, top + rowH, paint)
            val tx = max(x1, 0f) + 12 * dn
            val icd = icons[l.type]
            if (icd != null) {
                icd.setBounds(
                    tx.toInt(), (top + rowH / 2 - 8 * dn).toInt(),
                    (tx + 16 * dn).toInt(), (top + rowH / 2 + 8 * dn).toInt()
                )
                icd.draw(c)
            }
            textP.color = 0xFFFFFFFF.toInt()
            textP.textSize = 12 * dn
            textP.textAlign = Paint.Align.LEFT
            val label = (if (l.name.isNotBlank()) l.name else l.type.name.lowercase())
            c.drawText(label, tx + 22 * dn, top + rowH / 2 + 4 * dn, textP)
            c.restore()

            if (l.id == selectedId) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * dn
                paint.color = 0xFFFFFFFF.toInt()
                c.drawRoundRect(rect, 10 * dn, 10 * dn, paint)
                paint.style = Paint.Style.FILL
                paint.color = C.ACCENT
                val hw = 7 * dn
                rect.set(x1, top + 4 * dn, x1 + hw, top + rowH - 4 * dn)
                c.drawRoundRect(rect, 3 * dn, 3 * dn, paint)
                rect.set(x2 - hw, top + 4 * dn, x2, top + rowH - 4 * dn)
                c.drawRoundRect(rect, 3 * dn, 3 * dn, paint)
                val activeTimes = l.tracks[activeProp]?.map { it.t } ?: emptyList()
                for (kt in l.allKeyTimes()) {
                    val kx = xOf(l.start + kt)
                    val on = activeTimes.contains(kt)
                    diamond(c, kx, top + rowH / 2, if (on) 7 * dn else 5 * dn, if (on) C.AMBER else 0xFFFFFFFF.toInt())
                }
            }
        }
        c.restore()

        paint.style = Paint.Style.FILL
        paint.color = C.PANEL
        c.drawRect(0f, 0f, w, rulerH, paint)
        val steps = longArrayOf(100, 250, 500, 1000, 2000, 5000, 10000, 30000, 60000)
        var step = steps.last()
        for (s in steps) {
            if (s * pxPerMs >= 60 * dn) {
                step = s
                break
            }
        }
        val tmin = t - (w / 2f / pxPerMs).toLong()
        val tmax = t + (w / 2f / pxPerMs).toLong()
        var tk = (floor(tmin.toDouble() / step).toLong()) * step
        textP.color = C.MUTED
        textP.textSize = 10 * dn
        textP.textAlign = Paint.Align.CENTER
        paint.color = C.MUTED
        paint.strokeWidth = 1f
        while (tk <= tmax) {
            if (tk >= 0) {
                val x = xOf(tk)
                c.drawLine(x, rulerH - 8 * dn, x, rulerH, paint)
                val sec = tk / 1000
                val lbl = if (step < 1000) String.format("%d.%d", sec, (tk % 1000) / 100) else String.format("%d:%02d", sec / 60, sec % 60)
                c.drawText(lbl, x, rulerH - 12 * dn, textP)
            }
            tk += step
        }
        val ex = xOf(pr.totalDuration())
        paint.color = 0x66FFFFFF
        c.drawLine(ex, rulerH, ex, h, paint)

        paint.color = C.ACCENT
        paint.strokeWidth = 2 * dn
        c.drawLine(w / 2, 0f, w / 2, h, paint)
        val lbl = fmt(t, pr.fps)
        textP.textSize = 12 * dn
        textP.textAlign = Paint.Align.CENTER
        val tw = textP.measureText(lbl) + 16 * dn
        rect.set(w / 2 - tw / 2, 2 * dn, w / 2 + tw / 2, 22 * dn)
        paint.style = Paint.Style.FILL
        paint.color = C.RAISED
        c.drawRoundRect(rect, 6 * dn, 6 * dn, paint)
        textP.color = C.ACCENT
        c.drawText(lbl, w / 2, 17 * dn, textP)
    }

    private fun rowAt(y: Float): Layer? {
        val pr = project ?: return null
        if (y < rulerH) return null
        val order = pr.layers.asReversed()
        val yy = y - rowsTop + scrollY
        if (yy < 0) return null
        val idx = (yy / (rowH + gap)).toInt()
        if (idx >= order.size) return null
        if (yy - idx * (rowH + gap) > rowH) return null
        return order[idx]
    }

    private fun rowTop(l: Layer): Float {
        val pr = project ?: return 0f
        val idx = pr.layers.asReversed().indexOf(l)
        return rowsTop + idx * (rowH + gap) - scrollY
    }

    private fun candidates(self: Layer): List<Long> {
        val pr = project ?: return emptyList()
        val c = ArrayList<Long>()
        c.add(t)
        c.add(0L)
        for (o in pr.layers) if (o !== self) {
            c.add(o.start)
            c.add(o.end())
        }
        return c
    }

    private fun snapStart(ns: Long, dur: Long, self: Layer): Long {
        if (!snapOn) return ns
        val thr = (10 * dn / pxPerMs).toLong()
        var best = ns
        var bd = thr + 1
        for (c in candidates(self)) {
            val d1 = abs(ns - c)
            if (d1 < bd) {
                bd = d1
                best = c
            }
            val d2 = abs(ns + dur - c)
            if (d2 < bd) {
                bd = d2
                best = c - dur
            }
        }
        return best
    }

    private fun snapEdge(v: Long, self: Layer): Long {
        if (!snapOn) return v
        val thr = (10 * dn / pxPerMs).toLong()
        var best = v
        var bd = thr + 1
        for (c in candidates(self)) {
            val d1 = abs(v - c)
            if (d1 < bd) {
                bd = d1
                best = c
            }
        }
        return best
    }

    private fun dist(e: MotionEvent): Float = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pr = project ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                moved = false
                tgt = null
                keyTapT = -1L
                mode = 1
                val l = rowAt(e.y)
                if (l != null) {
                    val x1 = xOf(l.start)
                    val x2 = xOf(l.end())
                    val top = rowTop(l)
                    if (l.id == selectedId) {
                        for (kt in l.allKeyTimes()) {
                            if (abs(e.x - xOf(l.start + kt)) < 14 * dn && abs(e.y - (top + rowH / 2)) < rowH) {
                                keyTapT = l.start + kt
                            }
                        }
                        if (keyTapT >= 0) {
                            mode = 0
                            tgt = l
                            return true
                        }
                        if (abs(e.x - x1) < 20 * dn) mode = 3 else if (abs(e.x - x2) < 20 * dn) mode = 4
                    }
                    if (mode == 1 && e.x >= x1 && e.x <= x2) mode = 2
                    if (mode in 2..4) {
                        tgt = l
                        oStart = l.start
                        oDur = l.duration
                        oTrim = l.trimIn
                        oKeyT = l.tracks.mapValues { en -> en.value.map { it.t } }
                        if (l.id != selectedId) listener?.onSelect(l.id)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    mode = 5
                    pd = dist(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    mode = 5
                    val nd = dist(e)
                    if (pd > 0f) {
                        pxPerMs = (pxPerMs * nd / pd).coerceIn(0.015f * dn, 0.6f * dn)
                        invalidate()
                    }
                    pd = nd
                    moved = true
                    return true
                }
                if (!moved && hypot(e.x - downX, e.y - downY) < 8 * dn) return true
                if (!moved) {
                    moved = true
                    if (mode == 1 && abs(e.y - downY) > 2 * abs(e.x - downX)) mode = 6
                    if (mode in 2..4) listener?.onEditStart()
                }
                val l = tgt
                when (mode) {
                    1 -> {
                        val nt = (t - (e.x - lastX) / pxPerMs).roundToLong().coerceIn(0L, pr.totalDuration())
                        listener?.onSeek(nt)
                    }
                    2 -> if (l != null) {
                        var ns = oStart + ((e.x - downX) / pxPerMs).roundToLong()
                        ns = max(0L, snapStart(ns, l.duration, l))
                        l.start = ns
                        listener?.onChanged()
                    }
                    3 -> if (l != null) {
                        val media = l.type == LT.VIDEO || l.type == LT.AUDIO
                        var delta = ((e.x - downX) / pxPerMs).roundToLong()
                        delta = snapEdge(oStart + delta, l) - oStart
                        var minD = -oStart
                        if (media) minD = max(minD, -oTrim)
                        val maxD = max(oDur - 100, minD)
                        delta = delta.coerceIn(minD, maxD)
                        l.start = oStart + delta
                        l.duration = oDur - delta
                        if (media) l.trimIn = oTrim + delta
                        for ((prop, ks) in l.tracks) {
                            val orig = oKeyT[prop] ?: continue
                            for (i in ks.indices) if (i < orig.size) ks[i].t = orig[i] - delta
                        }
                        listener?.onChanged()
                    }
                    4 -> if (l != null) {
                        val media = l.type == LT.VIDEO || l.type == LT.AUDIO
                        val delta = ((e.x - downX) / pxPerMs).roundToLong()
                        var nd = snapEdge(oStart + oDur + delta, l) - oStart
                        nd = max(100L, nd)
                        if (media && l.srcDuration > 0) nd = min(nd, max(100L, l.srcDuration - l.trimIn))
                        l.duration = nd
                        listener?.onChanged()
                    }
                    6 -> {
                        val rows = pr.layers.size
                        val maxScroll = max(0f, rowsTop + rows * (rowH + gap) - height + 10 * dn)
                        scrollY = (scrollY - (e.y - lastY)).coerceIn(0f, maxScroll)
                        invalidate()
                    }
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    if (keyTapT >= 0) {
                        listener?.onSeek(keyTapT)
                    } else if (tgt == null && downY > rulerH) {
                        listener?.onSelect(null)
                    }
                } else if (mode in 2..4) {
                    listener?.onChanged()
                }
                mode = 0
            }
        }
        return true
    }
}
