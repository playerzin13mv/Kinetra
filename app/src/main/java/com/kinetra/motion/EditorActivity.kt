package com.kinetra.motion

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Movie
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class EditorActivity : Activity(), PreviewView.Listener, TimelineView.Listener {
    private lateinit var project: Project
    private lateinit var renderer: Renderer
    private lateinit var audio: AudioPreview
    private lateinit var preview: PreviewView
    private lateinit var timeline: TimelineView
    private lateinit var playBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var panel: LinearLayout
    private lateinit var slider: SeekBar
    private lateinit var valueText: TextView
    private lateinit var keyBtn: TextView
    private lateinit var easeBtn: TextView
    private lateinit var propRow: LinearLayout
    private lateinit var actionRow: LinearLayout

    private var selId: String? = null
    private var t = 0L
    private var playing = false
    private var activeProp = Props.X
    private var sliderDragging = false
    private val handler = Handler(Looper.getMainLooper())
    private val undo = ArrayList<String>()
    private val redo = ArrayList<String>()
    private var playStartWall = 0L
    private var playStartT = 0L

    private val endT: Long get() = max(0L, project.totalDuration() - 1)

    private val saveRun = Runnable { Store.save(this, project) }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            val nt = playStartT + (SystemClock.uptimeMillis() - playStartWall)
            if (nt >= endT) {
                setT(endT)
                stopPlay()
                return
            }
            setT(nt)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")
        val p = if (id != null) Store.load(this, id) else null
        if (p == null) {
            finish()
            return
        }
        project = p
        renderer = Renderer(this, project, false)
        audio = AudioPreview(project, this)
        window.statusBarColor = C.DARK_BG
        window.navigationBarColor = C.DARK_BG
        if (Prefs.b(this, Prefs.AWAKE, true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        renderer.onAsyncFrame = { preview.postInvalidate() }
        setT(0)
    }

    override fun onPause() {
        super.onPause()
        if (!::project.isInitialized) return
        stopPlay()
        handler.removeCallbacks(saveRun)
        Store.save(this, project)
        val snap = Project.fromJson(project.toJson().toString())
        Thread { Store.makeThumb(this, snap) }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::renderer.isInitialized) renderer.release()
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.DARK_BG)

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(C.DARK_PANEL)
        top.addView(iconBtn("←", 24f) { finish() }, lp(dp(52), dp(52)))
        titleView = tv(project.name, 17f, Color.WHITE, true)
        titleView.setSingleLine()
        titleView.setOnClickListener {
            askText("Nome do projeto", project.name, false) { n ->
                if (n.isNotBlank()) {
                    project.name = n
                    titleView.text = n
                    modelChanged()
                }
            }
        }
        top.addView(titleView, lp(0, WRAP, 1f))
        top.addView(iconBtn("⚙\uFE0E", 22f) { showProjectSettings() }, lp(dp(48), dp(52)))
        top.addView(iconBtn("⤴", 22f) { showExport() }, lp(dp(48), dp(52)))
        root.addView(top, lp(MATCH, WRAP))

        preview = PreviewView(this)
        preview.renderer = renderer
        preview.project = project
        preview.listener = this
        preview.showGrid = Prefs.b(this, Prefs.GRID, false)
        root.addView(preview, lp(MATCH, 0, 1f))

        val transport = LinearLayout(this)
        transport.gravity = Gravity.CENTER_VERTICAL
        transport.setBackgroundColor(C.DARK_BG)
        playBtn = iconBtn("▶\uFE0E", 24f) { togglePlay() }
        transport.addView(iconBtn("↶", 22f) { doUndo() }, lp(0, dp(48), 1f))
        transport.addView(iconBtn("↷", 22f) { doRedo() }, lp(0, dp(48), 1f))
        transport.addView(iconBtn("⏮\uFE0E", 20f) { setT(0) }, lp(0, dp(48), 1f))
        transport.addView(playBtn, lp(0, dp(48), 1f))
        transport.addView(iconBtn("⏭\uFE0E", 20f) { setT(endT) }, lp(0, dp(48), 1f))
        root.addView(transport, lp(MATCH, WRAP))

        val tlBox = FrameLayout(this)
        timeline = TimelineView(this)
        timeline.project = project
        timeline.listener = this
        timeline.snapOn = Prefs.b(this, Prefs.SNAP, true)
        tlBox.addView(timeline, FrameLayout.LayoutParams(MATCH, MATCH))
        val fab = tv("+", 28f, C.ACCENT, true)
        fab.gravity = Gravity.CENTER
        fab.background = rounded(C.DARK_BG, dpf(26f), C.ACCENT, dp(2))
        fab.setOnClickListener { showAddMenu() }
        val fp = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END)
        fp.setMargins(0, 0, dp(14), dp(10))
        tlBox.addView(fab, fp)
        root.addView(tlBox, lp(MATCH, dp(170)))

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(C.DARK_PANEL)
        panel.setPadding(0, dp(6), 0, dp(6))
        panel.visibility = View.GONE

        val propScroll = HorizontalScrollView(this)
        propScroll.isHorizontalScrollBarEnabled = false
        propRow = LinearLayout(this)
        propRow.setPadding(dp(8), 0, dp(8), 0)
        propScroll.addView(propRow)
        panel.addView(propScroll, lp(MATCH, WRAP))

        val sRow = LinearLayout(this)
        sRow.gravity = Gravity.CENTER_VERTICAL
        sRow.addView(iconBtn("◀", 14f) { jumpKey(-1) }, lp(dp(34), dp(44)))
        keyBtn = iconBtn("◇", 24f) { toggleKey() }
        sRow.addView(keyBtn, lp(dp(40), dp(44)))
        sRow.addView(iconBtn("▶", 14f) { jumpKey(1) }, lp(dp(34), dp(44)))
        slider = SeekBar(this)
        slider.max = 1000
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                if (!fromUser) return
                val l = sel() ?: return
                val r = range(activeProp)
                val v = r[0] + (r[1] - r[0]) * prog / 1000f
                l.setValue(activeProp, v, localT(l))
                valueText.text = fmtVal(activeProp, v)
                modelChanged()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                pushUndo()
                sliderDragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sliderDragging = false
                refreshControls()
            }
        })
        sRow.addView(slider, lp(0, WRAP, 1f))
        valueText = tv("", 13f, Color.WHITE, true)
        valueText.gravity = Gravity.CENTER
        sRow.addView(valueText, lp(dp(64), WRAP))
        panel.addView(sRow, lp(MATCH, WRAP))

        val actScroll = HorizontalScrollView(this)
        actScroll.isHorizontalScrollBarEnabled = false
        actionRow = LinearLayout(this)
        actionRow.setPadding(dp(8), dp(4), dp(8), 0)
        actScroll.addView(actionRow)
        panel.addView(actScroll, lp(MATCH, WRAP))

        easeBtn = chip("Suave ▾", false)
        easeBtn.setOnClickListener { pickEase() }

        root.addView(panel, lp(MATCH, WRAP))
        setContentView(root)
    }

    private fun sel(): Layer? = project.layers.find { it.id == selId }

    private fun localT(l: Layer): Long = (t - l.start).coerceIn(0L, l.duration)

    private fun allowedProps(l: Layer): List<String> = when (l.type) {
        LT.AUDIO -> listOf(Props.VOL)
        LT.VIDEO -> Props.all
        else -> Props.all.filter { it != Props.VOL }
    }

    private fun range(p: String): FloatArray = when (p) {
        Props.X -> floatArrayOf(-project.width.toFloat(), project.width * 2f)
        Props.Y -> floatArrayOf(-project.height.toFloat(), project.height * 2f)
        Props.SCALE -> floatArrayOf(0f, 5f)
        Props.ROT -> floatArrayOf(-360f, 360f)
        Props.ALPHA -> floatArrayOf(0f, 1f)
        Props.BRIGHT -> floatArrayOf(-1f, 1f)
        Props.CONTRAST, Props.SAT, Props.VOL -> floatArrayOf(0f, 2f)
        else -> floatArrayOf(0f, 1f)
    }

    private fun fmtVal(p: String, v: Float): String = when (p) {
        Props.X, Props.Y -> "${v.roundToInt()} px"
        Props.ROT -> "${v.roundToInt()}°"
        Props.BRIGHT -> "${(v * 100).roundToInt()}"
        else -> "${(v * 100).roundToInt()}%"
    }

    private fun select(id: String?) {
        selId = id
        preview.selectedId = id
        timeline.selectedId = id
        preview.invalidate()
        rebuildPanel()
    }

    private fun rebuildPanel() {
        val l = sel()
        if (l == null) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        val allowed = allowedProps(l)
        if (activeProp !in allowed) activeProp = allowed[0]
        timeline.activeProp = activeProp

        propRow.removeAllViews()
        for (p in allowed) {
            val c = chip(Props.label(p), p == activeProp)
            c.setOnClickListener {
                activeProp = p
                timeline.activeProp = p
                rebuildPanel()
            }
            propRow.addView(c, lp(WRAP, WRAP).also { it.setMargins(dp(3), 0, dp(3), 0) })
        }

        actionRow.removeAllViews()
        actionRow.addView(easeBtn, lp(WRAP, WRAP).also { it.setMargins(dp(3), 0, dp(3), 0) })
        fun act(label: String, f: () -> Unit) {
            val c = chip(label, false)
            c.setOnClickListener { f() }
            actionRow.addView(c, lp(WRAP, WRAP).also { it.setMargins(dp(3), 0, dp(3), 0) })
        }
        act("Dividir") { splitLayer() }
        act("Duplicar") { duplicateLayer() }
        act("Excluir") { deleteLayer() }
        if (l.type == LT.TEXT) {
            act("Editar texto") {
                askText("Texto", l.text, true) { s ->
                    pushUndo()
                    l.text = s.ifEmpty { " " }
                    modelChanged()
                }
            }
        }
        if (l.type == LT.TEXT || l.type == LT.SHAPE) {
            act("Cor") {
                pickColor { c ->
                    pushUndo()
                    l.color = c
                    modelChanged()
                }
            }
        }
        if (l.type == LT.SHAPE) {
            act("Forma") {
                pickShape { s ->
                    pushUndo()
                    l.shape = s
                    modelChanged()
                }
            }
        }
        if (l.type != LT.AUDIO) act("Mesclagem") { pickBlend(l) }
        act(if (l.visible) "Ocultar" else "Mostrar") {
            pushUndo()
            l.visible = !l.visible
            modelChanged()
            rebuildPanel()
        }
        act("Subir") { moveLayer(1) }
        act("Descer") { moveLayer(-1) }
        refreshControls()
    }

    private fun refreshControls() {
        val l = sel() ?: return
        val lt = localT(l)
        val v = l.value(activeProp, lt)
        valueText.text = fmtVal(activeProp, v)
        if (!sliderDragging) {
            val r = range(activeProp)
            slider.progress = (((v - r[0]) / (r[1] - r[0])) * 1000f).roundToInt().coerceIn(0, 1000)
        }
        val has = l.tracks[activeProp]?.isNotEmpty() == true
        val ki = l.keyIndexAt(activeProp, lt)
        val at = ki >= 0
        keyBtn.text = if (at) "◆" else "◇"
        keyBtn.setTextColor(if (at) C.ACCENT else if (has) Color.WHITE else C.GRAY)
        easeBtn.visibility = if (at) View.VISIBLE else View.GONE
        if (at) easeBtn.text = Ease.names[l.tracks[activeProp]!![ki].ease] + " ▾"
    }

    // ---------------- Estado / histórico ----------------

    private fun setT(nt: Long) {
        t = nt.coerceIn(0L, max(endT, 0L))
        preview.t = t
        preview.invalidate()
        timeline.t = t
        if (playing) audio.update(t)
        refreshControls()
    }

    private fun pushUndo() {
        undo.add(project.toJson().toString())
        if (undo.size > 60) undo.removeAt(0)
        redo.clear()
    }

    private fun doUndo() {
        if (undo.isEmpty()) return
        stopPlay()
        redo.add(project.toJson().toString())
        restore(undo.removeAt(undo.size - 1))
    }

    private fun doRedo() {
        if (redo.isEmpty()) return
        stopPlay()
        undo.add(project.toJson().toString())
        restore(redo.removeAt(redo.size - 1))
    }

    private fun restore(json: String) {
        val p = Project.fromJson(json)
        project = p
        renderer.project = p
        preview.project = p
        timeline.project = p
        audio.project = p
        titleView.text = p.name
        if (sel() == null) selId = null
        select(selId)
        modelChanged()
        setT(t)
    }

    private fun modelChanged() {
        preview.invalidate()
        timeline.invalidate()
        refreshControls()
        if (Prefs.b(this, Prefs.AUTOSAVE, true)) {
            handler.removeCallbacks(saveRun)
            handler.postDelayed(saveRun, 700)
        }
    }

    // ---------------- Listeners do preview / timeline ----------------

    override fun onSelect(id: String?) = select(id)
    override fun onDragStart() = pushUndo()
    override fun onEditStart() = pushUndo()
    override fun onChanged() = modelChanged()
    override fun onSeek(t: Long) = setT(t)

    override fun onMove(l: Layer, dx: Float, dy: Float) {
        val lt = localT(l)
        l.setValue(Props.X, l.value(Props.X, lt) + dx, lt)
        l.setValue(Props.Y, l.value(Props.Y, lt) + dy, lt)
        modelChanged()
    }

    override fun onPinch(l: Layer, scaleFactor: Float, rotDeg: Float) {
        val lt = localT(l)
        l.setValue(Props.SCALE, (l.value(Props.SCALE, lt) * scaleFactor).coerceIn(0.01f, 20f), lt)
        l.setValue(Props.ROT, l.value(Props.ROT, lt) + rotDeg, lt)
        modelChanged()
    }

    // ---------------- Reprodução ----------------

    private fun togglePlay() {
        if (playing) stopPlay() else startPlay()
    }

    private fun startPlay() {
        if (t >= endT) setT(0)
        playing = true
        playBtn.text = "❚❚"
        playStartT = t
        playStartWall = SystemClock.uptimeMillis()
        audio.start(t)
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun stopPlay() {
        if (!::playBtn.isInitialized) return
        playing = false
        playBtn.text = "▶\uFE0E"
        audio.stop()
    }

    // ---------------- Quadros-chave ----------------

    private fun toggleKey() {
        val l = sel() ?: return
        pushUndo()
        val added = l.toggleKey(activeProp, localT(l))
        if (added && Prefs.b(this, Prefs.HAPTIC, true)) keyBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        modelChanged()
    }

    private fun jumpKey(dir: Int) {
        val l = sel() ?: return
        val ks = l.tracks[activeProp] ?: return
        val lt = t - l.start
        val target = if (dir < 0) ks.lastOrNull { it.t < lt - 1 } else ks.firstOrNull { it.t > lt + 1 }
        if (target != null) setT(l.start + target.t)
    }

    private fun pickEase() {
        val l = sel() ?: return
        val ki = l.keyIndexAt(activeProp, localT(l))
        if (ki < 0) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Curva de animação")
            .setItems(Ease.names.toTypedArray()) { _, i ->
                pushUndo()
                l.tracks[activeProp]!![ki].ease = i
                modelChanged()
            }.show()
    }

    // ---------------- Camadas ----------------

    private fun addLayer(l: Layer) {
        pushUndo()
        l.start = t
        l.base[Props.X] = project.width / 2f
        l.base[Props.Y] = project.height / 2f
        project.layers.add(l)
        select(l.id)
        modelChanged()
    }

    private fun defDurMs(): Long = Opts.IMG_DUR[Prefs.i(this, Prefs.IMG_DUR, 2).coerceIn(0, Opts.IMG_DUR.size - 1)] * 1000L

    private fun showAddMenu() {
        val items = arrayOf("Mídia (imagem, GIF, vídeo)", "Áudio", "Texto", "Forma")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Adicionar")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> pickMedia(arrayOf("image/*", "video/*"), 1)
                    1 -> pickMedia(arrayOf("audio/*"), 2)
                    2 -> askText("Texto", "Texto", true) { s ->
                        val l = Layer()
                        l.type = LT.TEXT
                        l.name = s.take(16)
                        l.text = s.ifEmpty { " " }
                        l.duration = defDurMs()
                        addLayer(l)
                    }
                    3 -> pickShape { s ->
                        val l = Layer()
                        l.type = LT.SHAPE
                        l.shape = s
                        l.name = "Forma"
                        l.w = minOf(project.width, project.height) * 0.4f
                        l.h = l.w
                        l.color = 0xFF00C2A8.toInt()
                        l.duration = defDurMs()
                        addLayer(l)
                    }
                }
            }.show()
    }

    private fun pickShape(onPick: (String) -> Unit) {
        val keys = listOf("rect", "round", "ellipse", "triangle", "star")
        val names = arrayOf("Retângulo", "Retângulo arredondado", "Círculo", "Triângulo", "Estrela")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Forma")
            .setItems(names) { _, i -> onPick(keys[i]) }
            .show()
    }

    private fun pickBlend(l: Layer) {
        val keys = listOf("normal", "multiply", "screen", "add", "overlay", "darken", "lighten")
        val names = arrayOf("Normal", "Multiplicar", "Tela", "Adicionar", "Sobrepor", "Escurecer", "Clarear")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Mesclagem")
            .setItems(names) { _, i ->
                pushUndo()
                l.blend = keys[i]
                modelChanged()
            }.show()
    }

    private fun pickMedia(mimes: Array<String>, req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) {
            for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) importUris(uris)
    }

    private fun importUris(uris: List<Uri>) {
        Toast.makeText(this, "Importando…", Toast.LENGTH_SHORT).show()
        Thread {
            val results = ArrayList<Layer>()
            for (u in uris) {
                val l = makeMediaLayer(u)
                if (l != null) results.add(l)
            }
            runOnUiThread {
                if (results.isEmpty()) {
                    Toast.makeText(this, "Não foi possível importar", Toast.LENGTH_LONG).show()
                } else {
                    for (l in results) addLayer(l)
                }
            }
        }.start()
    }

    private fun mediaDuration(path: String): Long = try {
        val m = MediaMetadataRetriever()
        m.setDataSource(path)
        val d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 3000L
        m.release()
        d
    } catch (e: Exception) {
        3000L
    }

    private fun makeMediaLayer(u: Uri): Layer? {
        val mime = Store.mime(this, u)
        val imp = Store.importMedia(this, project.id, u) ?: return null
        val l = Layer()
        l.file = imp.first
        l.name = imp.second.substringBeforeLast('.').take(24)
        val path = File(Store.mediaDir(this, project.id), l.file).absolutePath
        val ext = imp.second.substringAfterLast('.', "").lowercase()
        when {
            mime == "image/gif" || ext == "gif" -> {
                l.type = LT.GIF
                val d = try {
                    Movie.decodeFile(path)?.duration() ?: 0
                } catch (e: Exception) {
                    0
                }
                l.duration = if (d > 0) d.toLong() else 3000L
            }
            mime.startsWith("video/") -> {
                l.type = LT.VIDEO
                val d = mediaDuration(path)
                l.duration = d
                l.srcDuration = d
            }
            mime.startsWith("audio/") -> {
                l.type = LT.AUDIO
                val d = mediaDuration(path)
                l.duration = d
                l.srcDuration = d
            }
            mime.startsWith("image/") -> {
                l.type = LT.IMAGE
                l.duration = defDurMs()
            }
            else -> return null
        }
        return l
    }

    private fun splitLayer() {
        val l = sel() ?: return
        if (t <= l.start + 100 || t >= l.end() - 100) {
            Toast.makeText(this, "Posicione o cursor dentro da camada", Toast.LENGTH_SHORT).show()
            return
        }
        pushUndo()
        val cut = t - l.start
        val b = l.copy()
        b.start = t
        b.duration = l.duration - cut
        if (l.type == LT.VIDEO || l.type == LT.AUDIO) b.trimIn = l.trimIn + cut
        for (ks in b.tracks.values) for (k in ks) k.t -= cut
        l.duration = cut
        val idx = project.layers.indexOf(l)
        project.layers.add(idx + 1, b)
        select(b.id)
        modelChanged()
    }

    private fun duplicateLayer() {
        val l = sel() ?: return
        pushUndo()
        val c = l.copy()
        c.name = l.name + " cópia"
        if (l.type != LT.AUDIO) {
            c.base[Props.X] = l.value(Props.X, localT(l)) + 40f
            c.base[Props.Y] = l.value(Props.Y, localT(l)) + 40f
            c.tracks.remove(Props.X)
            c.tracks.remove(Props.Y)
            for ((k, ks) in l.tracks) {
                if (k == Props.X || k == Props.Y) {
                    c.tracks[k] = ks.map { Keyframe(it.t, it.v + 40f, it.ease) }.toMutableList()
                }
            }
        }
        project.layers.add(project.layers.indexOf(l) + 1, c)
        select(c.id)
        modelChanged()
    }

    private fun deleteLayer() {
        val l = sel() ?: return
        pushUndo()
        project.layers.remove(l)
        select(null)
        modelChanged()
    }

    private fun moveLayer(dir: Int) {
        val l = sel() ?: return
        val i = project.layers.indexOf(l)
        val j = i + dir
        if (j < 0 || j >= project.layers.size) return
        pushUndo()
        project.layers.removeAt(i)
        project.layers.add(j, l)
        modelChanged()
    }

    // ---------------- Configurações do projeto ----------------

    private fun showProjectSettings() {
        var bgIdx = Opts.BG_COLORS.indexOf(project.bg).let { if (it < 0) 0 else it }
        var fpsIdx = Opts.FPS.indexOf(project.fps).let { if (it < 0) 2 else it }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(tv("Plano de fundo", 13f, C.GRAY), lp(MATCH, WRAP).also { it.topMargin = dp(8) })
        box.addView(dropdown(Opts.BG_NAMES, bgIdx, false) { bgIdx = it })
        box.addView(tv("Quadros por segundo", 13f, C.GRAY), lp(MATCH, WRAP).also { it.topMargin = dp(12) })
        box.addView(dropdown(Opts.FPS_NAMES, fpsIdx, false) { fpsIdx = it })
        box.addView(
            tv("Resolução: ${project.width} × ${project.height} (definida na criação)", 12f, C.GRAY),
            lp(MATCH, WRAP).also { it.topMargin = dp(12) }
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Configurações do projeto")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                pushUndo()
                project.bg = Opts.BG_COLORS[bgIdx]
                project.fps = Opts.FPS[fpsIdx]
                modelChanged()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Exportar ----------------

    private fun showExport() {
        stopPlay()
        var fmtIdx = Prefs.i(this, Prefs.EXP_FMT, 0).coerceIn(0, 2)
        var resIdx = Prefs.i(this, Prefs.EXP_RES, 2).coerceIn(0, Opts.RES.size - 1)
        var fpsIdx = Prefs.i(this, Prefs.EXP_FPS, 1).coerceIn(0, Opts.EXP_FPS.size - 1)
        var qIdx = Prefs.i(this, Prefs.EXP_Q, 1).coerceIn(0, Opts.QUALITY_BPP.size - 1)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        fun field(label: String, v: View) {
            box.addView(tv(label, 13f, C.GRAY), lp(MATCH, WRAP).also { it.topMargin = dp(10) })
            box.addView(v)
        }
        field("Formato", dropdown(Opts.EXP_FMT, fmtIdx, false) { fmtIdx = it })
        field("Resolução", dropdown(Opts.RES_NAMES, resIdx, false) { resIdx = it })
        field("Quadros por segundo", dropdown(Opts.EXP_FPS_NAMES, fpsIdx, false) { fpsIdx = it })
        field("Qualidade", dropdown(Opts.QUALITY_NAMES, qIdx, false) { qIdx = it })
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Exportar")
            .setView(box)
            .setPositiveButton("Exportar") { _, _ ->
                val opt = ExportOptions(Opts.RES[resIdx], Opts.EXP_FPS[fpsIdx], Opts.QUALITY_BPP[qIdx], fmtIdx == 1)
                runExport(fmtIdx == 2, opt)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun runExport(still: Boolean, opt: ExportOptions) {
        val snap = Project.fromJson(project.toJson().toString())
        val cancel = AtomicBoolean(false)
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(22), dp(24), dp(16))
        box.background = rounded(C.DARK_PANEL, dpf(20f))
        box.addView(tv(if (still) "Exportando imagem…" else "Exportando vídeo…", 17f, Color.WHITE, true))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        box.addView(bar, lp(MATCH, WRAP).also { it.topMargin = dp(16) })
        val pct = tv("0%", 14f, C.GRAY)
        box.addView(pct, lp(MATCH, WRAP).also { it.topMargin = dp(8) })
        val cancelBtn = tv("CANCELAR", 14f, C.ACCENT, true)
        cancelBtn.gravity = Gravity.END
        cancelBtn.setPadding(0, dp(16), 0, 0)
        cancelBtn.setOnClickListener { cancel.set(true) }
        box.addView(cancelBtn, lp(MATCH, WRAP))
        dlg.setContentView(box)
        dlg.setCancelable(false)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.85f).toInt(), WRAP)
        dlg.show()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val at = t
        Thread {
            var uri: Uri? = null
            var err: String? = null
            var lastPct = -1
            try {
                uri = if (still) {
                    Exporter.exportStill(this, snap, at, opt.shortSide)
                } else {
                    Exporter.exportVideo(this, snap, opt, { f ->
                        val p = (f * 100).toInt()
                        if (p != lastPct) {
                            lastPct = p
                            runOnUiThread {
                                bar.progress = p
                                pct.text = "$p%"
                            }
                        }
                    }, cancel)
                }
            } catch (e: Throwable) {
                err = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                dlg.dismiss()
                if (!Prefs.b(this, Prefs.AWAKE, true)) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                when {
                    err != null -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Erro ao exportar")
                        .setMessage(err)
                        .setPositiveButton("OK", null)
                        .show()
                    uri == null -> Toast.makeText(this, "Exportação cancelada", Toast.LENGTH_SHORT).show()
                    else -> showExported(uri, still)
                }
            }
        }.start()
    }

    private fun showExported(uri: Uri, still: Boolean) {
        val mime = if (still) "image/png" else "video/mp4"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Exportado!")
            .setMessage(if (still) "Salvo em Imagens/Kinetra" else "Salvo em Filmes/Kinetra")
            .setPositiveButton("Abrir") { _, _ ->
                val i = Intent(Intent.ACTION_VIEW)
                i.setDataAndType(uri, mime)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(i)
                } catch (e: Exception) {
                    Toast.makeText(this, "Nenhum app para abrir", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Compartilhar") { _, _ ->
                val i = Intent(Intent.ACTION_SEND)
                i.type = mime
                i.putExtra(Intent.EXTRA_STREAM, uri)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, "Compartilhar"))
            }
            .setNegativeButton("Fechar", null)
            .show()
    }
}
