package com.kinetra.motion

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class Ic {
    BACK, GEAR, EXPORT, UNDO, REDO, SKIP_START, SKIP_END, PLAY, PAUSE, PLUS, KEY, KEY_FILL,
    PREV, NEXT, IMAGE, VIDEO, AUDIO, TEXT, SHAPE, CLOSE, MORE, TRASH, COPY, CUT, EYE, EYE_OFF,
    UP, DOWN, BLEND, PALETTE, FOLDER, FILM, SLIDERS, CHEVRON, CHECK, LAYERS, GRID,
    FLIP_H, FLIP_V, FADE, CURVE
}

class IconDrawable(private val ic: Ic, var tint: Int, private val sizePx: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        c.scale(s, s)
        glyph(c, ic)
        c.restore()
    }

    private fun st() {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = tint
    }

    private fun fl() {
        p.style = Paint.Style.FILL
        p.color = tint
    }

    private fun line(c: Canvas, a: Float, b: Float, x: Float, y: Float) {
        st()
        c.drawLine(a, b, x, y, p)
    }

    private fun poly(c: Canvas, closed: Boolean, filled: Boolean, vararg v: Float) {
        path.reset()
        path.moveTo(v[0], v[1])
        var i = 2
        while (i < v.size) {
            path.lineTo(v[i], v[i + 1])
            i += 2
        }
        if (closed) path.close()
        if (filled) fl() else st()
        c.drawPath(path, p)
    }

    private fun circ(c: Canvas, x: Float, y: Float, r: Float, filled: Boolean = false) {
        if (filled) fl() else st()
        c.drawCircle(x, y, r, p)
    }

    private fun rrect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, filled: Boolean = false) {
        if (filled) fl() else st()
        c.drawRoundRect(l, t, r, b, rad, rad, p)
    }

    private fun glyph(c: Canvas, k: Ic) {
        when (k) {
            Ic.BACK -> {
                line(c, 19f, 12f, 5f, 12f)
                poly(c, false, false, 11f, 6f, 5f, 12f, 11f, 18f)
            }
            Ic.GEAR -> {
                circ(c, 12f, 12f, 3f)
                circ(c, 12f, 12f, 7f)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    val ca = cos(a).toFloat()
                    val sa = sin(a).toFloat()
                    line(c, 12f + ca * 7f, 12f + sa * 7f, 12f + ca * 10f, 12f + sa * 10f)
                }
            }
            Ic.EXPORT -> {
                line(c, 12f, 15f, 12f, 3f)
                poly(c, false, false, 7f, 8f, 12f, 3f, 17f, 8f)
                poly(c, false, false, 5f, 13f, 5f, 20f, 19f, 20f, 19f, 13f)
            }
            Ic.UNDO -> {
                poly(c, false, false, 9f, 5f, 4f, 10f, 9f, 15f)
                path.reset()
                path.moveTo(4f, 10f)
                path.lineTo(14f, 10f)
                path.cubicTo(18f, 10f, 20f, 13f, 20f, 16f)
                path.cubicTo(20f, 18f, 19f, 19.5f, 17f, 20f)
                st()
                c.drawPath(path, p)
            }
            Ic.REDO -> {
                c.save()
                c.scale(-1f, 1f, 12f, 12f)
                glyph(c, Ic.UNDO)
                c.restore()
            }
            Ic.SKIP_START -> {
                line(c, 6f, 5f, 6f, 19f)
                poly(c, true, true, 19f, 5f, 19f, 19f, 9f, 12f)
            }
            Ic.SKIP_END -> {
                c.save()
                c.scale(-1f, 1f, 12f, 12f)
                glyph(c, Ic.SKIP_START)
                c.restore()
            }
            Ic.PLAY -> poly(c, true, true, 8f, 5f, 19f, 12f, 8f, 19f)
            Ic.PAUSE -> {
                rrect(c, 7f, 5f, 10.5f, 19f, 1.2f, true)
                rrect(c, 13.5f, 5f, 17f, 19f, 1.2f, true)
            }
            Ic.PLUS -> {
                line(c, 12f, 5f, 12f, 19f)
                line(c, 5f, 12f, 19f, 12f)
            }
            Ic.KEY -> poly(c, true, false, 12f, 3f, 21f, 12f, 12f, 21f, 3f, 12f)
            Ic.KEY_FILL -> poly(c, true, true, 12f, 3f, 21f, 12f, 12f, 21f, 3f, 12f)
            Ic.PREV -> poly(c, false, false, 15f, 6f, 9f, 12f, 15f, 18f)
            Ic.NEXT -> poly(c, false, false, 9f, 6f, 15f, 12f, 9f, 18f)
            Ic.IMAGE -> {
                rrect(c, 3f, 4f, 21f, 20f, 3f)
                circ(c, 9f, 10f, 1.8f, true)
                poly(c, false, false, 3.5f, 18f, 9f, 13f, 13f, 17f, 16f, 14f, 20.5f, 18.5f)
            }
            Ic.VIDEO -> {
                rrect(c, 3f, 6f, 16f, 18f, 2.5f)
                poly(c, true, false, 16f, 10.5f, 21f, 7.5f, 21f, 16.5f, 16f, 13.5f)
            }
            Ic.AUDIO -> {
                line(c, 9f, 18f, 9f, 5f)
                poly(c, false, false, 9f, 5f, 19f, 3f, 19f, 16f)
                circ(c, 6.5f, 18f, 2.5f)
                circ(c, 16.5f, 16f, 2.5f)
            }
            Ic.TEXT -> {
                line(c, 5f, 6f, 19f, 6f)
                line(c, 12f, 6f, 12f, 19f)
                line(c, 9f, 19f, 15f, 19f)
            }
            Ic.SHAPE -> {
                rrect(c, 12f, 4f, 20f, 12f, 1.5f)
                circ(c, 9f, 15f, 5f)
            }
            Ic.CLOSE -> {
                line(c, 6f, 6f, 18f, 18f)
                line(c, 18f, 6f, 6f, 18f)
            }
            Ic.MORE -> {
                circ(c, 5f, 12f, 1.8f, true)
                circ(c, 12f, 12f, 1.8f, true)
                circ(c, 19f, 12f, 1.8f, true)
            }
            Ic.TRASH -> {
                line(c, 4f, 7f, 20f, 7f)
                poly(c, false, false, 9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f)
                poly(c, false, false, 6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f)
                line(c, 10f, 11f, 10f, 16f)
                line(c, 14f, 11f, 14f, 16f)
            }
            Ic.COPY -> {
                rrect(c, 9f, 9f, 20f, 20f, 2.5f)
                poly(c, false, false, 15f, 9f, 15f, 5f, 5f, 5f, 5f, 15f, 9f, 15f)
            }
            Ic.CUT -> {
                circ(c, 6f, 18f, 2.5f)
                circ(c, 18f, 18f, 2.5f)
                line(c, 8f, 16f, 17f, 4f)
                line(c, 16f, 16f, 7f, 4f)
            }
            Ic.EYE -> {
                path.reset()
                path.moveTo(2f, 12f)
                path.quadTo(12f, 3f, 22f, 12f)
                path.quadTo(12f, 21f, 2f, 12f)
                path.close()
                st()
                c.drawPath(path, p)
                circ(c, 12f, 12f, 3f)
            }
            Ic.EYE_OFF -> {
                glyph(c, Ic.EYE)
                line(c, 4f, 20f, 20f, 4f)
            }
            Ic.UP -> {
                line(c, 12f, 19f, 12f, 5f)
                poly(c, false, false, 6f, 11f, 12f, 5f, 18f, 11f)
            }
            Ic.DOWN -> {
                line(c, 12f, 5f, 12f, 19f)
                poly(c, false, false, 6f, 13f, 12f, 19f, 18f, 13f)
            }
            Ic.BLEND -> {
                circ(c, 9f, 12f, 6f)
                circ(c, 15f, 12f, 6f)
            }
            Ic.PALETTE -> {
                circ(c, 12f, 12f, 9f)
                circ(c, 8.5f, 10f, 1.3f, true)
                circ(c, 12f, 7.5f, 1.3f, true)
                circ(c, 15.5f, 10f, 1.3f, true)
                circ(c, 15f, 15f, 1.3f, true)
            }
            Ic.FOLDER -> poly(c, true, false, 3f, 6f, 9f, 6f, 11f, 9f, 21f, 9f, 21f, 19f, 3f, 19f)
            Ic.FILM -> {
                rrect(c, 4f, 4f, 20f, 20f, 2.5f)
                line(c, 8f, 4f, 8f, 20f)
                line(c, 16f, 4f, 16f, 20f)
                line(c, 4f, 9f, 8f, 9f)
                line(c, 4f, 15f, 8f, 15f)
                line(c, 16f, 9f, 20f, 9f)
                line(c, 16f, 15f, 20f, 15f)
            }
            Ic.SLIDERS -> {
                line(c, 4f, 7f, 20f, 7f)
                line(c, 4f, 12f, 20f, 12f)
                line(c, 4f, 17f, 20f, 17f)
                circ(c, 9f, 7f, 2f, true)
                circ(c, 15f, 12f, 2f, true)
                circ(c, 8f, 17f, 2f, true)
            }
            Ic.CHEVRON -> poly(c, false, false, 6f, 9f, 12f, 15f, 18f, 9f)
            Ic.CHECK -> poly(c, false, false, 5f, 12f, 10f, 17f, 19f, 7f)
            Ic.LAYERS -> {
                poly(c, true, false, 12f, 3f, 21f, 8f, 12f, 13f, 3f, 8f)
                poly(c, false, false, 3f, 12f, 12f, 17f, 21f, 12f)
                poly(c, false, false, 3f, 16f, 12f, 21f, 21f, 16f)
            }
            Ic.GRID -> {
                rrect(c, 4f, 4f, 20f, 20f, 2f)
                line(c, 9.3f, 4f, 9.3f, 20f)
                line(c, 14.7f, 4f, 14.7f, 20f)
                line(c, 4f, 9.3f, 20f, 9.3f)
                line(c, 4f, 14.7f, 20f, 14.7f)
            }
            Ic.FLIP_H -> {
                line(c, 12f, 3f, 12f, 21f)
                poly(c, true, false, 3f, 19f, 9f, 7f, 9f, 19f)
                poly(c, true, false, 21f, 19f, 15f, 7f, 15f, 19f)
            }
            Ic.FLIP_V -> {
                c.save()
                c.rotate(90f, 12f, 12f)
                glyph(c, Ic.FLIP_H)
                c.restore()
            }
            Ic.FADE -> {
                circ(c, 12f, 12f, 9f)
                fl()
                rf.set(3f, 3f, 21f, 21f)
                c.drawArc(rf, -90f, 180f, true, p)
            }
            Ic.CURVE -> {
                path.reset()
                path.moveTo(4f, 19f)
                path.cubicTo(10f, 19f, 14f, 5f, 20f, 5f)
                st()
                c.drawPath(path, p)
                circ(c, 4f, 19f, 1.6f, true)
                circ(c, 20f, 5f, 1.6f, true)
            }
        }
    }
}

class IconView(ctx: Context, ic: Ic, tint: Int, private val iconDp: Int = 22) : View(ctx) {
    private var d = IconDrawable(ic, tint, 1)

    fun setIcon(ic: Ic, tint: Int) {
        d = IconDrawable(ic, tint, 1)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = (iconDp * resources.displayMetrics.density).toInt()
        val l = (width - s) / 2
        val t = (height - s) / 2
        d.setBounds(l, t, l + s, t + s)
        d.draw(c)
    }
}
