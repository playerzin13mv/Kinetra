package com.kinetra.motion

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.LIGHT_BG
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.LIGHT_BG)

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(8), dp(12), dp(16), dp(12))
        val back = tv("←", 26f, C.TEXT_DARK, true)
        back.setPadding(dp(12), 0, dp(16), 0)
        back.setOnClickListener { finish() }
        top.addView(back)
        top.addView(tv("Configurações", 22f, C.TEXT_DARK, true))
        root.addView(top, lp(MATCH, WRAP))

        val sc = ScrollView(this)
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), 0, dp(16), dp(40))
        sc.addView(box)
        root.addView(sc, lp(MATCH, 0, 1f))

        section("Novo projeto")
        dropRow("Resolução padrão", Prefs.DEF_RES, Opts.RES_NAMES, 2)
        dropRow("FPS padrão", Prefs.DEF_FPS, Opts.FPS_NAMES, 2)
        dropRow("Fundo padrão", Prefs.DEF_BG, Opts.BG_NAMES, 0)
        dropRow("Duração de imagens, GIFs e textos", Prefs.IMG_DUR, Opts.IMG_DUR_NAMES, 2)

        section("Editor")
        switchRow("Mostrar grade no preview", Prefs.GRID, false)
        switchRow("Ímã na timeline (encaixe nas bordas)", Prefs.SNAP, true)
        switchRow("Vibrar ao criar quadro-chave", Prefs.HAPTIC, true)
        switchRow("Manter tela ligada", Prefs.AWAKE, true)
        switchRow("Salvamento automático", Prefs.AUTOSAVE, true)

        section("Exportação")
        dropRow("Formato padrão", Prefs.EXP_FMT, Opts.EXP_FMT, 0)
        dropRow("Resolução padrão", Prefs.EXP_RES, Opts.RES_NAMES, 2)
        dropRow("FPS padrão", Prefs.EXP_FPS, Opts.EXP_FPS_NAMES, 1)
        dropRow("Qualidade padrão", Prefs.EXP_Q, Opts.QUALITY_NAMES, 1)

        section("Armazenamento")
        actionRow("Limpar cache") {
            cacheDir.deleteRecursively()
            Toast.makeText(this, "Cache limpo", Toast.LENGTH_SHORT).show()
        }
        actionRow("Excluir todos os projetos") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Excluir TODOS os projetos?")
                .setMessage("Não dá para desfazer.")
                .setPositiveButton("Excluir") { _, _ ->
                    Store.root(this).deleteRecursively()
                    Toast.makeText(this, "Projetos excluídos", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        section("Sobre")
        val about = tv("Kinetra 1.0\nEditor de motion e vídeo com quadros-chave.", 14f, C.GRAY)
        about.setPadding(dp(4), dp(6), 0, 0)
        box.addView(about)

        setContentView(root)
    }

    private fun section(t: String) {
        val v = tv(t.uppercase(), 12f, C.GRAY, true)
        v.letterSpacing = 0.08f
        v.setPadding(dp(4), dp(22), 0, dp(8))
        box.addView(v)
    }

    private fun card(): LinearLayout {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(16), dp(12), dp(12), dp(12))
        r.background = rounded(Color.WHITE, dpf(14f))
        box.addView(r, lp(MATCH, WRAP).also { it.bottomMargin = dp(8) })
        return r
    }

    private fun dropRow(label: String, key: String, options: List<String>, def: Int) {
        val r = card()
        r.addView(tv(label, 15f, C.TEXT_DARK, true), lp(0, WRAP, 1f))
        val cur = Prefs.i(this, key, def).coerceIn(0, options.size - 1)
        val dd = dropdown(options, cur, true) { Prefs.setI(this, key, it) }
        dd.background = rounded(C.LIGHT_BG, dpf(10f))
        r.addView(dd, lp(WRAP, WRAP))
    }

    private fun switchRow(label: String, key: String, def: Boolean) {
        val r = card()
        r.addView(tv(label, 15f, C.TEXT_DARK, true), lp(0, WRAP, 1f))
        val s = Switch(this)
        s.isChecked = Prefs.b(this, key, def)
        s.setOnCheckedChangeListener { _, v -> Prefs.setB(this, key, v) }
        r.addView(s, lp(WRAP, WRAP))
    }

    private fun actionRow(label: String, f: () -> Unit) {
        val r = card()
        r.addView(tv(label, 15f, C.TEXT_DARK, true), lp(0, WRAP, 1f))
        r.setOnClickListener { f() }
    }
}
