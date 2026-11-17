package com.kinetra.motion

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var listBox: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.LIGHT_BG
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = FrameLayout(this)
        root.setBackgroundColor(C.LIGHT_BG)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val header = FrameLayout(this)
        header.setPadding(dp(16), dp(14), dp(16), dp(10))
        val title = tv("Kinetra", 26f, C.TEXT_DARK, true)
        title.letterSpacing = 0.04f
        header.addView(title, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val gear = tv("⚙\uFE0E", 26f, C.TEXT_DARK)
        gear.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        header.addView(gear, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(header, lp(MATCH, WRAP))

        val sub = LinearLayout(this)
        sub.orientation = LinearLayout.HORIZONTAL
        sub.setPadding(dp(20), dp(12), dp(20), dp(4))
        sub.addView(tv("Seus projetos", 20f, C.TEXT_DARK, true), lp(0, WRAP, 1f))
        sub.addView(tv("Organizar por data ▾", 13f, C.TEXT_DARK, true), lp(WRAP, WRAP))
        col.addView(sub, lp(MATCH, WRAP))

        val scroll = ScrollView(this)
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        listBox.setPadding(dp(16), dp(8), dp(16), dp(120))
        scroll.addView(listBox)
        col.addView(scroll, lp(MATCH, 0, 1f))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Color.WHITE)
        nav.elevation = dpf(8f)
        val n1 = tv("Projetos", 13f, C.TEXT_DARK, true)
        n1.gravity = Gravity.CENTER
        val gap = View(this)
        val n2 = tv("Config.", 13f, C.GRAY, true)
        n2.gravity = Gravity.CENTER
        n2.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        nav.addView(n1, lp(0, MATCH, 1f))
        nav.addView(gap, lp(0, MATCH, 1f))
        nav.addView(n2, lp(0, MATCH, 1f))
        col.addView(nav, lp(MATCH, dp(64)))

        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        val fab = tv("+", 34f, C.NAVY, true)
        fab.gravity = Gravity.CENTER
        fab.background = rounded(0xFF00F5A0.toInt(), dpf(32f))
        fab.elevation = dpf(8f)
        fab.setOnClickListener { showNewProject() }
        val fp = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        fp.bottomMargin = dp(32)
        root.addView(fab, fp)

        emptyText = tv("Nenhum projeto ainda.\nToque no + para criar.", 15f, C.GRAY)
        emptyText.gravity = Gravity.CENTER
        setContentView(root)
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
        val projects = Store.list(this).sortedByDescending { it.modified }
        if (projects.isEmpty()) {
            emptyText.setPadding(0, dp(80), 0, 0)
            (emptyText.parent as? android.view.ViewGroup)?.removeView(emptyText)
            listBox.addView(emptyText, lp(MATCH, WRAP))
            return
        }
        val fmt = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("pt", "BR"))
        var lastDay = ""
        for (p in projects) {
            val day = fmt.format(Date(p.modified))
            if (day != lastDay) {
                lastDay = day
                val h = tv(day, 17f, C.TEXT_DARK, true)
                h.setPadding(dp(4), dp(16), 0, dp(8))
                listBox.addView(h, lp(MATCH, WRAP))
            }
            listBox.addView(card(p), lp(MATCH, WRAP).also { it.bottomMargin = dp(10) })
        }
    }

    private fun card(p: Project): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(12), dp(8), dp(12))
        row.background = rounded(Color.WHITE, dpf(18f))
        row.setOnClickListener {
            startActivity(Intent(this, EditorActivity::class.java).putExtra("id", p.id))
        }

        val thumbBox = FrameLayout(this)
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.background = rounded(0xFFD0D3D9.toInt(), dpf(10f))
        img.clipToOutline = true
        val tf = Store.thumb(this, p.id)
        if (tf.exists()) img.setImageBitmap(BitmapFactory.decodeFile(tf.path))
        thumbBox.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        val dur = tv(fmtDur(p.totalDuration(), p.fps), 11f, Color.WHITE, true)
        dur.setPadding(dp(6), dp(2), dp(6), dp(2))
        dur.background = rounded(0xCC000000.toInt(), dpf(6f))
        val dp2 = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START)
        dp2.setMargins(dp(4), 0, 0, dp(4))
        thumbBox.addView(dur, dp2)
        row.addView(thumbBox, lp(dp(86), dp(86)))

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(dp(14), 0, dp(4), 0)
        info.addView(tv(p.name, 17f, C.TEXT_DARK, true))
        val l1 = tv("${p.aspect}     ${min(p.width, p.height)}p", 13f, C.GRAY)
        l1.setPadding(0, dp(6), 0, 0)
        info.addView(l1)
        val l2 = tv("${Store.formatSize(Store.sizeOf(this, p.id))}     ${p.fps}fps", 13f, C.GRAY)
        l2.setPadding(0, dp(2), 0, 0)
        info.addView(l2)
        row.addView(info, lp(0, WRAP, 1f))

        val more = tv("•••", 16f, C.GRAY, true)
        more.setPadding(dp(14), dp(14), dp(14), dp(14))
        more.setOnClickListener { v ->
            val pm = PopupMenu(this, v)
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
        row.addView(more, lp(WRAP, WRAP))
        return row
    }

    private fun showNewProject() {
        val count = Store.list(this).size
        var aw = 16
        var ah = 9
        var resIdx = Prefs.i(this, Prefs.DEF_RES, 2)
        var fpsIdx = Prefs.i(this, Prefs.DEF_FPS, 2)
        var bgIdx = Prefs.i(this, Prefs.DEF_BG, 0)

        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(20), dp(24), dp(12))
        val r = dpf(28f)
        val bgd = GradientDrawable()
        bgd.setColor(0xFFEEF0F3.toInt())
        bgd.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        box.background = bgd

        val tabs = LinearLayout(this)
        tabs.gravity = Gravity.CENTER
        val tProj = tv("PROJETO", 15f, C.ACCENT, true)
        tProj.gravity = Gravity.CENTER
        tProj.setPadding(dp(30), dp(12), dp(30), dp(12))
        tProj.background = rounded(C.NAVY, dpf(12f))
        tabs.addView(tProj)
        box.addView(tabs, lp(MATCH, WRAP))

        val nameEt = EditText(this)
        nameEt.setText("Novo projeto ${count + 1}")
        nameEt.setTextColor(C.TEXT_DARK)
        nameEt.textSize = 20f
        nameEt.setSingleLine()
        box.addView(nameEt, lp(MATCH, WRAP).also { it.topMargin = dp(14) })

        val chipRow = LinearLayout(this)
        chipRow.gravity = Gravity.CENTER_VERTICAL
        val labels = listOf("16:9", "9:16", "4:5", "1:1", "4:3", "✎")
        val ratios = listOf(intArrayOf(16, 9), intArrayOf(9, 16), intArrayOf(4, 5), intArrayOf(1, 1), intArrayOf(4, 3))
        val chips = ArrayList<TextView>()
        var sel = 0
        fun restyle() {
            for ((i, c) in chips.withIndex()) {
                val on = i == sel
                c.setTextColor(if (on) C.NAVY else C.TEXT_DARK)
                c.background = rounded(if (on) 0xFF00F5A0.toInt() else Color.WHITE, dpf(8f), 0xFFB0B5BF.toInt(), dp(1))
            }
        }
        for ((i, lb) in labels.withIndex()) {
            val c = tv(lb, 14f, C.TEXT_DARK, true)
            c.gravity = Gravity.CENTER
            c.setPadding(0, dp(14), 0, dp(14))
            c.setOnClickListener {
                if (i < ratios.size) {
                    sel = i
                    aw = ratios[i][0]
                    ah = ratios[i][1]
                    restyle()
                } else {
                    val e1 = EditText(this)
                    e1.hint = "Largura"
                    e1.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    e1.setTextColor(Color.WHITE)
                    val e2 = EditText(this)
                    e2.hint = "Altura"
                    e2.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    e2.setTextColor(Color.WHITE)
                    val bx = LinearLayout(this)
                    bx.setPadding(dp(20), dp(8), dp(20), 0)
                    bx.addView(e1, lp(0, WRAP, 1f))
                    bx.addView(tv(" : ", 20f), lp(WRAP, WRAP))
                    bx.addView(e2, lp(0, WRAP, 1f))
                    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Proporção personalizada")
                        .setView(bx)
                        .setPositiveButton("OK") { _, _ ->
                            val a = e1.text.toString().toIntOrNull() ?: 0
                            val b = e2.text.toString().toIntOrNull() ?: 0
                            if (a > 0 && b > 0) {
                                aw = a
                                ah = b
                                sel = 5
                                chips[5].text = "$a:$b"
                                restyle()
                            }
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            chips.add(c)
            chipRow.addView(c, lp(0, WRAP, 1f).also { it.setMargins(dp(3), 0, dp(3), 0) })
        }
        restyle()
        box.addView(chipRow, lp(MATCH, WRAP).also { it.topMargin = dp(16) })

        fun row(label: String, v: View) {
            val rw = LinearLayout(this)
            rw.gravity = Gravity.CENTER_VERTICAL
            rw.addView(tv(label, 17f, C.TEXT_DARK, true), lp(0, WRAP, 1f))
            rw.addView(v, lp(0, WRAP, 1.2f))
            box.addView(rw, lp(MATCH, WRAP).also { it.topMargin = dp(14) })
        }
        row("Resolução", dropdown(Opts.RES_NAMES, resIdx, true) { resIdx = it })
        row("Quadros por segundo", dropdown(Opts.FPS_NAMES, fpsIdx, true) { fpsIdx = it })
        row("Plano de fundo", dropdown(Opts.BG_NAMES, bgIdx, true) { bgIdx = it })

        val create = tv("CRIAR PROJETO", 16f, C.TEXT_DARK, true)
        create.setPadding(dp(8), dp(22), dp(8), dp(12))
        create.gravity = Gravity.END
        create.setOnClickListener {
            val dims = Project.dims(aw, ah, Opts.RES[resIdx])
            val p = Project()
            p.name = nameEt.text.toString().ifBlank { "Projeto" }
            p.width = dims[0]
            p.height = dims[1]
            p.aspect = "$aw:$ah"
            p.fps = Opts.FPS[fpsIdx]
            p.bg = Opts.BG_COLORS[bgIdx]
            Store.save(this, p)
            d.dismiss()
            startActivity(Intent(this, EditorActivity::class.java).putExtra("id", p.id))
        }
        box.addView(create, lp(MATCH, WRAP))

        val close = tv("✕", 22f, C.TEXT_DARK)
        close.gravity = Gravity.CENTER
        close.setPadding(0, dp(8), 0, dp(12))
        close.setOnClickListener { d.dismiss() }
        box.addView(close, lp(MATCH, WRAP))

        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(MATCH, WRAP)
        d.window?.setGravity(Gravity.BOTTOM)
        d.show()
    }
}
