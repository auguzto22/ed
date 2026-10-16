package com.termex.replay15.editor.media

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termex.replay15.editor.ui.EditorStyle
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Seletor de fotos e vídeos do Recly: uma folha que sobe de baixo, no visual do canvas
 * (preto, vermelho de ação e branco), no lugar do seletor de documentos do sistema.
 *
 * Devolve o mesmo formato do ACTION_OPEN_DOCUMENT (data + clipData), com
 * [EXTRA_FROM_GALLERY] = true. Itens da galeria são URIs do MediaStore, legíveis enquanto
 * a permissão de mídia estiver concedida; por isso não passam por takePersistableUriPermission.
 * O atalho "Arquivos" abre o seletor do sistema e repassa o resultado dele sem mudanças.
 */
class GalleryPickerActivity : Activity() {
    /** Tema claro/escuro escolhido em Config. > Aparência (não o do sistema): ver BlitzAppearance. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.termex.replay15.ui.BlitzAppearance.wrap(newBase))
    }


    companion object {
        const val EXTRA_MODE = "gallery_mode"
        const val EXTRA_MULTIPLE = "gallery_multiple"
        const val EXTRA_MAX = "gallery_max"
        const val EXTRA_TITLE = "gallery_title"
        const val EXTRA_FROM_GALLERY = "from_recly_gallery"

        const val MODE_ANY = 0
        const val MODE_VIDEOS = 1
        const val MODE_IMAGES = 2

        private const val REQ_SYSTEM = 4101
        private const val REQ_PERMISSIONS = 4102
        private const val MAX_ITEMS = 6000
        private const val VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

        fun intent(context: Context, mode: Int, multiple: Boolean = false, title: String? = null, max: Int = 30): Intent =
            Intent(context, GalleryPickerActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_MULTIPLE, multiple)
                .putExtra(EXTRA_MAX, max)
                .apply { if (title != null) putExtra(EXTRA_TITLE, title) }

        /** True when [data] came from the gallery grid (MediaStore URIs, no persistable grant). */
        fun isGalleryResult(data: Intent?): Boolean = data?.getBooleanExtra(EXTRA_FROM_GALLERY, false) == true
    }

    // ── Cores (paleta do canvas) ────────────────────────────────────────────────
    // Folha e quadradinhos no tema escolhido (Config. > Aparência).
    private val sheetColor = if (EditorStyle.IS_LIGHT) 0xFFFFFFFF.toInt() else 0xFF0B0B0C.toInt()
    private val tileColor = if (EditorStyle.IS_LIGHT) 0xFFEDEDF0.toInt() else 0xFF1C1C1E.toInt()
    private val red = EditorStyle.ACTION
    private val fg = EditorStyle.TEXT
    private val muted = EditorStyle.MUTED

    private data class Item(val id: Long, val uri: Uri, val isVideo: Boolean, val durationMs: Long, val bucket: String)
    private data class Album(val name: String?, val label: String, val count: Int)

    private val mode by lazy { intent.getIntExtra(EXTRA_MODE, MODE_ANY) }
    private val multiple by lazy { intent.getBooleanExtra(EXTRA_MULTIPLE, false) }
    private val maxSelection by lazy { if (multiple) intent.getIntExtra(EXTRA_MAX, 30).coerceAtLeast(1) else 1 }

    private var all: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var segment = MODE_ANY          // filtro Tudo / Vídeos / Fotos (só no modo "qualquer")
    private var album: String? = null       // null = todos os álbuns
    private var loading = true
    private val selected = LinkedHashMap<Long, Item>()
    private var permissionAsked = false
    private var closing = false

    private val io = Executors.newFixedThreadPool(4)
    private val thumbs = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val inFlight = HashSet<Long>()

    private lateinit var scrim: View
    private lateinit var sheet: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var segmentRow: LinearLayout
    private lateinit var albumRow: LinearLayout
    private lateinit var albumScroll: HorizontalScrollView
    private lateinit var limitedBanner: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var stateView: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var trayRow: LinearLayout
    private lateinit var trayScroll: HorizontalScrollView
    private lateinit var confirm: TextView
    private lateinit var preview: FrameLayout
    private val adapter = GridAdapter()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    // ── Ciclo de vida ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.navigationBarColor = sheetColor
        // Tema claro: ícones da barra de navegação escuros sobre a folha branca.
        if (EditorStyle.IS_LIGHT && android.os.Build.VERSION.SDK_INT >= 30) {
            val flag = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(flag, flag)
        }
        segment = if (mode == MODE_ANY) MODE_ANY else mode
        setContentView(buildRoot())
        animateIn()
        if (hasAccess()) loadMedia() else showPermissionState()
    }

    override fun onResume() {
        super.onResume()
        // Voltando das configurações do sistema com a permissão concedida.
        if (::grid.isInitialized && all.isEmpty() && !loading && hasAccess()) loadMedia()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        if (preview.visibility == View.VISIBLE) hidePreview() else close(null)
    }

    // ── Estrutura ───────────────────────────────────────────────────────────────

    private fun buildRoot(): View {
        val root = FrameLayout(this)
        scrim = View(this).apply {
            setBackgroundColor(if (EditorStyle.IS_LIGHT) 0x80000000.toInt() else 0xB8000000.toInt())
            alpha = 0f
            setOnClickListener { close(null) }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(sheetColor)
                val r = dpf(28f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
        }
        val sheetHeight = (resources.displayMetrics.heightPixels * .9f).toInt()
        root.addView(sheet, FrameLayout.LayoutParams(-1, sheetHeight, Gravity.BOTTOM))

        sheet.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(EditorStyle.HANDLE); cornerRadius = dpf(2f) }
        }, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })

        sheet.addView(buildHeader())
        if (mode == MODE_ANY) sheet.addView(buildSegments(), LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(16), dp(14), dp(16), 0) })
        albumScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), 0)
            albumRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            addView(albumRow)
        }
        sheet.addView(albumScroll, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(12) })
        limitedBanner = buildLimitedBanner().apply { visibility = View.GONE }
        sheet.addView(limitedBanner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(12), dp(16), 0) })

        val content = FrameLayout(this)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = this@GalleryPickerActivity.adapter
            clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(132))
            itemAnimator = null
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    val g = dp(2); outRect.set(g, g, g, g)
                }
            })
        }
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        stateView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(96))
            visibility = View.GONE
        }
        content.addView(stateView, FrameLayout.LayoutParams(-1, -1))
        bottomBar = buildBottomBar()
        content.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        sheet.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        preview = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(0xF2000000.toInt()); isClickable = true }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        // A barra de navegação do sistema não pode cobrir o botão de confirmar.
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            bottomBar.setPadding(dp(16), dp(28), dp(16), dp(16) + bottom)
            grid.setPadding(dp(12), dp(12), dp(12), dp(132) + bottom)
            stateView.setPadding(dp(32), dp(24), dp(32), dp(96) + bottom)
            insets
        }
        renderAlbums()
        updateBottomBar(animate = false)
        return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(14), 0)
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: when (mode) {
                MODE_VIDEOS -> "Escolher vídeo"
                MODE_IMAGES -> "Escolher foto"
                else -> "Escolher mídia"
            }
            textSize = 25f
            setTextColor(fg)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            letterSpacing = -.02f
        })
        subtitle = TextView(context).apply {
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        }
        texts.addView(subtitle)
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(circleButton(PickerIcon.CLOSE, "Fechar") { close(null) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        updateSubtitle()
    }

    private fun circleButton(icon: Int, description: String, click: () -> Unit) = FrameLayout(this).apply {
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = RippleDrawable(android.content.res.ColorStateList.valueOf(EditorStyle.RIPPLE),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(EditorStyle.CARD); setStroke(dp(1), EditorStyle.BORDER) }, null)
        addView(ImageView(context).apply { setImageDrawable(PickerIcon(icon, fg)) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        setOnClickListener { click() }
    }

    private fun buildSegments(): View {
        segmentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { setColor(EditorStyle.CARD); setStroke(dp(1), EditorStyle.BORDER); cornerRadius = dpf(14f) }
        }
        listOf(MODE_ANY to "Tudo", MODE_VIDEOS to "Vídeos", MODE_IMAGES to "Fotos").forEach { (value, label) ->
            segmentRow.addView(TextView(this).apply {
                text = label
                tag = value
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (segment == value) return@setOnClickListener
                    segment = value
                    album = null
                    paintSegments()
                    applyFilter()
                    renderAlbums()
                }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        paintSegments()
        return segmentRow
    }

    private fun paintSegments() {
        if (!::segmentRow.isInitialized) return
        for (i in 0 until segmentRow.childCount) {
            val tab = segmentRow.getChildAt(i) as TextView
            val active = tab.tag == segment
            // Ativo em branco com texto escuro, como o bloco "Auto Edit" do canvas.
            tab.background = if (active) GradientDrawable().apply { setColor(EditorStyle.ACCENT); cornerRadius = dpf(10f) } else null
            tab.setTextColor(if (active) EditorStyle.ON_ACCENT else muted)
            if (active) { tab.scaleX = .94f; tab.scaleY = .94f; tab.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }
        }
    }

    private fun buildLimitedBanner() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(8), dp(10))
        background = GradientDrawable().apply { setColor(EditorStyle.CARD); setStroke(dp(1), EditorStyle.BORDER); cornerRadius = dpf(14f) }
        addView(TextView(context).apply {
            text = "Você liberou só algumas fotos e vídeos."
            textSize = 13f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "Gerenciar"
            textSize = 13f
            setTextColor(EditorStyle.ACTION_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { requestAccess() }
        })
    }

    private fun buildBottomBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Degradê do fundo da folha para transparente, para a grade "sumir" atrás do botão.
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(sheetColor, sheetColor, sheetColor and 0x00FFFFFF))
        setPadding(dp(16), dp(28), dp(16), dp(16))
        trayScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            trayRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            addView(trayRow)
            visibility = if (multiple) View.VISIBLE else View.GONE
        }
        addView(trayScroll, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        confirm = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(EditorStyle.ON_ACTION)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply { setColor(red); cornerRadius = dpf(16f) }, null)
            setOnClickListener { deliver() }
        }
        addView(confirm, LinearLayout.LayoutParams(-1, dp(56)))
    }

    // ── Animações de entrada e saída ────────────────────────────────────────────

    private fun animateIn() {
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.animate().translationY(0f).setDuration(340).setInterpolator(DecelerateInterpolator(2.4f)).start()
            scrim.animate().alpha(1f).setDuration(260).start()
        }
    }

    private fun close(result: Intent?) {
        if (closing) return
        closing = true
        if (result != null) setResult(RESULT_OK, result) else setResult(RESULT_CANCELED)
        scrim.animate().alpha(0f).setDuration(200).start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(240)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }.start()
    }

    // ── Permissões ──────────────────────────────────────────────────────────────

    private fun wanted(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> buildList {
            if (mode != MODE_VIDEOS) add(Manifest.permission.READ_MEDIA_IMAGES)
            if (mode != MODE_IMAGES) add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= 34) add(VISUAL_USER_SELECTED)
        }.toTypedArray()
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasFullAccess(): Boolean = wanted().filter { it != VISUAL_USER_SELECTED }.all(::granted)

    /** Android 14+: o usuário pode liberar só algumas fotos; nesse caso mostramos o aviso "Gerenciar". */
    private fun hasPartialAccess(): Boolean = Build.VERSION.SDK_INT >= 34 && granted(VISUAL_USER_SELECTED)

    private fun hasAccess() = hasFullAccess() || hasPartialAccess()

    private fun requestAccess() {
        val permanentlyDenied = permissionAsked && wanted().none { shouldShowRequestPermissionRationale(it) } && !hasAccess()
        if (permanentlyDenied) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
            return
        }
        permissionAsked = true
        requestPermissions(wanted(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (hasAccess()) loadMedia() else showPermissionState()
    }

    // ── Carregamento ────────────────────────────────────────────────────────────

    private fun loadMedia() {
        loading = true
        stateView.visibility = View.GONE
        grid.visibility = View.VISIBLE
        albumScroll.visibility = View.VISIBLE
        limitedBanner.visibility = if (!hasFullAccess() && hasPartialAccess()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        io.execute {
            val items = runCatching { queryMedia() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                all = items
                loading = false
                applyFilter()
                renderAlbums()
            }
        }
    }

    private fun queryMedia(): List<Item> {
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val selection = when (mode) {
            MODE_VIDEOS -> "$type = $video"
            MODE_IMAGES -> "$type = $image"
            else -> "($type = $image OR $type = $video)"
        } + " AND ${MediaStore.MediaColumns.SIZE} > 0"
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, type, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        val out = ArrayList<Item>()
        contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val typeCol = c.getColumnIndexOrThrow(type)
            val durCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext() && out.size < MAX_ITEMS) {
                val id = c.getLong(idCol)
                val isVideo = c.getInt(typeCol) == video
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += Item(id, ContentUris.withAppendedId(base, id), isVideo, if (isVideo) c.getLong(durCol) else 0L,
                    c.getString(bucketCol) ?: "Outros")
            }
        }
        return out
    }

    private fun byType(): List<Item> = when (segment) {
        MODE_VIDEOS -> all.filter { it.isVideo }
        MODE_IMAGES -> all.filter { !it.isVideo }
        else -> all
    }

    private fun applyFilter() {
        shown = byType().let { list -> if (album == null) list else list.filter { it.bucket == album } }
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
        if (!loading && shown.isEmpty()) showEmptyState() else if (!loading) stateView.visibility = View.GONE
        updateSubtitle()
    }

    private fun updateSubtitle() {
        if (!::subtitle.isInitialized) return
        subtitle.text = when {
            loading -> "Carregando sua galeria…"
            multiple -> "Toque para escolher · até $maxSelection itens"
            else -> "Toque para escolher · segure para ver maior"
        }
    }

    // ── Álbuns ──────────────────────────────────────────────────────────────────

    private fun renderAlbums() {
        if (!::albumRow.isInitialized) return
        albumRow.removeAllViews()
        val source = byType()
        val albums = mutableListOf(Album(null, "Todos", source.size))
        source.groupingBy { it.bucket }.eachCount().entries
            .sortedByDescending { it.value }
            .forEach { (name, count) -> albums += Album(name, name, count) }
        albums.forEach { a -> albumRow.addView(albumChip(a), LinearLayout.LayoutParams(-2, -1).apply { marginEnd = dp(8) }) }
        // Atalho para pastas que não aparecem na galeria (Drive, downloads, cartão SD).
        albumRow.addView(TextView(this).apply {
            text = "Arquivos"
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(fg)
            setCompoundDrawablesRelative(PickerIcon(PickerIcon.FOLDER, fg).apply { setBounds(0, 0, dp(16), dp(16)) }, null, null, null)
            compoundDrawablePadding = dp(6)
            setPadding(dp(14), 0, dp(16), 0)
            background = chipBackground(false)
            setOnClickListener { openSystemPicker() }
        }, LinearLayout.LayoutParams(-2, -1))
    }

    private fun chipBackground(active: Boolean) = RippleDrawable(android.content.res.ColorStateList.valueOf(EditorStyle.RIPPLE),
        GradientDrawable().apply {
            setColor(if (active) red else EditorStyle.CARD)
            if (!active) setStroke(dp(1), EditorStyle.BORDER)
            cornerRadius = dpf(19f)
        }, null)

    private fun albumChip(a: Album) = TextView(this).apply {
        val active = a.name == album
        val count = formatCount(a.count)
        text = SpannableStringBuilder(a.label).apply {
            append("  ")
            val start = length
            append(count)
            setSpan(ForegroundColorSpan(if (active) 0xCCFFFFFF.toInt() else muted), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textSize = 13.5f
        gravity = Gravity.CENTER
        maxLines = 1
        setTextColor(if (active) EditorStyle.ON_ACTION else fg)
        typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setPadding(dp(16), 0, dp(16), 0)
        background = chipBackground(active)
        setOnClickListener {
            if (album == a.name) return@setOnClickListener
            album = a.name
            applyFilter()
            renderAlbums()
        }
    }

    private fun formatCount(n: Int) = if (n >= 1000) String.format(Locale("pt", "BR"), "%.1fk", n / 1000f) else n.toString()

    // ── Estados vazios ──────────────────────────────────────────────────────────

    private fun showState(icon: Int, title: String, body: String, primary: Pair<String, () -> Unit>?, secondary: Pair<String, () -> Unit>?) {
        stateView.removeAllViews()
        stateView.visibility = View.VISIBLE
        stateView.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(red); cornerRadius = dpf(20f) }
            addView(ImageView(context).apply { setImageDrawable(PickerIcon(icon, Color.WHITE)) }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(68), dp(68)))
        stateView.addView(TextView(this).apply {
            this.text = title
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(fg)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setPadding(0, dp(18), 0, 0)
        })
        stateView.addView(TextView(this).apply {
            this.text = body
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setLineSpacing(dpf(3f), 1f)
            setPadding(0, dp(8), 0, 0)
        })
        primary?.let { (label, action) ->
            stateView.addView(TextView(this).apply {
                this.text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(EditorStyle.ON_ACTION)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                    GradientDrawable().apply { setColor(red); cornerRadius = dpf(16f) }, null)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(24) })
        }
        secondary?.let { (label, action) ->
            stateView.addView(TextView(this).apply {
                this.text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(fg)
                background = RippleDrawable(android.content.res.ColorStateList.valueOf(EditorStyle.RIPPLE),
                    GradientDrawable().apply { setColor(EditorStyle.CARD); setStroke(dp(1), EditorStyle.BORDER); cornerRadius = dpf(16f) }, null)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        }
        stateView.alpha = 0f
        stateView.translationY = dpf(12f)
        stateView.animate().alpha(1f).translationY(0f).setDuration(260).start()
    }

    private fun showPermissionState() {
        loading = false
        grid.visibility = View.GONE
        albumScroll.visibility = View.GONE
        limitedBanner.visibility = View.GONE
        subtitle.text = "Precisamos ver sua galeria"
        showState(PickerIcon.IMAGE, "Libere sua galeria",
            "Suas fotos e vídeos aparecem aqui para você escolher rápido. Nada sai do seu aparelho.",
            "Permitir acesso" to { requestAccess() },
            "Escolher pelos arquivos" to { openSystemPicker() })
    }

    private fun showEmptyState() {
        val what = when (segment) { MODE_VIDEOS -> "vídeo"; MODE_IMAGES -> "foto"; else -> "foto ou vídeo" }
        showState(PickerIcon.IMAGE, "Nada por aqui",
            if (album != null) "Esse álbum não tem nenhum(a) $what." else "Não encontramos nenhum(a) $what na galeria.",
            null, "Procurar nos arquivos" to { openSystemPicker() })
    }

    // ── Seleção ─────────────────────────────────────────────────────────────────

    private fun toggle(item: Item) {
        if (selected.containsKey(item.id)) {
            selected.remove(item.id)
        } else {
            if (!multiple) selected.clear()
            if (selected.size >= maxSelection) {
                Toast.makeText(this, "Você pode escolher até $maxSelection itens", Toast.LENGTH_SHORT).show()
                return
            }
            selected[item.id] = item
        }
        // Números de ordem mudam em todos os selecionados: redesenha a grade visível.
        adapter.notifyItemRangeChanged(0, adapter.itemCount, PAYLOAD_SELECTION)
        updateBottomBar(animate = true)
        renderTray()
    }

    private fun updateBottomBar(animate: Boolean) {
        val count = selected.size
        val visible = count > 0
        confirm.text = when {
            count == 0 -> ""
            !multiple -> if (selected.values.first().isVideo) "Usar este vídeo" else "Usar esta foto"
            count == 1 -> "Adicionar 1 item"
            else -> "Adicionar $count itens"
        }
        val target = if (visible) 0f else dpf(160f)
        if (!animate) { bottomBar.translationY = target; bottomBar.alpha = if (visible) 1f else 0f; return }
        bottomBar.animate().translationY(target).alpha(if (visible) 1f else 0f).setDuration(260)
            .setInterpolator(if (visible) OvershootInterpolator(.8f) else DecelerateInterpolator()).start()
    }

    private fun renderTray() {
        if (!multiple) return
        trayRow.removeAllViews()
        selected.values.forEach { item ->
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(tileColor)
                outlineProvider = rounded(dpf(10f))
                clipToOutline = true
                contentDescription = "Remover da seleção"
                setOnClickListener { toggle(item) }
            }
            loadThumb(item, thumb, dp(96))
            trayRow.addView(thumb, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(6) })
        }
        trayScroll.post { trayScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun deliver() {
        val uris = selected.values.map { it.uri }
        if (uris.isEmpty()) return
        val data = Intent().apply {
            putExtra(EXTRA_FROM_GALLERY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            this.data = uris.first()
            if (uris.size > 1) {
                clipData = ClipData.newRawUri("media", uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            }
        }
        close(data)
    }

    // ── Seletor do sistema (atalho "Arquivos") ──────────────────────────────────

    private fun openSystemPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        when (mode) {
            MODE_VIDEOS -> intent.type = "video/*"
            MODE_IMAGES -> intent.type = "image/*"
            else -> { intent.type = "*/*"; intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*")) }
        }
        runCatching { startActivityForResult(intent, REQ_SYSTEM) }
            .onFailure { Toast.makeText(this, "Seletor de arquivos indisponível", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Repassa o resultado como veio: quem abriu o seletor faz a persistência de sempre.
        if (requestCode == REQ_SYSTEM && resultCode == RESULT_OK && data != null) close(data)
    }

    // ── Pré-visualização (segurar) ──────────────────────────────────────────────

    private fun showPreview(item: Item) {
        preview.removeAllViews()
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        preview.addView(image, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(16), dp(72), dp(16), dp(128)) })
        loadThumb(item, image, 1440, cache = false)
        preview.addView(TextView(this).apply {
            text = if (item.isVideo) "${item.bucket} · ${formatDuration(item.durationMs)}" else item.bucket
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, dp(72), Gravity.TOP).apply { topMargin = dp(8) })
        val chosen = selected.containsKey(item.id)
        preview.addView(TextView(this).apply {
            text = if (chosen) "Tirar da seleção" else if (multiple) "Selecionar" else if (item.isVideo) "Usar este vídeo" else "Usar esta foto"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(if (chosen) fg else EditorStyle.ON_ACTION)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(if (chosen) EditorStyle.CARD else red)
                if (chosen) setStroke(dp(1), EditorStyle.BORDER)
                cornerRadius = dpf(16f)
            }
            setOnClickListener {
                hidePreview()
                if (!multiple && !chosen) { selected.clear(); selected[item.id] = item; deliver() } else toggle(item)
            }
        }, FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(40)) })
        preview.setOnClickListener { hidePreview() }
        preview.alpha = 0f
        preview.visibility = View.VISIBLE
        image.scaleX = .92f; image.scaleY = .92f
        preview.animate().alpha(1f).setDuration(180).start()
        image.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun hidePreview() {
        preview.animate().alpha(0f).setDuration(160).withEndAction { preview.visibility = View.GONE; preview.removeAllViews() }.start()
    }

    // ── Miniaturas ──────────────────────────────────────────────────────────────

    private fun loadThumb(item: Item, target: ImageView, sizePx: Int, cache: Boolean = true) {
        target.tag = item.id
        if (cache) thumbs.get(item.id)?.let { target.setImageBitmap(it); target.alpha = 1f; return }
        target.setImageDrawable(null)
        if (cache && !inFlight.add(item.id)) {
            // Já está carregando; quando chegar, o próximo bind pega do cache.
            return
        }
        io.execute {
            val bitmap = runCatching { contentResolver.loadThumbnail(item.uri, Size(sizePx, sizePx), null) }.getOrNull()
            runOnUiThread {
                if (cache) inFlight.remove(item.id)
                if (bitmap == null || isDestroyed) return@runOnUiThread
                if (cache) thumbs.put(item.id, bitmap)
                if (target.tag == item.id) {
                    target.alpha = 0f
                    target.setImageBitmap(bitmap)
                    target.animate().alpha(1f).setDuration(140).start()
                } else if (cache) {
                    adapter.notifyItemChanged(shown.indexOfFirst { it.id == item.id }.takeIf { it >= 0 } ?: return@runOnUiThread)
                }
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    private fun rounded(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
    }

    // ── Grade ───────────────────────────────────────────────────────────────────

    private val PAYLOAD_SELECTION = Any()

    /** Quadrado: a largura da coluna define a altura. */
    private class SquareFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }

    private inner class Tile(val root: SquareFrame) : RecyclerView.ViewHolder(root) {
        val image = ImageView(root.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(tileColor)
            outlineProvider = rounded(dpf(12f))
            clipToOutline = true
        }
        val duration = TextView(root.context).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = dpf(6f) }
        }
        val badge = TextView(root.context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val ring = View(root.context).apply {
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(3), red); cornerRadius = dpf(14f) }
            visibility = View.GONE
        }

        init {
            root.addView(image, FrameLayout.LayoutParams(-1, -1))
            root.addView(ring, FrameLayout.LayoutParams(-1, -1))
            root.addView(duration, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(6), 0, 0, dp(6)) })
            root.addView(badge, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        }

        fun bindSelection(item: Item, animate: Boolean) {
            val order = selected.keys.indexOf(item.id)
            val isOn = order >= 0
            badge.text = if (isOn) (if (multiple) "${order + 1}" else "✓") else ""
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isOn) setColor(red) else { setColor(0x33000000); setStroke(dp(2), Color.WHITE) }
            }
            ring.visibility = if (isOn) View.VISIBLE else View.GONE
            // Foto "afunda" um pouco quando escolhida, como numa galeria de verdade.
            val scale = if (isOn) .88f else 1f
            if (animate && image.scaleX != scale) {
                image.animate().scaleX(scale).scaleY(scale).setDuration(200).setInterpolator(OvershootInterpolator(1.6f)).start()
                if (isOn) { badge.scaleX = .4f; badge.scaleY = .4f; badge.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start() }
            } else {
                image.animate().cancel(); image.scaleX = scale; image.scaleY = scale
            }
        }
    }

    private inner class GridAdapter : RecyclerView.Adapter<Tile>() {
        override fun getItemCount() = if (loading) 24 else shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Tile(SquareFrame(parent.context))

        override fun onBindViewHolder(holder: Tile, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_SELECTION) && !loading) {
                shown.getOrNull(position)?.let { holder.bindSelection(it, animate = true) }
            } else super.onBindViewHolder(holder, position, payloads)
        }

        override fun onBindViewHolder(holder: Tile, position: Int) {
            if (loading) {
                // Esqueleto pulsando enquanto a galeria carrega.
                holder.image.setImageDrawable(null)
                holder.image.tag = null
                holder.duration.visibility = View.GONE
                holder.badge.visibility = View.GONE
                holder.ring.visibility = View.GONE
                holder.root.setOnClickListener(null)
                holder.root.setOnLongClickListener(null)
                holder.image.animate().cancel()
                holder.image.scaleX = 1f; holder.image.scaleY = 1f
                holder.image.alpha = .5f
                ValueAnimator.ofFloat(.35f, .8f).apply {
                    duration = 700
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = 3
                    startDelay = (position % 3) * 90L
                    addUpdateListener { if (loading) holder.image.alpha = it.animatedValue as Float else cancel() }
                }.start()
                return
            }
            val item = shown[position]
            holder.image.alpha = 1f
            holder.badge.visibility = View.VISIBLE
            holder.duration.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            holder.duration.text = formatDuration(item.durationMs)
            holder.root.contentDescription = (if (item.isVideo) "Vídeo de ${formatDuration(item.durationMs)}" else "Foto") + ", ${item.bucket}"
            loadThumb(item, holder.image, dp(240))
            holder.bindSelection(item, animate = false)
            holder.root.setOnClickListener { toggle(item) }
            holder.root.setOnLongClickListener { showPreview(item); true }
        }
    }

    // ── Ícones desenhados (sem depender de drawables) ───────────────────────────

    private class PickerIcon(private val kind: Int, private val color: Int) : Drawable() {
        companion object { const val CLOSE = 0; const val FOLDER = 1; const val IMAGE = 2 }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = this@PickerIcon.color
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = b.width() / 24f
            paint.strokeWidth = 2.2f * s
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            fun p(x: Float) = x * s
            when (kind) {
                CLOSE -> {
                    canvas.drawLine(p(6f), p(6f), p(18f), p(18f), paint)
                    canvas.drawLine(p(18f), p(6f), p(6f), p(18f), paint)
                }
                FOLDER -> {
                    val path = Path().apply {
                        moveTo(p(3f), p(7f)); lineTo(p(3f), p(19f)); lineTo(p(21f), p(19f)); lineTo(p(21f), p(9f))
                        lineTo(p(12f), p(9f)); lineTo(p(10f), p(6f)); lineTo(p(3f), p(6f)); close()
                    }
                    canvas.drawPath(path, paint)
                }
                IMAGE -> {
                    canvas.drawRoundRect(RectF(p(3f), p(4f), p(21f), p(20f)), p(3f), p(3f), paint)
                    canvas.drawCircle(p(9f), p(9.5f), p(1.8f), paint)
                    val path = Path().apply { moveTo(p(4f), p(18f)); lineTo(p(10f), p(12.5f)); lineTo(p(14f), p(16f)); lineTo(p(16.5f), p(13.5f)); lineTo(p(20f), p(17f)) }
                    canvas.drawPath(path, paint)
                }
            }
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Drawable opacity")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = 48
        override fun getIntrinsicHeight() = 48
    }
}
