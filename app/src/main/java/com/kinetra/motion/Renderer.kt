package com.kinetra.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Renderer(private val ctx: Context, var project: Project, private val export: Boolean) {
    private val mediaDir = Store.mediaDir(ctx, project.id)
    private val bitmaps = HashMap<String, Bitmap?>()
    private val movies = HashMap<String, Movie?>()
    private val videos = HashMap<String, VideoSource?>()
    private val exec = Executors.newSingleThreadExecutor()
    var onAsyncFrame: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val dst = RectF()

    private inner class VideoSource(path: String) {
        val mmr = MediaMetadataRetriever()
        var w = 1
        var h = 1
        private val cache = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?): Boolean = size > 10
        }
        @Volatile
        var last: Bitmap? = null
        private val pending = HashSet<Long>()

        init {
            mmr.setDataSource(path)
            val vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1
            val vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                w = vh
                h = vw
            } else {
                w = vw
                h = vh
            }
        }

        private fun decode(ms: Long): Bitmap? {
            val maxSide = if (export) max(project.width, project.height) else 720
            val sc = min(1f, maxSide.toFloat() / max(w, h))
            val tw = max(2, (w * sc).toInt())
            val th = max(2, (h * sc).toInt())
            return try {
                mmr.getScaledFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, tw, th)
            } catch (e: Throwable) {
                null
            }
        }

        fun frame(ms: Long): Bitmap? {
            val key = if (export) ms else (ms / 66L) * 66L
            synchronized(cache) {
                val c = cache[key]
                if (c != null) {
                    last = c
                    return c
                }
            }
            if (export) {
                val b = decode(key)
                if (b != null) {
                    synchronized(cache) { cache[key] = b }
                    last = b
                }
                return b ?: last
            }
            synchronized(pending) {
                if (!pending.add(key)) return last
            }
            try {
                exec.execute {
                    val b = decode(key)
                    if (b != null) {
                        synchronized(cache) { cache[key] = b }
                        last = b
                    }
                    synchronized(pending) { pending.remove(key) }
                    onAsyncFrame?.invoke()
                }
            } catch (e: Exception) {
            }
            return last
        }

        fun release() {
            try {
                mmr.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun decodeBitmap(f: File): Bitmap? = try {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        val maxSide = if (export) 2560 else 1600
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > maxSide) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        BitmapFactory.decodeFile(f.path, o2)
    } catch (e: Throwable) {
        null
    }

    private fun bmp(l: Layer): Bitmap? {
        if (!bitmaps.containsKey(l.id)) bitmaps[l.id] = decodeBitmap(File(mediaDir, l.file))
        return bitmaps[l.id]
    }

    private fun movie(l: Layer): Movie? {
        if (!movies.containsKey(l.id)) {
            movies[l.id] = try {
                FileInputStream(File(mediaDir, l.file)).use { Movie.decodeStream(it) }
            } catch (e: Throwable) {
                null
            }
        }
        return movies[l.id]
    }

    private fun video(l: Layer): VideoSource? {
        if (!videos.containsKey(l.id)) {
            videos[l.id] = try {
                VideoSource(File(mediaDir, l.file).absolutePath)
            } catch (e: Throwable) {
                null
            }
        }
        return videos[l.id]
    }

    private fun textMetrics(l: Layer): FloatArray {
        val size = project.height * 0.08f
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.textSize = size
        textPaint.typeface = Typeface.DEFAULT_BOLD
        val lines = l.text.split("\n")
        var w = 10f
        for (ln in lines) w = max(w, textPaint.measureText(ln))
        return floatArrayOf(w, size * 1.25f * lines.size)
    }

    fun size(l: Layer): FloatArray {
        val pw = project.width.toFloat()
        val ph = project.height.toFloat()
        fun fit(w: Float, h: Float): FloatArray {
            if (w <= 0f || h <= 0f) return floatArrayOf(pw, ph)
            val s = min(pw / w, ph / h)
            return floatArrayOf(w * s, h * s)
        }
        return when (l.type) {
            LT.IMAGE -> bmp(l)?.let { fit(it.width.toFloat(), it.height.toFloat()) } ?: floatArrayOf(200f, 200f)
            LT.GIF -> movie(l)?.let { fit(it.width().toFloat(), it.height().toFloat()) } ?: floatArrayOf(200f, 200f)
            LT.VIDEO -> video(l)?.let { fit(it.w.toFloat(), it.h.toFloat()) } ?: floatArrayOf(200f, 200f)
            LT.TEXT -> textMetrics(l)
            LT.SHAPE -> floatArrayOf(l.w, l.h)
            LT.AUDIO -> floatArrayOf(0f, 0f)
        }
    }

    private fun resetPaint() {
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
    }

    private fun modeFor(b: String): PorterDuff.Mode? = when (b) {
        "multiply" -> PorterDuff.Mode.MULTIPLY
        "screen" -> PorterDuff.Mode.SCREEN
        "add" -> PorterDuff.Mode.ADD
        "overlay" -> PorterDuff.Mode.OVERLAY
        "darken" -> PorterDuff.Mode.DARKEN
        "lighten" -> PorterDuff.Mode.LIGHTEN
        else -> null
    }

    private fun colorFilterFor(b: Float, c: Float, s: Float): ColorMatrixColorFilter? {
        if (b == 0f && c == 1f && s == 1f) return null
        val cm = ColorMatrix()
        cm.setSaturation(s)
        val t = (-0.5f * c + 0.5f) * 255f + b * 255f
        val cc = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(cc)
        return ColorMatrixColorFilter(cm)
    }

    fun renderScene(canvas: Canvas, t: Long) {
        val pw = project.width.toFloat()
        val ph = project.height.toFloat()
        canvas.save()
        canvas.clipRect(0f, 0f, pw, ph)
        if (project.bg != 0) {
            resetPaint()
            paint.color = project.bg
            canvas.drawRect(0f, 0f, pw, ph, paint)
        }
        for (l in project.layers) {
            if (!l.visible || l.type == LT.AUDIO) continue
            if (t < l.start || t >= l.end()) continue
            drawLayer(canvas, l, t)
        }
        canvas.restore()
    }

    private fun drawLayer(canvas: Canvas, l: Layer, t: Long) {
        val lt = t - l.start
        val a = l.value(Props.ALPHA, lt).coerceIn(0f, 1f)
        if (a <= 0f) return
        val sc = l.value(Props.SCALE, lt)
        if (sc <= 0f) return
        val s = size(l)
        resetPaint()
        val cf = colorFilterFor(
            l.value(Props.BRIGHT, lt), l.value(Props.CONTRAST, lt), l.value(Props.SAT, lt)
        )
        paint.colorFilter = cf
        val mode = modeFor(l.blend)
        if (mode != null) paint.xfermode = PorterDuffXfermode(mode)
        canvas.save()
        canvas.translate(l.value(Props.X, lt), l.value(Props.Y, lt))
        canvas.rotate(l.value(Props.ROT, lt))
        canvas.scale(sc, sc)
        when (l.type) {
            LT.IMAGE -> {
                val b = bmp(l)
                if (b != null) {
                    paint.alpha = (a * 255).toInt()
                    dst.set(-s[0] / 2, -s[1] / 2, s[0] / 2, s[1] / 2)
                    canvas.drawBitmap(b, null, dst, paint)
                }
            }
            LT.GIF -> {
                val m = movie(l)
                if (m != null && m.width() > 0 && m.height() > 0) {
                    val d = m.duration()
                    m.setTime(if (d > 0) ((lt + l.trimIn) % d).toInt() else 0)
                    paint.alpha = (a * 255).toInt()
                    canvas.save()
                    canvas.scale(s[0] / m.width(), s[1] / m.height())
                    m.draw(canvas, -m.width() / 2f, -m.height() / 2f, paint)
                    canvas.restore()
                }
            }
            LT.VIDEO -> {
                val b = video(l)?.frame(lt + l.trimIn)
                if (b != null) {
                    paint.alpha = (a * 255).toInt()
                    dst.set(-s[0] / 2, -s[1] / 2, s[0] / 2, s[1] / 2)
                    canvas.drawBitmap(b, null, dst, paint)
                }
            }
            LT.TEXT -> {
                val size = project.height * 0.08f
                textPaint.reset()
                textPaint.isAntiAlias = true
                textPaint.textSize = size
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = l.color
                textPaint.alpha = (Color.alpha(l.color) * a).toInt()
                textPaint.colorFilter = cf
                textPaint.xfermode = paint.xfermode
                val lh = size * 1.25f
                val lines = l.text.split("\n")
                for ((i, ln) in lines.withIndex()) {
                    canvas.drawText(ln, 0f, -s[1] / 2 + lh * i + lh * 0.82f, textPaint)
                }
            }
            LT.SHAPE -> {
                paint.style = Paint.Style.FILL
                paint.color = l.color
                paint.alpha = (Color.alpha(l.color) * a).toInt()
                val hw = s[0] / 2
                val hh = s[1] / 2
                when (l.shape) {
                    "ellipse" -> {
                        dst.set(-hw, -hh, hw, hh)
                        canvas.drawOval(dst, paint)
                    }
                    "round" -> {
                        dst.set(-hw, -hh, hw, hh)
                        val r = min(s[0], s[1]) * 0.2f
                        canvas.drawRoundRect(dst, r, r, paint)
                    }
                    "triangle" -> {
                        path.reset()
                        path.moveTo(0f, -hh)
                        path.lineTo(hw, hh)
                        path.lineTo(-hw, hh)
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                    "star" -> {
                        path.reset()
                        for (i in 0 until 10) {
                            val ang = Math.PI / 5 * i - Math.PI / 2
                            val rr = if (i % 2 == 0) 1f else 0.4f
                            val px = (cos(ang) * hw * rr).toFloat()
                            val py = (sin(ang) * hh * rr).toFloat()
                            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                    else -> canvas.drawRect(-hw, -hh, hw, hh, paint)
                }
            }
            LT.AUDIO -> {}
        }
        canvas.restore()
    }

    fun drawSelection(canvas: Canvas, l: Layer, t: Long, viewScale: Float) {
        val lt = t - l.start
        val sc = l.value(Props.SCALE, lt)
        val s = size(l)
        if (s[0] <= 0f || sc <= 0f) return
        canvas.save()
        canvas.translate(l.value(Props.X, lt), l.value(Props.Y, lt))
        canvas.rotate(l.value(Props.ROT, lt))
        canvas.scale(sc, sc)
        val k = 1f / (viewScale * max(sc, 0.001f))
        resetPaint()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * k
        paint.color = 0xFF00E5B0.toInt()
        canvas.drawRect(-s[0] / 2, -s[1] / 2, s[0] / 2, s[1] / 2, paint)
        paint.style = Paint.Style.FILL
        val r = 6f * k
        for (cx in listOf(-s[0] / 2, s[0] / 2)) {
            for (cy in listOf(-s[1] / 2, s[1] / 2)) canvas.drawCircle(cx, cy, r, paint)
        }
        canvas.restore()
    }

    fun hitTest(px: Float, py: Float, t: Long): Layer? {
        for (l in project.layers.asReversed()) {
            if (!l.visible || l.type == LT.AUDIO) continue
            if (t < l.start || t >= l.end()) continue
            val lt = t - l.start
            val sc = l.value(Props.SCALE, lt)
            if (sc <= 0f) continue
            val ang = Math.toRadians(l.value(Props.ROT, lt).toDouble())
            val dx = px - l.value(Props.X, lt)
            val dy = py - l.value(Props.Y, lt)
            val lx = ((dx * cos(ang) + dy * sin(ang)) / sc).toFloat()
            val ly = ((-dx * sin(ang) + dy * cos(ang)) / sc).toFloat()
            val s = size(l)
            if (abs(lx) <= s[0] / 2 && abs(ly) <= s[1] / 2) return l
        }
        return null
    }

    fun release() {
        for (v in videos.values) v?.release()
        videos.clear()
        bitmaps.clear()
        movies.clear()
        try {
            exec.shutdownNow()
        } catch (e: Exception) {
        }
    }
}
