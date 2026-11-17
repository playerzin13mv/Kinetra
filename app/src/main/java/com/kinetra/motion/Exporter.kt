package com.kinetra.motion

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ExportOptions(val shortSide: Int, val fps: Int, val bpp: Float, val hevc: Boolean)

object Exporter {
    private fun even(v: Float): Int = max(2, (v / 2f).roundToInt() * 2)

    fun dims(p: Project, shortSide: Int): IntArray {
        val s = shortSide.toFloat() / min(p.width, p.height)
        return intArrayOf(even(p.width * s), even(p.height * s))
    }

    private fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "video" }

    fun exportVideo(
        ctx: Context,
        project: Project,
        opt: ExportOptions,
        progress: (Float) -> Unit,
        cancel: AtomicBoolean
    ): Uri? {
        val d = dims(project, opt.shortSide)
        val w = d[0]
        val h = d[1]
        val total = project.totalDuration()
        val frames = max(1, (total * opt.fps / 1000L).toInt())
        val tmp = File(ctx.cacheDir, "export_tmp.mp4")
        if (tmp.exists()) tmp.delete()

        val aac: AacResult? = try {
            AudioMixer.mix(ctx, project, total)?.let { AudioMixer.encode(it) }
        } catch (e: Throwable) {
            null
        }
        if (cancel.get()) return null

        val mime = if (opt.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val bitrate = (w.toLong() * h * opt.fps * opt.bpp).toLong().coerceIn(1_000_000L, 100_000_000L).toInt()
        val fmt = MediaFormat.createVideoFormat(mime, w, h)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, opt.fps)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val renderer = Renderer(ctx, project, true)
        val error = AtomicReference<Throwable?>(null)

        val drain = Thread {
            val info = MediaCodec.BufferInfo()
            var track = -1
            var started = false
            var count = 0L
            try {
                loop@ while (true) {
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        var at = -1
                        if (aac != null) at = muxer.addTrack(aac.format)
                        muxer.start()
                        started = true
                        if (aac != null) {
                            for ((bytes, bi) in aac.samples) {
                                muxer.writeSampleData(at, ByteBuffer.wrap(bytes), bi)
                            }
                        }
                    } else if (oi >= 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) {
                            info.presentationTimeUs = count * 1_000_000L / opt.fps
                            count++
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                    }
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }

        var ok = false
        try {
            drain.start()
            val sx = w / project.width.toFloat()
            val sy = h / project.height.toFloat()
            for (i in 0 until frames) {
                if (cancel.get()) break
                val err = error.get()
                if (err != null) throw err
                val t = i * 1000L / opt.fps
                val c = surface.lockCanvas(null)
                try {
                    c.drawColor(Color.BLACK)
                    c.save()
                    c.scale(sx, sy)
                    renderer.renderScene(c, t)
                    c.restore()
                } finally {
                    surface.unlockCanvasAndPost(c)
                }
                progress((i + 1f) / frames)
            }
            codec.signalEndOfInputStream()
            drain.join()
            val err2 = error.get()
            if (err2 != null) throw err2
            if (!cancel.get()) {
                muxer.stop()
                ok = true
            }
        } finally {
            try {
                codec.stop()
            } catch (e: Throwable) {
            }
            try {
                codec.release()
            } catch (e: Throwable) {
            }
            try {
                surface.release()
            } catch (e: Throwable) {
            }
            try {
                muxer.release()
            } catch (e: Throwable) {
            }
            renderer.release()
        }
        if (!ok) {
            tmp.delete()
            return null
        }

        val values = ContentValues()
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "${safeName(project.name)}_${System.currentTimeMillis()}.mp4")
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Kinetra")
        values.put(MediaStore.Video.Media.IS_PENDING, 1)
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            tmp.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        ctx.contentResolver.update(uri, values, null, null)
        tmp.delete()
        return uri
    }

    fun exportStill(ctx: Context, project: Project, t: Long, shortSide: Int): Uri? {
        val d = dims(project, shortSide)
        val bmp = Bitmap.createBitmap(d[0], d[1], Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(d[0] / project.width.toFloat(), d[1] / project.height.toFloat())
        val r = Renderer(ctx, project, true)
        r.renderScene(c, t)
        r.release()
        val values = ContentValues()
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "${safeName(project.name)}_${System.currentTimeMillis()}.png")
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kinetra")
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }
}
