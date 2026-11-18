package com.kinetra.motion

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Decodificador de vídeo sequencial (MediaExtractor + MediaCodec -> ImageReader).
 * Muito mais estável que o MediaMetadataRetriever para tocar/pré-visualizar vídeo.
 */
class VideoDecoder(path: String) {
    private val ex = MediaExtractor()
    private lateinit var codec: MediaCodec
    private lateinit var reader: ImageReader
    private val info = MediaCodec.BufferInfo()

    var rawW = 0
        private set
    var rawH = 0
        private set
    var rotation = 0
        private set
    val dispW: Int get() = if (rotation == 90 || rotation == 270) rawH else rawW
    val dispH: Int get() = if (rotation == 90 || rotation == 270) rawW else rawH

    private var tolUs = 16_000L
    private var inDone = false
    private var outDone = false
    private var decPts = 0L
    private var cur: Bitmap? = null
    private var curPts = -10_000_000L
    private var released = false
    private var started = false

    init {
        try {
            ex.setDataSource(path)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    idx = i
                    fmt = f
                    break
                }
            }
            if (idx < 0 || fmt == null) throw IllegalStateException("sem trilha de vídeo")
            ex.selectTrack(idx)
            rawW = fmt.getInteger(MediaFormat.KEY_WIDTH)
            rawH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            val fr = frameRate(fmt)
            tolUs = 500_000L / min(max(fr, 10), 120)
            reader = ImageReader.newInstance(rawW, rawH, ImageFormat.YUV_420_888, 4)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, reader.surface, null, 0)
            codec.start()
            started = true
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    private fun frameRate(f: MediaFormat): Int {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return 30
        return try {
            f.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (e: Throwable) {
            try {
                f.getFloat(MediaFormat.KEY_FRAME_RATE).toInt()
            } catch (e2: Throwable) {
                30
            }
        }
    }

    private fun seek(targetUs: Long) {
        ex.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inDone = false
        outDone = false
        decPts = max(0L, ex.sampleTime)
    }

    private fun awaitImage(): Image? {
        for (i in 0 until 100) {
            val img = reader.acquireLatestImage()
            if (img != null) return img
            try {
                Thread.sleep(3)
            } catch (e: InterruptedException) {
                return null
            }
        }
        return null
    }

    @Synchronized
    fun frameAt(ms: Long, step: Int): Bitmap? {
        if (released || !started) return cur
        val targetUs = ms * 1000L
        if (cur != null && abs(curPts - targetUs) <= tolUs) return cur
        if (outDone && targetUs >= decPts) return cur
        if (targetUs < decPts - tolUs || targetUs > decPts + 1_500_000L) seek(targetUs)
        val deadline = SystemClock.uptimeMillis() + 2500
        while (SystemClock.uptimeMillis() < deadline) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(0)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val sz = ex.readSampleData(buf, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 4000)
            if (oi >= 0) {
                val pts = info.presentationTimeUs
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val render = info.size > 0 && pts >= targetUs - tolUs
                codec.releaseOutputBuffer(oi, render)
                decPts = pts
                if (render) {
                    val img = awaitImage()
                    if (img != null) {
                        val b = toBitmap(img, step)
                        img.close()
                        cur = b
                        curPts = pts
                        return b
                    }
                }
                if (eos) {
                    outDone = true
                    return cur
                }
            }
        }
        return cur
    }

    private fun toBitmap(img: Image, step: Int): Bitmap {
        val crop = img.cropRect
        val cw = crop.width()
        val ch = crop.height()
        val ow = max(1, cw / step)
        val oh = max(1, ch / step)
        val px = IntArray(ow * oh)
        val yp = img.planes[0]
        val up = img.planes[1]
        val vp = img.planes[2]
        val yb = yp.buffer
        val ub = up.buffer
        val vb = vp.buffer
        val yrs = yp.rowStride
        val yps = yp.pixelStride
        val urs = up.rowStride
        val ups = up.pixelStride
        val vrs = vp.rowStride
        val vps = vp.pixelStride
        for (j in 0 until oh) {
            val yy = crop.top + j * step
            val yRow = yy * yrs
            val uRow = (yy shr 1) * urs
            val vRow = (yy shr 1) * vrs
            for (i in 0 until ow) {
                val xx = crop.left + i * step
                val y = max((yb.get(yRow + xx * yps).toInt() and 0xFF) - 16, 0)
                val u = (ub.get(uRow + (xx shr 1) * ups).toInt() and 0xFF) - 128
                val v = (vb.get(vRow + (xx shr 1) * vps).toInt() and 0xFF) - 128
                val y298 = 298 * y
                var r = (y298 + 459 * v + 128) shr 8
                var g = (y298 - 55 * u - 137 * v + 128) shr 8
                var b = (y298 + 541 * u + 128) shr 8
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                px[j * ow + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        try {
            if (started) codec.stop()
        } catch (e: Throwable) {
        }
        try {
            codec.release()
        } catch (e: Throwable) {
        }
        try {
            reader.close()
        } catch (e: Throwable) {
        }
        try {
            ex.release()
        } catch (e: Throwable) {
        }
    }
}
