package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import com.termex.replay15.editor.media.GalleryPickerActivity
import com.termex.replay15.editor.media.MediaSourceAccess
import com.termex.replay15.editor.media.MediaSourceStatus
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.recly.editor.R
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.project.ProjectStore
import com.termex.replay15.account.AccountGate
import com.termex.replay15.ui.DockNavigationHelper
import com.termex.replay15.ui.DockTab
import com.termex.replay15.ui.GlassEffectHelper
import java.io.File
import java.util.concurrent.Executors

class EditorLibraryActivity : Activity() {

    companion object {
        private const val REQ_PICK_VIDEO = 810
    }

    private enum class LibraryTab {
        PROJECTS, RECORDINGS, REPLAYS
    }

    private val io = Executors.newSingleThreadExecutor()
    private var currentTab = LibraryTab.PROJECTS
    /** Ferramenta a abrir no editor depois de escolher o vídeo ("auto_edit" ou "analyze"); null = só abrir. */
    private var pendingOpenTool: String? = null

    private lateinit var tabProjects: TextView
    private lateinit var tabRecordings: TextView
    private lateinit var tabReplays: TextView

    private lateinit var containerProjects: LinearLayout
    private lateinit var cardEmptyProjects: LinearLayout
    private lateinit var listProjects: LinearLayout

    private lateinit var containerRecordings: LinearLayout
    private lateinit var cardEmptyRecordings: LinearLayout
    private lateinit var listRecordings: LinearLayout

    private lateinit var containerReplays: LinearLayout
    private lateinit var cardEmptyReplays: LinearLayout
    private lateinit var listReplays: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Primeira abertura: sem conta, vai para o cadastro e volta para cá depois.
        if (!AccountGate.ensure(this)) return
        setContentView(R.layout.activity_editor_library)
        if (savedInstanceState == null) BootSplashView.showOnce(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }

        tabProjects = findViewById(R.id.tabProjects)
        tabRecordings = findViewById(R.id.tabRecordings)
        tabReplays = findViewById(R.id.tabReplays)

        containerProjects = findViewById(R.id.containerProjects)
        cardEmptyProjects = findViewById(R.id.cardEmptyProjects)
        listProjects = findViewById(R.id.listProjects)

        containerRecordings = findViewById(R.id.containerRecordings)
        cardEmptyRecordings = findViewById(R.id.cardEmptyRecordings)
        listRecordings = findViewById(R.id.listRecordings)

        containerReplays = findViewById(R.id.containerReplays)
        cardEmptyReplays = findViewById(R.id.cardEmptyReplays)
        listReplays = findViewById(R.id.listReplays)

        val pickVideoAction = View.OnClickListener { pendingOpenTool = null; openVideoPicker() }
        findViewById<View>(R.id.btnNewProject).setOnClickListener(pickVideoAction)
        findViewById<View>(R.id.btnImportVideo).setOnClickListener(pickVideoAction)
        findViewById<View>(R.id.btnHomeAutoEdit).setOnClickListener { pendingOpenTool = "auto_edit"; openVideoPicker() }
        findViewById<View>(R.id.btnHomeAnalyze).setOnClickListener { pendingOpenTool = "analyze"; openVideoPicker() }
        // Perfil (canvas): leva para os ajustes, o mesmo destino do último item da barra de baixo.
        findViewById<View>(R.id.btnProfile).setOnClickListener { findViewById<View>(R.id.navSettings)?.performClick() }

        // "editar" em amarelo, como no título do canvas.
        findViewById<TextView>(R.id.homeTitle).apply {
            val full = "O que vamos editar hoje?"
            val start = full.indexOf("editar")
            text = android.text.SpannableString(full).apply {
                setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#FAFAF6")), start, start + "editar".length, 0)
            }
        }

        tabProjects.setOnClickListener { switchTab(LibraryTab.PROJECTS) }
        tabRecordings.setOnClickListener { switchTab(LibraryTab.RECORDINGS) }
        tabReplays.visibility = View.GONE // replays pertenciam a outro app

