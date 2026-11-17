package com.kinetra.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class LT { IMAGE, GIF, VIDEO, AUDIO, TEXT, SHAPE }

object Props {
    const val X = "x"
    const val Y = "y"
    const val SCALE = "scale"
    const val ROT = "rot"
    const val ALPHA = "alpha"
    const val BRIGHT = "bright"
    const val CONTRAST = "contrast"
    const val SAT = "sat"
    const val VOL = "vol"
    val all = listOf(X, Y, SCALE, ROT, ALPHA, BRIGHT, CONTRAST, SAT, VOL)

    fun label(p: String): String = when (p) {
        X -> "Posição X"
        Y -> "Posição Y"
        SCALE -> "Escala"
        ROT -> "Rotação"
        ALPHA -> "Opacidade"
        BRIGHT -> "Brilho"
        CONTRAST -> "Contraste"
        SAT -> "Saturação"
        VOL -> "Volume"
        else -> p
    }

    fun default(p: String): Float = when (p) {
        SCALE, ALPHA, CONTRAST, SAT, VOL -> 1f
        else -> 0f
    }
}

object Ease {
    val names = listOf("Linear", "Entrada", "Saída", "Suave", "Parado", "Elástico")

    fun apply(e: Int, x: Float): Float = when (e) {
        0 -> x
        1 -> x * x
        2 -> 1f - (1f - x) * (1f - x)
        3 -> x * x * (3f - 2f * x)
        4 -> 0f
        else -> {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            val y = x - 1f
            1f + c3 * y * y * y + c1 * y * y
        }
    }
}

class Keyframe(var t: Long, var v: Float, var ease: Int = 3)

class Layer(val id: String = UUID.randomUUID().toString()) {
    var type = LT.IMAGE
    var name = ""
    var file = ""
    var start = 0L
    var duration = 3000L
    var trimIn = 0L
    var srcDuration = 0L
    var text = "Texto"
    var color = -1
    var shape = "rect"
    var w = 400f
    var h = 400f
    var blend = "normal"
    var visible = true
    val base = HashMap<String, Float>()
    val tracks = HashMap<String, MutableList<Keyframe>>()

    fun end(): Long = start + duration

    fun value(p: String, t: Long): Float {
        val ks = tracks[p]
        if (ks == null || ks.isEmpty()) return base[p] ?: Props.default(p)
        if (t <= ks.first().t) return ks.first().v
        if (t >= ks.last().t) return ks.last().v
        for (i in 0 until ks.size - 1) {
            val a = ks[i]
            val b = ks[i + 1]
            if (t >= a.t && t < b.t) {
                val span = (b.t - a.t).toFloat()
                val f = if (span <= 0f) 1f else (t - a.t) / span
                return a.v + (b.v - a.v) * Ease.apply(a.ease, f)
            }
        }
        return ks.last().v
    }

    fun keyIndexAt(p: String, lt: Long, tol: Long = 25L): Int {
        val ks = tracks[p] ?: return -1
        return ks.indexOfFirst { abs(it.t - lt) <= tol }
    }

    fun setValue(p: String, v: Float, lt: Long) {
        val ks = tracks[p]
        if (ks == null || ks.isEmpty()) {
            base[p] = v
            return
        }
        val i = keyIndexAt(p, lt)
        if (i >= 0) {
            ks[i].v = v
        } else {
            ks.add(Keyframe(lt, v, 3))
            ks.sortBy { it.t }
        }
    }

    fun toggleKey(p: String, lt: Long): Boolean {
        val cur = value(p, lt)
        val ks = tracks.getOrPut(p) { ArrayList() }
        val i = keyIndexAt(p, lt)
        if (i >= 0) {
            ks.removeAt(i)
            if (ks.isEmpty()) {
                tracks.remove(p)
                base[p] = cur
            }
            return false
        }
        ks.add(Keyframe(lt, cur, 3))
        ks.sortBy { it.t }
        return true
    }

