package com.kinetra.motion

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class AudioPreview(var project: Project, private val ctx: Context) {
    private val players = HashMap<String, MediaPlayer>()
    private var playing = false

    private fun create(l: Layer): MediaPlayer? = try {
        val mp = MediaPlayer()
        mp.setDataSource(File(Store.mediaDir(ctx, project.id), l.file).absolutePath)
        mp.prepare()
        mp
    } catch (e: Exception) {
        null
    }

    fun start(t: Long) {
        playing = true
        update(t)
    }

    fun update(t: Long) {
        if (!playing) return
        for (l in project.layers) {
            if (l.type != LT.AUDIO && l.type != LT.VIDEO) continue
            val active = l.visible && t >= l.start && t < l.end()
            var mp = players[l.id]
            if (active) {
                if (mp == null) {
                    mp = create(l) ?: continue
                    players[l.id] = mp
                    mp.seekTo((t - l.start + l.trimIn).toInt())
                    mp.start()
                }
                val v = (l.value(Props.VOL, t - l.start) * l.fadeFactor(t - l.start)).coerceIn(0f, 1f)
                mp.setVolume(v, v)
            } else if (mp != null) {
                try {
                    mp.release()
                } catch (e: Exception) {
                }
                players.remove(l.id)
            }
        }
    }

    fun stop() {
        playing = false
        for (mp in players.values) {
            try {
                mp.release()
            } catch (e: Exception) {
            }
        }
        players.clear()
    }
}

class AacResult(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

object AudioMixer {
    const val SR = 44100

    class Pcm(val data: ShortArray, val sr: Int, val ch: Int)

    private fun decode(path: String, maxUs: Long): Pcm? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    idx = i
                    fmt = f
                    break
                }
            }
            if (idx < 0 || fmt == null) return null
            ex.selectTrack(idx)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var out = ShortArray(sr * ch * 10)
            var n = 0
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0 || ex.sampleTime > maxUs) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val cnt = sb.remaining()
                    if (n + cnt > out.size) out = out.copyOf(max(out.size * 2, n + cnt))
                    sb.get(out, n, cnt)
                    n += cnt
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            codec.stop()
            codec.release()
            return Pcm(out.copyOf(n), sr, max(1, ch))
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
        }
    }

    fun mix(ctx: Context, p: Project, totalMs: Long): ShortArray? {
        val frames = (totalMs * SR / 1000L).toInt()
        val acc = FloatArray(frames * 2)
        var any = false
        val dir = Store.mediaDir(ctx, p.id)
        for (l in p.layers) {
            if (!l.visible || (l.type != LT.AUDIO && l.type != LT.VIDEO)) continue
            val pcm = decode(File(dir, l.file).absolutePath, (l.trimIn + l.duration) * 1000L) ?: continue
            any = true
            val srcFrames = pcm.data.size / pcm.ch
            val startFrame = (l.start * SR / 1000L).toInt()
            val trimFrames = l.trimIn * pcm.sr / 1000L
            val outFrames = (l.duration * SR / 1000L).toInt()
            var vol = 1f
            for (i in 0 until outFrames) {
                val of = startFrame + i
                if (of >= frames) break
                val srcPos = trimFrames + i.toLong() * pcm.sr / SR
                if (srcPos >= srcFrames) break
                if (i % 441 == 0) vol = l.value(Props.VOL, i * 1000L / SR) * l.fadeFactor(i * 1000L / SR)
                val si = srcPos.toInt() * pcm.ch
                val left = pcm.data[si].toFloat()
                val right = if (pcm.ch > 1) pcm.data[si + 1].toFloat() else left
                acc[of * 2] += left * vol
                acc[of * 2 + 1] += right * vol
            }
        }
        if (!any) return null
        val out = ShortArray(acc.size)
        for (i in acc.indices) out[i] = acc[i].coerceIn(-32768f, 32767f).toInt().toShort()
        return out
    }

    fun encode(pcm: ShortArray): AacResult? {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 2)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var inDone = false
        var outDone = false
        var outFmt: MediaFormat? = null
        val list = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        buf.clear()
                        buf.order(ByteOrder.nativeOrder())
                        var cnt = min(buf.capacity() / 2, pcm.size - pos)
                        cnt -= cnt % 2
                        val pts = (pos / 2).toLong() * 1_000_000L / SR
                        if (cnt <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            buf.asShortBuffer().put(pcm, pos, cnt)
                            codec.queueInputBuffer(ii, 0, cnt * 2, pts, 0)
                            pos += cnt
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val bytes = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.get(bytes)
                        val bi = MediaCodec.BufferInfo()
                        bi.set(0, info.size, info.presentationTimeUs, info.flags)
                        list.add(Pair(bytes, bi))
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = codec.outputFormat
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (e: Exception) {
            }
            codec.release()
        }
        val f = outFmt ?: return null
        return AacResult(f, list)
    }
}