        val libraryScroll = findViewById<ScrollView>(R.id.libraryScroll)
        val bottomDock = findViewById<View>(R.id.bottomDock)
        val glassCards = listOfNotNull(
            cardEmptyProjects,
            cardEmptyRecordings,
            cardEmptyReplays,
            bottomDock
        )
        // Visual do canvas: superfícies chapadas com borda fina, sem vidro, desfoque nem fundo de seda.
        glassCards.forEach { card -> card.elevation = 0f }

        handleIntentTab(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentTab(intent)
    }

    private fun handleIntentTab(intent: Intent?) {
        val tabExtra = intent?.getStringExtra("initial_tab")
        // Abre no Início (cabeçalho + Recentes), como no canvas; "Gravações" lista os vídeos salvos.
        val initialDockTab = when (tabExtra) {
            "RECORDINGS" -> DockTab.RECORDINGS
            "PROJECTS" -> DockTab.EDITOR
            else -> DockTab.HOME
        }
        switchTab(if (initialDockTab == DockTab.RECORDINGS) LibraryTab.RECORDINGS else LibraryTab.PROJECTS)
        DockNavigationHelper.bind(this, initialDockTab) { tab ->
            when (tab) {
                DockTab.HOME, DockTab.EDITOR -> switchTab(LibraryTab.PROJECTS)
                DockTab.RECORDINGS, DockTab.LIBRARY -> switchTab(LibraryTab.RECORDINGS)
                else -> {}
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun openVideoPicker() {
        // Seletor próprio do Recly no lugar do seletor de documentos do sistema.
        val title = when (pendingOpenTool) {
            "auto_edit" -> "Vídeo para o Auto Edit"
            "analyze" -> "Vídeo para analisar"
            else -> "Novo projeto"
        }
        val mode = if (pendingOpenTool == null) GalleryPickerActivity.MODE_ANY else GalleryPickerActivity.MODE_VIDEOS
        startActivityForResult(GalleryPickerActivity.intent(this, mode, title = title), REQ_PICK_VIDEO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_VIDEO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val persisted = if (GalleryPickerActivity.isGalleryResult(data)) {
                MediaSourceAccess.verify(contentResolver, uri) == MediaSourceStatus.Available
            } else {
                runCatching { MediaSourceAccess.persistReadPermission(contentResolver, uri, data.flags) }.getOrDefault(false)
            }
            if (!persisted) {
                Toast.makeText(this, "Não foi possível manter acesso a este vídeo", Toast.LENGTH_LONG).show()
                return
            }
            val editor = Intent(this, EditorActivity::class.java).putExtra("uri", uri.toString())
            pendingOpenTool?.let { editor.putExtra("open_tool", it) }
            pendingOpenTool = null
            startActivity(editor)
        }
    }

    private fun switchTab(tab: LibraryTab) {
        currentTab = tab

        fun styleTab(view: TextView, active: Boolean) {
            if (active) {
                view.setBackgroundResource(R.drawable.recly_tab_active)
                view.elevation = 0f
                view.setTextColor(Color.WHITE)
            } else {
                view.background = null
                view.elevation = 0f
                view.outlineProvider = null
                view.setTextColor(Color.parseColor("#9A968C"))
            }
        }

        styleTab(tabProjects, tab == LibraryTab.PROJECTS)
        styleTab(tabRecordings, tab == LibraryTab.RECORDINGS)
        styleTab(tabReplays, tab == LibraryTab.REPLAYS)

        containerProjects.visibility = if (tab == LibraryTab.PROJECTS) View.VISIBLE else View.GONE
        containerRecordings.visibility = if (tab == LibraryTab.RECORDINGS) View.VISIBLE else View.GONE
        containerReplays.visibility = if (tab == LibraryTab.REPLAYS) View.VISIBLE else View.GONE
    }

    private fun loadData() {
        io.execute {
            val projectStore = ProjectStore(File(filesDir, "editor-projects"))
            val projects = runCatching { projectStore.list() }.getOrDefault(emptyList())

            val recordings = mutableListOf<VideoItem>()
            val replays = mutableListOf<VideoItem>()

            runCatching {
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION
                )
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    // Movies/Recly: vídeos exportados antes da troca de nome para Blitz.
                    "(${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?) AND ${MediaStore.Video.Media.IS_PENDING} = 0",
                    arrayOf("Movies/Blitz%", "Movies/Recly%"),
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    while (cursor.moveToNext() && (recordings.size + replays.size) < 150) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Vídeo"
                        val duration = cursor.getLong(durCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        val item = VideoItem(id, name, uri, duration)

                        recordings.add(item)
                    }
                }
            }

            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                renderProjects(projects, projectStore)
                renderRecordings(recordings)
                renderReplays(replays)
            }
        }
    }