    fun allKeyTimes(): List<Long> {
        val s = HashSet<Long>()
        for (ks in tracks.values) for (k in ks) s.add(k.t)
        return s.sorted()
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("type", type.name)
        o.put("name", name)
        o.put("file", file)
        o.put("start", start)
        o.put("duration", duration)
        o.put("trimIn", trimIn)
        o.put("srcDuration", srcDuration)
        o.put("text", text)
        o.put("color", color)
        o.put("shape", shape)
        o.put("w", w.toDouble())
        o.put("h", h.toDouble())
        o.put("blend", blend)
        o.put("visible", visible)
        val b = JSONObject()
        for ((k, v) in base) b.put(k, v.toDouble())
        o.put("base", b)
        val t = JSONObject()
        for ((k, ks) in tracks) {
            val a = JSONArray()
            for (kf in ks) {
                val r = JSONArray()
                r.put(kf.t)
                r.put(kf.v.toDouble())
                r.put(kf.ease)
                a.put(r)
            }
            t.put(k, a)
        }
        o.put("tracks", t)
        return o
    }

    fun copy(): Layer {
        val o = toJson()
        o.put("id", UUID.randomUUID().toString())
        return fromJson(o)
    }

    companion object {
        fun fromJson(o: JSONObject): Layer {
            val l = Layer(o.optString("id", UUID.randomUUID().toString()))
            l.type = try {
                LT.valueOf(o.optString("type", "IMAGE"))
            } catch (e: Exception) {
                LT.IMAGE
            }
            l.name = o.optString("name", "")
            l.file = o.optString("file", "")
            l.start = o.optLong("start", 0L)
            l.duration = o.optLong("duration", 3000L)
            l.trimIn = o.optLong("trimIn", 0L)
            l.srcDuration = o.optLong("srcDuration", 0L)
            l.text = o.optString("text", "Texto")
            l.color = o.optInt("color", -1)
            l.shape = o.optString("shape", "rect")
            l.w = o.optDouble("w", 400.0).toFloat()
            l.h = o.optDouble("h", 400.0).toFloat()
            l.blend = o.optString("blend", "normal")
            l.visible = o.optBoolean("visible", true)
            val b = o.optJSONObject("base")
            if (b != null) {
                for (k in b.keys()) l.base[k] = b.getDouble(k).toFloat()
            }
            val t = o.optJSONObject("tracks")
            if (t != null) {
                for (k in t.keys()) {
                    val a = t.getJSONArray(k)
                    val ks = ArrayList<Keyframe>()
                    for (i in 0 until a.length()) {
                        val r = a.getJSONArray(i)
                        ks.add(Keyframe(r.getLong(0), r.getDouble(1).toFloat(), r.getInt(2)))
                    }
                    l.tracks[k] = ks
                }
            }
            return l
        }
    }
}

class Project(val id: String = UUID.randomUUID().toString()) {
    var name = "Novo projeto"
    var width = 1920
    var height = 1080
    var fps = 30
    var bg = 0
    var aspect = "16:9"
    var created = System.currentTimeMillis()
    var modified = created
    val layers = ArrayList<Layer>()

