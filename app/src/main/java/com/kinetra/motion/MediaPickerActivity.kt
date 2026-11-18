package com.kinetra.motion

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class MediaPickerActivity : Activity() {
    class Item(
        val uri: Uri,
        val kind: Int, // 0 imagem, 1 vídeo, 2 áudio
        val name: String,
        val mime: String,
        val durMs: Long,
        val date: Long
    )

    private val all = ArrayList<Item>()
    private var shown: List<Item> = emptyList()
    private val selected = LinkedHashMap<String, Item>()
    private var tab = 0
    private val cache = LruCache<String, Bitmap>(160)
    private val pool = Executors.newFixedThreadPool(3)
    private lateinit var grid: GridView
    private lateinit var adapter: Adapter
    private lateinit var permBox: LinearLayout
    private lateinit var emptyTv: TextView
    private lateinit var addBtn: LinearLayout
    private lateinit var addTxt: TextView
    private lateinit var tabRow: LinearLayout
    private var colW = 100
    private var loaded = false
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.PANEL
        tab = intent.getIntExtra("tab", 0)
        colW = (resources.displayMetrics.widthPixels - dp(2) * 2) / 3

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.BG)

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(4), dp(8), dp(12), dp(8))
        top.addView(iconBtn(Ic.CLOSE) { finish() }, lp(dp(48), dp(48)))
        top.addView(tv("Importar mídia", 18f, C.TEXT, true), lp(0, WRAP, 1f))
        val files = chip("Arquivos", false, Ic.FOLDER)
        files.setOnClickListener { openSystemPicker() }
        top.addView(files, lp(WRAP, WRAP))
        root.addView(top, lp(MATCH, WRAP))

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        tabRow = LinearLayout(this)
        tabRow.setPadding(dp(12), 0, dp(12), dp(10))
        hs.addView(tabRow)
        root.addView(hs, lp(MATCH, WRAP))
        buildTabs()

        val frame = FrameLayout(this)
        grid = GridView(this)
        grid.numColumns = 3
        grid.setColumnWidth(colW)
        grid.horizontalSpacing = dp(2)
        grid.verticalSpacing = dp(2)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.selector = ColorDrawable(Color.TRANSPARENT)
        adapter = Adapter()
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> toggle(shown[pos]) }
        frame.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))

        emptyTv = tv("Nada por aqui ainda.", 15f, C.MUTED)
        emptyTv.gravity = Gravity.CENTER
        emptyTv.visibility = View.GONE
        frame.addView(emptyTv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        permBox = buildPermBox()
        permBox.visibility = View.GONE
        frame.addView(permBox, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(frame, lp(MATCH, 0, 1f))

        val bottom = FrameLayout(this)
        bottom.setPadding(dp(16), dp(10), dp(16), dp(14))
        bottom.setBackgroundColor(C.PANEL)
        addBtn = gradientButton("Selecione itens", Ic.CHECK)
        addTxt = addBtn.getChildAt(1) as TextView
        addBtn.alpha = 0.4f
        addBtn.setOnClickListener { finishWith(selected.values.map { it.uri }) }
        bottom.addView(addBtn, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(bottom, lp(MATCH, WRAP))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
    }

    private fun buildPermBox(): LinearLayout {
        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.gravity = Gravity.CENTER
        b.setPadding(dp(32), dp(32), dp(32), dp(32))
        b.setBackgroundColor(C.BG)
        val circle = FrameLayout(this)
        circle.background = rounded(C.RAISED, dpf(40f), C.STROKE, dp(1))
        circle.addView(
            IconView(this, Ic.IMAGE, C.ACCENT, 36),
            FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER)
        )
        b.addView(circle, lp(dp(80), dp(80)))
        val t = tv("Acesso à sua galeria", 20f, C.TEXT, true)
        t.setPadding(0, dp(20), 0, dp(8))
        b.addView(t)
        val d = tv(
            "Para importar fotos, vídeos, GIFs e sons direto pelo Kinetra, precisamos da sua permissão para ver a galeria.",
            14f, C.MUTED
        )
        d.gravity = Gravity.CENTER
        b.addView(d, lp(MATCH, WRAP))
        val allow = gradientButton("Permitir acesso", Ic.CHECK)
        allow.setOnClickListener { Access.request(this, 21) }
        b.addView(allow, lp(WRAP, WRAP).also { it.topMargin = dp(24) })
        val st = chip("Abrir configurações", false, Ic.GEAR)
        st.setOnClickListener {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.fromParts("package", packageName, null)
            startActivity(i)
        }
        b.addView(st, lp(WRAP, WRAP).also { it.topMargin = dp(14) })
        return b
    }

    private fun refreshAccess() {
        if (Access.hasAny(this)) {
            permBox.visibility = View.GONE
            if (!loaded) load()
        } else {
            permBox.visibility = View.VISIBLE
            grid.visibility = View.GONE
            emptyTv.visibility = View.GONE
            if (!Access.asked(this)) Access.request(this, 21)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAccess()
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        val names = listOf("Tudo", "Fotos", "GIFs", "Vídeos", "Áudios")
        val icons = listOf(Ic.LAYERS, Ic.IMAGE, Ic.FILM, Ic.VIDEO, Ic.AUDIO)
        for (i in names.indices) {
            val c = chip(names[i], i == tab, icons[i])
            c.setOnClickListener {
                tab = i
                buildTabs()
                applyFilter()
            }
            tabRow.addView(c, lp(WRAP, WRAP).also { it.rightMargin = dp(8) })
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        Thread {
            val list = ArrayList<Item>()
            query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 0, list)
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 1, list)
            query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 2, list)
            list.sortByDescending { it.date }
            runOnUiThread {
                all.clear()
                all.addAll(list)
                loaded = true
                loading = false
                applyFilter()
            }
        }.start()
    }

    private fun query(uri: Uri, kind: Int, out: MutableList<Item>) {
        val hasDur = kind != 0
        val proj = if (hasDur) {
            arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, "duration"
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED
            )
        }
        val b = Bundle()
        b.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
        b.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        b.putInt(ContentResolver.QUERY_ARG_LIMIT, 1000)
        try {
            contentResolver.query(uri, proj, b, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out.add(
                        Item(
                            ContentUris.withAppendedId(uri, id), kind,
                            c.getString(1) ?: "", c.getString(2) ?: "",
                            if (hasDur) c.getLong(4) else 0L, c.getLong(3)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun applyFilter() {
        shown = all.filter {
            when (tab) {
                0 -> it.kind != 2
                1 -> it.kind == 0 && it.mime != "image/gif"
                2 -> it.kind == 0 && it.mime == "image/gif"
                3 -> it.kind == 1
                else -> it.kind == 2
            }
        }
        grid.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        emptyTv.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toggle(item: Item) {
        val key = item.uri.toString()
        if (selected.remove(key) == null) selected[key] = item
        addTxt.text = if (selected.isEmpty()) "Selecione itens" else "Adicionar (${selected.size})"
        addBtn.alpha = if (selected.isEmpty()) 0.4f else 1f
        adapter.notifyDataSetChanged()
    }

    private fun finishWith(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val i = Intent()
        val cd = ClipData.newRawUri("media", uris[0])
        for (k in 1 until uris.size) cd.addItem(ClipData.Item(uris[k]))
        i.clipData = cd
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setResult(RESULT_OK, i)
        finish()
    }

    private fun openSystemPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*"))
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            val cd = data.clipData
            if (cd != null) {
                for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
            finishWith(uris)
        }
    }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }

    private inner class Cell(ctx: Context) : FrameLayout(ctx) {
        val img = ImageView(ctx)
        val audioBox = LinearLayout(ctx)
        val nameTv = tv("", 11f, C.MUTED)
        val badge = tv("", 10f, Color.WHITE, true)
        val check = tv("", 11f, C.BG, true)

        init {
            setBackgroundColor(C.RAISED)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            addView(img, LayoutParams(MATCH, MATCH))
            audioBox.orientation = LinearLayout.VERTICAL
            audioBox.gravity = Gravity.CENTER
            audioBox.setPadding(dp(6), dp(6), dp(6), dp(6))
            audioBox.addView(IconView(ctx, Ic.AUDIO, C.ACCENT, 28), lp(dp(40), dp(40)))
            nameTv.maxLines = 2
            nameTv.gravity = Gravity.CENTER
            nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
            audioBox.addView(nameTv, lp(MATCH, WRAP))
            addView(audioBox, LayoutParams(MATCH, MATCH))
            badge.setPadding(dp(5), dp(1), dp(5), dp(1))
            badge.background = rounded(0xAA000000.toInt(), dpf(6f))
            val bp = LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START)
            bp.setMargins(dp(4), 0, 0, dp(4))
            addView(badge, bp)
            check.gravity = Gravity.CENTER
            val cp = LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END)
            cp.setMargins(0, dp(5), dp(5), 0)
            addView(check, cp)
        }

        fun bind(item: Item) {
            val key = item.uri.toString()
            val order = selected.keys.indexOf(key)
            if (order >= 0) {
                check.text = (order + 1).toString()
                check.background = rounded(C.ACCENT, dpf(12f))
            } else {
                check.text = ""
                check.background = rounded(0x66000000, dpf(12f), 0xCCFFFFFF.toInt(), dp(2))
            }
            when (item.kind) {
                2 -> {
                    img.visibility = View.GONE
                    audioBox.visibility = View.VISIBLE
                    nameTv.text = item.name
                    badge.visibility = View.VISIBLE
                    badge.text = fmtDur(item.durMs)
                }
                else -> {
                    audioBox.visibility = View.GONE
                    img.visibility = View.VISIBLE
                    when {
                        item.kind == 1 -> {
                            badge.visibility = View.VISIBLE
                            badge.text = fmtDur(item.durMs)
                        }
                        item.mime == "image/gif" -> {
                            badge.visibility = View.VISIBLE
                            badge.text = "GIF"
                        }
                        else -> badge.visibility = View.GONE
                    }
                    img.tag = key
                    val bm = cache.get(key)
                    if (bm != null) {
                        img.setImageBitmap(bm)
                    } else {
                        img.setImageDrawable(null)
                        try {
                            pool.execute {
                                try {
                                    val b = contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
                                    cache.put(key, b)
                                    runOnUiThread { if (img.tag == key) img.setImageBitmap(b) }
                                } catch (e: Throwable) {
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? Cell) ?: Cell(this@MediaPickerActivity)
            cell.layoutParams = AbsListView.LayoutParams(colW, colW)
            cell.bind(shown[position])
            return cell
        }
    }
}