    private fun renderProjects(projects: List<Project>, projectStore: ProjectStore) {
        listProjects.removeAllViews()
        if (projects.isEmpty()) {
            cardEmptyProjects.visibility = View.VISIBLE
            listProjects.visibility = View.GONE
            return
        }
        cardEmptyProjects.visibility = View.GONE
        listProjects.visibility = View.VISIBLE

        // Grade de duas colunas, como em "Recentes" no canvas.
        projects.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }
            pair.forEachIndexed { column, project ->
                val tile = buildProjectTile(project, rowIndex * 2 + column, projectStore)
                row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (column == 0) marginEnd = dp(7) else marginStart = dp(7)
                })
            }
            if (pair.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(7) })
            }
            listProjects.addView(row)
        }
    }

    /**
     * Cartão de projeto do canvas: miniatura com play, duração, formato, nome e quando foi editado.
     * Toque abre o projeto; toque longo pergunta se quer excluir (sem botões em cada cartão).
     */
    private fun buildProjectTile(project: Project, index: Int, projectStore: ProjectStore): View {
        val tones = intArrayOf(0xFF2A2D35.toInt(), 0xFF332D28.toInt(), 0xFF2A2F2B.toInt(), 0xFF2E2B34.toInt())
        val chipFill = Color.parseColor("#D9000000")
        val line = Color.parseColor("#2C2C30")

        fun chip(text: String, mono: Boolean) = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#FAFAF6"))
            textSize = 11f
            typeface = if (mono) android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            else android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(chipFill)
                cornerRadius = dp(5).toFloat()
            }
        }

        val thumb = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(tones[index % tones.size])
                setStroke(dp(1.5f), line)
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        thumb.addView(android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#CC000000"))
                setStroke(dp(1.5f), Color.parseColor("#4A4A50"))
            }
            addView(ImageView(this@EditorLibraryActivity).apply {
                setImageResource(R.drawable.ic_recly_play)
                imageTintList = ColorStateList.valueOf(Color.parseColor("#FAFAF6"))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        }, android.widget.FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        thumb.addView(chip(timeLabel(project.durationUs), true),
            android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM).apply { setMargins(dp(7), 0, 0, dp(9)) })
        thumb.addView(chip(aspectLabel(project), false),
            android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { setMargins(0, dp(7), dp(7), 0) })

        val name = TextView(this).apply {
            text = project.name
            setTextColor(Color.parseColor("#FAFAF6"))
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val whenText = TextView(this).apply {
            text = editedLabel(project.modifiedAt)
            setTextColor(Color.parseColor("#9A968C"))
            textSize = 12f
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = project.name
            addView(thumb, LinearLayout.LayoutParams(-1, dp(132)))
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(whenText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
            setOnClickListener {
                startActivity(Intent(this@EditorLibraryActivity, EditorActivity::class.java).putExtra("project", project.id))
            }
            setOnLongClickListener {
                AlertDialog.Builder(this@EditorLibraryActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Excluir projeto?")
                    .setMessage("Os vídeos originais serão mantidos.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir") { _, _ ->
                        io.execute {
                            projectStore.delete(project.id)
                            loadData()
                        }
                    }
                    .show()
                true
            }
        }
    }

    /** "9:16", "1:1", "16:9": a proporção do projeto. */
    private fun aspectLabel(project: Project): String {
        val (w, h) = project.dimensions()
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val g = gcd(w.coerceAtLeast(1), h.coerceAtLeast(1)).coerceAtLeast(1)
        val rw = w / g
        val rh = h / g
        return if (rw <= 32 && rh <= 32) "$rw:$rh" else String.format(java.util.Locale.ROOT, "%.2f:1", w.toFloat() / h.coerceAtLeast(1))
    }

    /** "Editado há 2 h", "Ontem", "3 dias atrás". */
    private fun editedLabel(modifiedAt: Long): String {
        if (modifiedAt <= 0L) return ""
        val now = System.currentTimeMillis()
        val ago = (now - modifiedAt).coerceAtLeast(0L)
        return if (ago < android.text.format.DateUtils.DAY_IN_MILLIS) {
            "Editado " + android.text.format.DateUtils.getRelativeTimeSpanString(
                modifiedAt, now, android.text.format.DateUtils.MINUTE_IN_MILLIS,
                android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
            )
        } else {
            android.text.format.DateUtils.getRelativeTimeSpanString(
                modifiedAt, now, android.text.format.DateUtils.DAY_IN_MILLIS
            ).toString()
        }
    }

    private fun renderRecordings(recordings: List<VideoItem>) {
        listRecordings.removeAllViews()
        if (recordings.isEmpty()) {
            cardEmptyRecordings.visibility = View.VISIBLE
            listRecordings.visibility = View.GONE
        } else {
            cardEmptyRecordings.visibility = View.GONE
            listRecordings.visibility = View.VISIBLE

            recordings.forEach { item ->
                val card = buildItemCard(
                    title = item.name,
                    subtitle = formatDuration(item.durationMs),
                    iconRes = R.drawable.ic_pro_video,
                    actionText = "Editar",
                    onActionClick = {
                        startActivity(Intent(this, EditorActivity::class.java).putExtra("uri", item.uri.toString()))
                    }
                )
                listRecordings.addView(card)
            }
        }
    }

    private fun renderReplays(replays: List<VideoItem>) {
        listReplays.removeAllViews()
        if (replays.isEmpty()) {
            cardEmptyReplays.visibility = View.VISIBLE
            listReplays.visibility = View.GONE
        } else {
            cardEmptyReplays.visibility = View.GONE
            listReplays.visibility = View.VISIBLE

            replays.forEach { item ->
                val card = buildItemCard(
                    title = item.name,
                    subtitle = "${formatDuration(item.durationMs)} • Replay Instantâneo",
                    iconRes = R.drawable.ic_save_15,
                    actionText = "Editar",
                    onActionClick = {
                        startActivity(Intent(this, EditorActivity::class.java).putExtra("uri", item.uri.toString()))
                    }
                )
                listReplays.addView(card)
            }
        }
    }

    private fun buildItemCard(
        title: String,
        subtitle: String,
        iconRes: Int,
        actionText: String,
        onActionClick: () -> Unit,
        secondaryActionText: String? = null,
        onSecondaryClick: (() -> Unit)? = null
    ): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.recly_card_dark)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) }
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#8C8880"))
        }
        root.addView(icon)

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#FAFAF6"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subtitleView = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9A968C"))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        }
        textLayout.addView(titleView)
        textLayout.addView(subtitleView)
        root.addView(textLayout)

        if (secondaryActionText != null && onSecondaryClick != null) {
            val secButton = TextView(this).apply {
                text = secondaryActionText
                setTextColor(Color.parseColor("#9A968C"))
                textSize = 13f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { onSecondaryClick() }
            }
            root.addView(secButton)
        }

        root.setOnClickListener { onActionClick() }
        return root
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(java.util.Locale.ROOT, "%02d:%02d", min, sec)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private data class VideoItem(
        val id: Long,
        val name: String,
        val uri: Uri,
        val durationMs: Long
    )
}
