package com.kinetra.motion

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class RatioView(ctx: Context, val aw: Int, val ah: Int, var label: String, private val free: Boolean = false) : View(ctx) {
    var on = false
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val boxH = h - 22 * d
        val maxS = min(w - 10 * d, boxH - 6 * d)
        val k = max(aw, ah).toFloat()
        val rw = maxS * aw / k
        val rh = maxS * ah / k
        val cx = w / 2
        val cy = boxH / 2 + 2 * d
        r.set(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2)
        if (on) {
            p.style = Paint.Style.FILL
            p.color = (C.ACCENT and 0x00FFFFFF) or 0x33000000
            c.drawRoundRect(r, 5 * d, 5 * d, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = if (on) C.ACCENT else C.MUTED
        p.pathEffect = if (free) DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) else null
        c.drawRoundRect(r, 5 * d, 5 * d, p)
        p.pathEffect = null
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.textSize = 11 * d
        p.isFakeBoldText = true
        c.drawText(label, cx, h - 5 * d, p)
        p.isFakeBoldText = false
    }
}

class MainActivity : Activity() {
    private lateinit var listBox: LinearLayout
    private lateinit var countTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG

        val root = FrameLayout(this)
        root.setBackgroundColor(C.BG)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(20), dp(16), dp(10), dp(4))
        header.addView(IconView(this, Ic.KEY_FILL, C.ACCENT, 26), lp(dp(30), dp(30)))
        val title = tv("Kinetra", 28f, C.TEXT, true)
        title.letterSpacing = 0.03f
        title.setPadding(dp(8), 0, 0, 0)
        header.addView(title, lp(0, WRAP, 1f))
        header.addView(iconBtn(Ic.GEAR, C.MUTED) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, lp(dp(48), dp(48)))
        col.addView(header, lp(MATCH, WRAP))

        countTv = tv("", 13f, C.MUTED)
        countTv.setPadding(dp(22), 0, dp(22), dp(8))
        col.addView(countTv, lp(MATCH, WRAP))

        val scroll = ScrollView(this)
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        listBox.setPadding(dp(12), dp(4), dp(12), dp(120))
        scroll.addView(listBox)
        col.addView(scroll, lp(MATCH, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        val fab = gradientButton("Novo projeto", Ic.PLUS)
        fab.setOnClickListener { showNewProject() }
        val fp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        fp.bottomMargin = dp(28)
        root.addView(fab, fp)
        setContentView(root)

        askPermissionOnce()
    }

    private fun askPermissionOnce() {
        if (Access.hasAny(this) || Access.asked(this)) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Acesso à galeria")
            .setMessage("O Kinetra precisa ver suas fotos, vídeos e sons para você importar mídia direto pelo app.")
            .setPositiveButton("Permitir") { _, _ -> Access.request(this, 11) }
            .setNegativeButton("Agora não") { _, _ -> Prefs.setB(this, "asked_perm", true) }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun fmtDur(ms: Long, fps: Int): String {
        val m = ms / 60000
        val s = (ms / 1000) % 60
        val f = (ms % 1000) * fps / 1000
        return String.format("%02d:%02d:%02d", m, s, f)
    }

    private fun refresh() {
        listBox.removeAllViews()
        val ps = Store.list(this).sortedByDescending { it.modified }
        countTv.text = if (ps.isEmpty()) "" else if (ps.size == 1) "1 projeto" else "${ps.size} projetos"
        if (ps.isEmpty()) {
            val e = LinearLayout(this)
            e.orientation = LinearLayout.VERTICAL
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(90), 0, 0)
            e.addView(IconView(this, Ic.FILM, C.MUTED, 52), lp(dp(72), dp(72)))
            val t = tv("Nenhum projeto ainda", 17f, C.TEXT, true)
            t.setPadding(0, dp(12), 0, dp(4))
            e.addView(t)
            e.addView(tv("Toque em \"Novo projeto\" para começar.", 13f, C.MUTED))
            listBox.addView(e, lp(MATCH, WRAP))
            return
        }
        var i = 0
        while (i < ps.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(card(ps[i]), lp(0, WRAP, 1f).also { it.setMargins(dp(5), dp(5), dp(5), dp(5)) })
            if (i + 1 < ps.size) {
                row.addView(card(ps[i + 1]), lp(0, WRAP, 1f).also { it.setMargins(dp(5), dp(5), dp(5), dp(5)) })
            } else {
                row.addView(View(this), lp(0, 1, 1f))
            }
            listBox.addView(row, lp(MATCH, WRAP))
            i += 2
        }
    }

    private fun card(p: Project): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(8), dp(8), dp(10))
        box.background = rounded(C.PANEL, dpf(18f), C.STROKE, dp(1))
        box.setOnClickListener {
            startActivity(Intent(this, EditorActivity::class.java).putExtra("id", p.id))
        }

        val thumbBox = FrameLayout(this)
        thumbBox.background = rounded(C.RAISED, dpf(12f))
        thumbBox.clipToOutline = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        val tf = Store.thumb(this, p.id)
        if (tf.exists()) img.setImageBitmap(BitmapFactory.decodeFile(tf.path))
        thumbBox.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        val dur = tv(fmtDur(p.totalDuration(), p.fps), 10f, Color.WHITE, true)
        dur.setPadding(dp(6), dp(2), dp(6), dp(2))
        dur.background = rounded(0xAA000000.toInt(), dpf(6f))
        val dpar = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START)
        dpar.setMargins(dp(6), 0, 0, dp(6))
        thumbBox.addView(dur, dpar)
        val more = IconView(this, Ic.MORE, Color.WHITE, 20)
        more.background = rounded(0x66000000, dpf(16f))
        more.setOnClickListener { v -> showMenu(v, p) }
        val mp = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END)
        mp.setMargins(0, dp(6), dp(6), 0)
        thumbBox.addView(more, mp)
        box.addView(thumbBox, lp(MATCH, dp(110)))

        val name = tv(p.name, 15f, C.TEXT, true)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        name.setPadding(dp(2), dp(8), dp(2), 0)
        box.addView(name, lp(MATCH, WRAP))
        val meta = tv(
            "${p.aspect} · ${min(p.width, p.height)}p · ${p.fps}fps · ${Store.formatSize(Store.sizeOf(this, p.id))}",
            11f, C.MUTED
        )
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        meta.setPadding(dp(2), dp(2), dp(2), 0)
        box.addView(meta, lp(MATCH, WRAP))
        return box
    }

    private fun showMenu(anchor: View, p: Project) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add("Renomear")
        pm.menu.add("Duplicar")
        pm.menu.add("Excluir")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.title.toString()) {
                "Renomear" -> askText("Renomear projeto", p.name, false) { n ->
                    if (n.isNotBlank()) {
                        p.name = n
                        Store.save(this, p)
                        refresh()
                    }
                }
                "Duplicar" -> {
                    Store.duplicate(this, p.id)
                    refresh()
                }
                "Excluir" -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Excluir \"${p.name}\"?")
                    .setMessage("Isso apaga o projeto e as mídias dele.")
                    .setPositiveButton("Excluir") { _, _ ->
                        Store.delete(this, p.id)
                        refresh()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            true
        }
        pm.show()
    }

    private fun showNewProject() {
        val count = Store.list(this).size
        var aw = 16
        var ah = 9
        var resIdx = Prefs.i(this, Prefs.DEF_RES, 2)
        var fpsIdx = Prefs.i(this, Prefs.DEF_FPS, 2)
        var bgIdx = Prefs.i(this, Prefs.DEF_BG, 0)
        var bgCustom = 0xFF000000.toInt()

        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(12), dp(22), dp(14))
        val rad = dpf(28f)
        val sheet = GradientDrawable()
        sheet.setColor(C.PANEL)
        sheet.cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
        box.background = sheet

        val handle = View(this)
        handle.background = rounded(C.STROKE, dpf(3f))
        box.addView(handle, lp(dp(40), dp(4)).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        val head = tv("Novo projeto", 20f, C.TEXT, true)
        head.setPadding(0, dp(16), 0, dp(10))
        box.addView(head, lp(MATCH, WRAP))

        val nameEt = EditText(this)
        nameEt.setText("Novo projeto ${count + 1}")
        nameEt.setTextColor(C.TEXT)
        nameEt.textSize = 16f
        nameEt.setSingleLine()
        nameEt.inputType = InputType.TYPE_CLASS_TEXT
        nameEt.setPadding(dp(14), dp(12), dp(14), dp(12))
        nameEt.background = rounded(C.RAISED, dpf(12f), C.STROKE, dp(1))
        box.addView(nameEt, lp(MATCH, WRAP))

        val lbl = tv("Proporção", 12f, C.MUTED, true)
        lbl.setPadding(dp(2), dp(16), 0, dp(6))
        box.addView(lbl, lp(MATCH, WRAP))

        val ratioRow = LinearLayout(this)
        val rviews = ArrayList<RatioView>()
        val defs = listOf(intArrayOf(16, 9), intArrayOf(9, 16), intArrayOf(4, 5), intArrayOf(1, 1), intArrayOf(4, 3))
        fun select(i: Int) {
            for ((k, rv) in rviews.withIndex()) rv.on = k == i
        }
        for ((i, df) in defs.withIndex()) {
            val rv = RatioView(this, df[0], df[1], "${df[0]}:${df[1]}")
            rv.setOnClickListener {
                aw = df[0]
                ah = df[1]
                select(i)
            }
            rviews.add(rv)
            ratioRow.addView(rv, lp(0, dp(70), 1f))
        }
        val free = RatioView(this, 1, 1, "Livre", true)
        free.setOnClickListener {
            val e1 = EditText(this)
            e1.hint = "Largura"
            e1.inputType = InputType.TYPE_CLASS_NUMBER
            e1.setTextColor(Color.WHITE)
            val e2 = EditText(this)
            e2.hint = "Altura"
            e2.inputType = InputType.TYPE_CLASS_NUMBER
            e2.setTextColor(Color.WHITE)
            val bx = LinearLayout(this)
            bx.setPadding(dp(20), dp(8), dp(20), 0)
            bx.addView(e1, lp(0, WRAP, 1f))
            bx.addView(tv("  :  ", 20f), lp(WRAP, WRAP))
            bx.addView(e2, lp(0, WRAP, 1f))
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Proporção livre")
                .setView(bx)
                .setPositiveButton("OK") { _, _ ->
                    val a = e1.text.toString().toIntOrNull() ?: 0
                    val b = e2.text.toString().toIntOrNull() ?: 0
                    if (a > 0 && b > 0) {
                        aw = a
                        ah = b
                        select(5)
                        free.label = "$a:$b"
                        free.invalidate()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        rviews.add(free)
        ratioRow.addView(free, lp(0, dp(70), 1f))
        select(0)
        box.addView(ratioRow, lp(MATCH, WRAP))

        fun row(label: String, v: View) {
            val rw = LinearLayout(this)
            rw.gravity = Gravity.CENTER_VERTICAL
            rw.addView(tv(label, 15f, C.TEXT, true), lp(0, WRAP, 1f))
            rw.addView(v, lp(0, WRAP, 1.1f))
            box.addView(rw, lp(MATCH, WRAP).also { it.topMargin = dp(12) })
        }
        row("Resolução", dropdown(Opts.RES_NAMES, resIdx) { resIdx = it })
        row("Quadros por segundo", dropdown(Opts.FPS_NAMES, fpsIdx) { fpsIdx = it })
        row("Plano de fundo", dropdown(Opts.BG_NAMES + "Cor personalizada", bgIdx) {
            bgIdx = it
            if (it == 4) pickColor { c -> bgCustom = c }
        })

        val create = gradientButton("Criar projeto", Ic.CHECK)
        create.setOnClickListener {
            val dims = Project.dims(aw, ah, Opts.RES[resIdx])
            val p = Project()
            p.name = nameEt.text.toString().ifBlank { "Projeto" }
            p.width = dims[0]
            p.height = dims[1]
            p.aspect = "$aw:$ah"
            p.fps = Opts.FPS[fpsIdx]
            p.bg = if (bgIdx < 4) Opts.BG_COLORS[bgIdx] else bgCustom
            Store.save(this, p)
            d.dismiss()
            startActivity(Intent(this, EditorActivity::class.java).putExtra("id", p.id))
        }
        box.addView(create, lp(MATCH, WRAP).also { it.topMargin = dp(20) })

        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(MATCH, WRAP)
        d.window?.setGravity(Gravity.BOTTOM)
        d.show()
    }
}
