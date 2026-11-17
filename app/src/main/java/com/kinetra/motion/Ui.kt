package com.kinetra.motion

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

object C {
    val ACCENT = 0xFF00E5B0.toInt()
    val NAVY = 0xFF1A1D2E.toInt()
    val DARK_BG = 0xFF11131D.toInt()
    val DARK_PANEL = 0xFF1A1D2B.toInt()
    val DARK_PANEL2 = 0xFF262A3B.toInt()
    val LIGHT_BG = 0xFFF4F5F7.toInt()
    val TEXT_DARK = 0xFF1E2235.toInt()
    val GRAY = 0xFF9AA0AE.toInt()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = radius
    if (strokeW > 0) g.setStroke(strokeW, stroke)
    return g
}

fun Context.tv(text: String, sp: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = sp
    t.setTextColor(color)
    if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
    return t
}

fun Context.chip(text: String, selected: Boolean): TextView {
    val t = tv(text, 13f, if (selected) C.NAVY else Color.WHITE, true)
    t.gravity = Gravity.CENTER
    t.setPadding(dp(14), dp(8), dp(14), dp(8))
    t.background = rounded(if (selected) C.ACCENT else C.DARK_PANEL2, dpf(18f))
    return t
}

fun Context.iconBtn(glyph: String, sizeSp: Float = 20f, onClick: () -> Unit): TextView {
    val t = tv(glyph, sizeSp, Color.WHITE, false)
    t.gravity = Gravity.CENTER
    t.setOnClickListener { onClick() }
    return t
}

fun Context.dropdown(options: List<String>, sel: Int, light: Boolean, onSel: (Int) -> Unit): TextView {
    val t = tv("", 15f, if (light) C.TEXT_DARK else Color.WHITE, true)
    var cur = sel
    fun upd() {
        t.text = options[cur] + "  ▾"
    }
    upd()
    t.setPadding(dp(14), dp(12), dp(14), dp(12))
    t.background = rounded(if (light) Color.WHITE else C.DARK_PANEL2, dpf(12f))
    t.setOnClickListener {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setItems(options.toTypedArray()) { _, i ->
                cur = i
                upd()
                onSel(i)
            }.show()
    }
    return t
}

fun Context.askText(title: String, initial: String, multiline: Boolean, onOk: (String) -> Unit) {
    val et = EditText(this)
    et.setText(initial)
    et.setTextColor(Color.WHITE)
    if (multiline) {
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    } else {
        et.setSingleLine()
    }
    val box = LinearLayout(this)
    box.setPadding(dp(20), dp(8), dp(20), 0)
    box.addView(et, lp(MATCH, WRAP))
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("OK") { _, _ -> onOk(et.text.toString()) }
        .setNegativeButton("Cancelar", null)
        .show()
}

fun Context.pickColor(onPick: (Int) -> Unit) {
    val palette = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFD60A.toInt(),
        0xFF34C759.toInt(), 0xFF00E5B0.toInt(), 0xFF30B0C7.toInt(), 0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(),
        0xFFBF5AF2.toInt(), 0xFFFF2D55.toInt(), 0xFF8E8E93.toInt(), 0xFF7B4A2D.toInt(), 0xFFFFC0CB.toInt()
    )
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(16), dp(8), dp(16), 0)
    val grid = GridLayout(this)
    grid.columnCount = 5
    val hex = EditText(this)
    hex.hint = "#RRGGBB"
    hex.setTextColor(Color.WHITE)
    hex.setHintTextColor(C.GRAY)
    hex.setSingleLine()
    val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("Cor")
        .setView(box)
        .setPositiveButton("OK") { _, _ ->
            try {
                onPick(Color.parseColor(hex.text.toString().trim()))
            } catch (e: Exception) {
            }
        }
        .setNegativeButton("Cancelar", null)
        .create()
    for (c in palette) {
        val v = View(this)
        v.background = rounded(c, dpf(22f), 0x55FFFFFF, dp(1))
        val params = GridLayout.LayoutParams()
        params.width = dp(44)
        params.height = dp(44)
        params.setMargins(dp(6), dp(6), dp(6), dp(6))
        v.setOnClickListener {
            onPick(c)
            dlg.dismiss()
        }
        grid.addView(v, params)
    }
    box.addView(grid)
    box.addView(hex, lp(MATCH, WRAP))
    dlg.show()
}

object Opts {
    val RES = listOf(480, 720, 1080, 1440)
    val RES_NAMES = listOf("480p", "720p", "1080p (FHD)", "1440p (QHD)")
    val FPS = listOf(24, 25, 30, 60)
    val FPS_NAMES = listOf("24 fps", "25 fps", "30 fps", "60 fps")
    val BG_NAMES = listOf("Transparente", "Preto", "Branco", "Cinza")
    val BG_COLORS = listOf(0, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt())
    val IMG_DUR = listOf(1, 2, 3, 5, 10)
    val IMG_DUR_NAMES = listOf("1 segundo", "2 segundos", "3 segundos", "5 segundos", "10 segundos")
    val EXP_FMT = listOf("MP4 (H.264)", "MP4 (HEVC)", "Imagem PNG (quadro atual)")
    val EXP_FPS = listOf(24, 30, 60)
    val EXP_FPS_NAMES = listOf("24 fps", "30 fps", "60 fps")
    val QUALITY_NAMES = listOf("Baixa", "Média", "Alta", "Máxima")
    val QUALITY_BPP = listOf(0.07f, 0.12f, 0.20f, 0.30f)
}

object Prefs {
    const val DEF_RES = "def_res"
    const val DEF_FPS = "def_fps"
    const val DEF_BG = "def_bg"
    const val IMG_DUR = "img_dur"
    const val GRID = "grid"
    const val SNAP = "snap"
    const val HAPTIC = "haptic"
    const val AWAKE = "awake"
    const val AUTOSAVE = "autosave"
    const val EXP_FMT = "exp_fmt"
    const val EXP_RES = "exp_res"
    const val EXP_FPS = "exp_fps"
    const val EXP_Q = "exp_q"

    private fun sp(c: Context) = c.getSharedPreferences("kinetra_prefs", Context.MODE_PRIVATE)
    fun i(c: Context, k: String, d: Int): Int = sp(c).getInt(k, d)
    fun setI(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun b(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    fun setB(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}