    fun totalDuration(): Long = max(1000L, layers.maxOfOrNull { it.end() } ?: 0L)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("width", width)
        o.put("height", height)
        o.put("fps", fps)
        o.put("bg", bg)
        o.put("aspect", aspect)
        o.put("created", created)
        o.put("modified", modified)
        val a = JSONArray()
        for (l in layers) a.put(l.toJson())
        o.put("layers", a)
        return o
    }

    companion object {
        fun fromJson(s: String): Project {
            val o = JSONObject(s)
            val p = Project(o.getString("id"))
            p.name = o.optString("name", "Projeto")
            p.width = o.optInt("width", 1920)
            p.height = o.optInt("height", 1080)
            p.fps = o.optInt("fps", 30)
            p.bg = o.optInt("bg", 0)
            p.aspect = o.optString("aspect", "16:9")
            p.created = o.optLong("created", System.currentTimeMillis())
            p.modified = o.optLong("modified", p.created)
            val a = o.optJSONArray("layers")
            if (a != null) {
                for (i in 0 until a.length()) p.layers.add(Layer.fromJson(a.getJSONObject(i)))
            }
            return p
        }

        fun dims(aw: Int, ah: Int, shortSide: Int): IntArray {
            fun ev(v: Float): Int = max(2, (v / 2f).roundToInt() * 2)
            return if (aw >= ah) {
                intArrayOf(ev(shortSide.toFloat() * aw / ah), shortSide)
            } else {
                intArrayOf(shortSide, ev(shortSide.toFloat() * ah / aw))
            }
        }
    }
}

object Store {
    fun root(ctx: Context): File {
        val f = File(ctx.filesDir, "projects")
        f.mkdirs()
        return f
    }

    fun dir(ctx: Context, id: String): File = File(root(ctx), id)

    fun mediaDir(ctx: Context, id: String): File {
        val f = File(dir(ctx, id), "media")
        f.mkdirs()
        return f
    }

    fun thumb(ctx: Context, id: String): File = File(dir(ctx, id), "thumb.jpg")

    fun save(ctx: Context, p: Project) {
        p.modified = System.currentTimeMillis()
        val d = dir(ctx, p.id)
        d.mkdirs()
        val tmp = File(d, "project.tmp")
        tmp.writeText(p.toJson().toString())
        tmp.renameTo(File(d, "project.json"))
    }

    fun load(ctx: Context, id: String): Project? = try {
        val f = File(dir(ctx, id), "project.json")
        if (f.exists()) Project.fromJson(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun list(ctx: Context): List<Project> =
        root(ctx).listFiles()?.mapNotNull { load(ctx, it.name) } ?: emptyList()

    fun delete(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
    }

    fun duplicate(ctx: Context, id: String) {
        try {
            val newId = UUID.randomUUID().toString()
            dir(ctx, id).copyRecursively(dir(ctx, newId), true)
            val f = File(dir(ctx, newId), "project.json")
            val o = JSONObject(f.readText())
            o.put("id", newId)
            o.put("name", o.optString("name") + " (cópia)")
            o.put("modified", System.currentTimeMillis())
            f.writeText(o.toString())
        } catch (e: Exception) {
        }
    }

    fun sizeOf(ctx: Context, id: String): Long =
        dir(ctx, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun formatSize(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> String.format("%.1fKB", b / 1024.0)
        else -> String.format("%.1fMB", b / 1048576.0)
    }

    fun mime(ctx: Context, uri: Uri): String = ctx.contentResolver.getType(uri) ?: ""

    fun importMedia(ctx: Context, pid: String, uri: Uri): Pair<String, String>? {
        return try {
            var display = "midia"
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) display = c.getString(i) ?: display
                }
            }
            val ext = display.substringAfterLast('.', "")
            val fname = UUID.randomUUID().toString().take(8) + (if (ext.isNotEmpty()) ".$ext" else "")
            val out = File(mediaDir(ctx, pid), fname)
            ctx.contentResolver.openInputStream(uri)!!.use { i ->
                out.outputStream().use { o -> i.copyTo(o) }
            }
            Pair(fname, display)
        } catch (e: Exception) {
            null
        }
    }

    fun makeThumb(ctx: Context, p: Project) {
        try {
            val w = 360
            val h = max(2, w * p.height / p.width)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFFCFD3DA.toInt())
            val r = Renderer(ctx, p, true)
            c.scale(w / p.width.toFloat(), h / p.height.toFloat())
            val t0 = p.layers.minOfOrNull { it.start } ?: 0L
            r.renderScene(c, t0)
            r.release()
            thumb(ctx, p.id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Throwable) {
        }
    }
}
