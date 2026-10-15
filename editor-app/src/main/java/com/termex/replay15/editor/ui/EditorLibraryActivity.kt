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
import com.termex.replay15.account.AccountManager
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
        PROJECTS, RECORDINGS, REPLAYS, SETTINGS
    }

    private val io = Executors.newSingleThreadExecutor()
    private var currentTab = LibraryTab.PROJECTS
    /** Ferramenta a abrir no editor depois de escolher o vídeo ("auto_edit" ou "analyze"); null = só abrir. */
    private var pendingOpenTool: String? = null
    /** Miniaturas reais dos projetos (quadro do primeiro clipe), com cache em memória e disco. */
    private lateinit var thumbnails: ProjectThumbnails
    /** Decodifica miniaturas sem travar a lista nem o carregamento dos projetos. */
    private val thumbIo = Executors.newFixedThreadPool(2)
    private var lastProjects: List<Project> = emptyList()
    private var lastRecordingsCount = 0

    private lateinit var homeHero: LinearLayout
    private lateinit var containerSettings: LinearLayout

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

        thumbnails = ProjectThumbnails(this)
        homeHero = findViewById(R.id.homeHero)
        containerSettings = findViewById(R.id.containerSettings)

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
        // Perfil: visitante pode entrar/criar conta; com conta vai direto para Config. (dados da conta).
        findViewById<View>(R.id.btnProfile).setOnClickListener {
            if (AccountManager.isGuest(this) && !AccountManager.hasAccount(this)) {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Você está sem conta")
                    .setMessage("Crie uma conta ou entre para usar as legendas automáticas e guardar seu acesso.")
                    .setPositiveButton("Entrar ou criar conta") { _, _ ->
                        AccountGate.openAuth(this, null, com.termex.replay15.account.AuthActivity.MODE_SIGN_IN)
                    }
                    .setNeutralButton("Config.") { _, _ -> showDockTab(DockTab.SETTINGS) }
                    .setNegativeButton("Agora não", null)
                    .show()
            } else showDockTab(DockTab.SETTINGS)
        }

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
            "SETTINGS" -> DockTab.SETTINGS
            else -> DockTab.HOME
        }
        showDockTab(initialDockTab)
    }

    /**
     * Liga a barra de baixo. Antes "Editor" só repetia o Início e "Config." não fazia nada (não
     * existia tela de ajustes); agora cada item tem destino próprio.
     */
    private fun showDockTab(tab: DockTab) {
        val target = when (tab) {
            DockTab.RECORDINGS, DockTab.LIBRARY -> LibraryTab.RECORDINGS
            DockTab.SETTINGS -> LibraryTab.SETTINGS
            else -> LibraryTab.PROJECTS
        }
        switchTab(target)
        DockNavigationHelper.bind(this, tab) { chosen ->
            when (chosen) {
                DockTab.HOME, DockTab.RECORD -> switchTab(LibraryTab.PROJECTS)
                DockTab.RECORDINGS, DockTab.LIBRARY -> switchTab(LibraryTab.RECORDINGS)
                DockTab.SETTINGS -> switchTab(LibraryTab.SETTINGS)
                // Editor: continua o projeto mais recente; sem projeto, começa um novo.
                DockTab.EDITOR -> {
                    openLatestProject()
                    showDockTab(when (currentTab) {
                        LibraryTab.RECORDINGS -> DockTab.RECORDINGS
                        LibraryTab.SETTINGS -> DockTab.SETTINGS
                        else -> DockTab.HOME
                    })
                }
            }
        }
        findViewById<ScrollView>(R.id.libraryScroll)?.scrollTo(0, 0)
    }

    private fun openLatestProject() {
        val latest = lastProjects.maxByOrNull { it.modifiedAt }
        if (latest != null) {
            startActivity(Intent(this, EditorActivity::class.java).putExtra("project", latest.id))
        } else {
            Toast.makeText(this, "Nenhum projeto ainda. Escolha um vídeo para começar.", Toast.LENGTH_SHORT).show()
            pendingOpenTool = null
            openVideoPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        renderHeader()
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
        containerSettings.visibility = if (tab == LibraryTab.SETTINGS) View.VISIBLE else View.GONE
        // Título e atalhos só no Início; nas outras abas a tela mostra só o conteúdo dela.
        homeHero.visibility = if (tab == LibraryTab.PROJECTS) View.VISIBLE else View.GONE
        if (tab == LibraryTab.SETTINGS) renderSettings()
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
                lastProjects = projects
                lastRecordingsCount = recordings.size
                renderProjects(projects, projectStore)
                renderRecordings(recordings)
                renderReplays(replays)
                renderStats()
                if (currentTab == LibraryTab.SETTINGS) renderSettings()
            }
        }
    }

    private fun renderProjects(projects: List<Project>, projectStore: ProjectStore) {
        listProjects.removeAllViews()
        if (projects.isEmpty()) {
            cardEmptyProjects.visibility = View.VISIBLE
            listProjects.visibility = View.GONE
            findViewById<View>(R.id.recentHint).visibility = View.GONE
            return
        }
        cardEmptyProjects.visibility = View.GONE
        listProjects.visibility = View.VISIBLE
        findViewById<View>(R.id.recentHint).visibility = View.VISIBLE

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
     * Cartão de projeto: prévia real do vídeo (quadro do primeiro clipe, enquadrado inteiro sobre
     * um fundo desfocado do próprio quadro), proporção, duração, nome e quando foi editado.
     * Toque abre; toque longo ou o botão ⋯ abrem as opções (renomear, duplicar, excluir).
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
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        // Fundo: o mesmo quadro ampliado, escurecido (e desfocado no Android 12+), para não sobrar barra preta.
        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (Build.VERSION.SDK_INT >= 31) {
                setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(18).toFloat(), dp(18).toFloat(), android.graphics.Shader.TileMode.CLAMP))
            }
        }
        // Frente: o quadro inteiro, na proporção exata do vídeo.
        val frame = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        thumb.addView(backdrop, android.widget.FrameLayout.LayoutParams(-1, -1))
        thumb.addView(frame, android.widget.FrameLayout.LayoutParams(-1, -1))
        // Borda por cima da imagem, para o contorno do cartão não sumir atrás do quadro.
        thumb.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1.5f), line)
                cornerRadius = dp(12).toFloat()
            }
        }, android.widget.FrameLayout.LayoutParams(-1, -1))
        // Sombra embaixo para os textos dos chips continuarem legíveis sobre qualquer imagem.
        thumb.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#99000000"), Color.TRANSPARENT)
            )
        }, android.widget.FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        thumb.addView(android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#99000000"))
            }
            addView(ImageView(this@EditorLibraryActivity).apply {
                setImageResource(R.drawable.ic_recly_play)
                imageTintList = ColorStateList.valueOf(Color.parseColor("#FAFAF6"))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
        }, android.widget.FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        thumb.addView(chip(shortDuration(project.durationUs), true),
            android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM).apply { setMargins(dp(8), 0, 0, dp(8)) })
        thumb.addView(chip(aspectLabel(project), false),
            android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { setMargins(0, dp(8), dp(8), 0) })
        // Botão ⋯ (opções), canto de baixo à direita.
        thumb.addView(TextView(this).apply {
            text = "⋯"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FAFAF6"))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            contentDescription = "Opções de ${project.name}"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(chipFill)
            }
            setOnClickListener { showProjectOptions(project, projectStore) }
        }, android.widget.FrameLayout.LayoutParams(dp(30), dp(30), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(6), dp(6)) })

        fun show(bitmap: android.graphics.Bitmap) {
            backdrop.setImageBitmap(bitmap)
            frame.setImageBitmap(bitmap)
            backdrop.animate().alpha(0.45f).setDuration(180).start()
            frame.animate().alpha(1f).setDuration(180).start()
        }
        val cached = thumbnails.cached(project)
        if (cached != null) {
            backdrop.setImageBitmap(cached); frame.setImageBitmap(cached); backdrop.alpha = 0.45f; frame.alpha = 1f
        } else {
            frame.tag = project.id
            thumbIo.execute {
                val bitmap = runCatching { thumbnails.load(project) }.getOrNull() ?: return@execute
                runOnUiThread {
                    // A lista pode ter sido refeita enquanto decodificava.
                    if (!isDestroyed && frame.tag == project.id && frame.isAttachedToWindow) show(bitmap)
                }
            }
        }

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
            foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null,
                android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() })
            addView(thumb, LinearLayout.LayoutParams(-1, dp(168)))
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            addView(whenText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
            setOnClickListener {
                startActivity(Intent(this@EditorLibraryActivity, EditorActivity::class.java).putExtra("project", project.id))
            }
            setOnLongClickListener { showProjectOptions(project, projectStore); true }
        }
    }

    /** Opções do projeto: abrir, renomear, duplicar e excluir (antes só dava para excluir segurando). */
    private fun showProjectOptions(project: Project, projectStore: ProjectStore) {
        val options = arrayOf("Abrir", "Renomear", "Duplicar", "Excluir")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(project.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, EditorActivity::class.java).putExtra("project", project.id))
                    1 -> renameProject(project, projectStore)
                    2 -> duplicateProject(project, projectStore)
                    3 -> confirmDelete(project, projectStore)
                }
            }
            .show()
    }

    private fun renameProject(project: Project, projectStore: ProjectStore) {
        val input = android.widget.EditText(this).apply {
            setText(project.name)
            setSelectAllOnFocus(true)
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Renomear projeto")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == project.name) return@setPositiveButton
                io.execute {
                    val ok = runCatching {
                        // Relê do disco: o projeto pode ter mudado desde que a lista foi montada.
                        val current = projectStore.load(project.id)
                        projectStore.save(current.copy(name = newName, modifiedAt = System.currentTimeMillis()))
                    }.isSuccess
                    runOnUiThread { if (!ok) Toast.makeText(this, "Não foi possível renomear", Toast.LENGTH_SHORT).show() }
                    loadData()
                }
            }
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun duplicateProject(project: Project, projectStore: ProjectStore) {
        io.execute {
            val ok = runCatching {
                val current = projectStore.load(project.id)
                val now = System.currentTimeMillis()
                val baseName = current.name.take(190)
                projectStore.save(current.copy(
                    id = com.termex.replay15.editor.domain.newId(),
                    name = "$baseName (cópia)",
                    createdAt = now,
                    modifiedAt = now
                ))
            }.isSuccess
            runOnUiThread {
                Toast.makeText(this, if (ok) "Projeto duplicado" else "Não foi possível duplicar", Toast.LENGTH_SHORT).show()
            }
            loadData()
        }
    }

    private fun confirmDelete(project: Project, projectStore: ProjectStore) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Excluir \"${project.name}\"?")
            .setMessage("Os vídeos originais serão mantidos.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                io.execute {
                    projectStore.delete(project.id)
                    thumbnails.forget(project.id)
                    loadData()
                }
            }
            .show()
    }

    /** "0:05", "1:23", "1:02:03": duração curta para o chip (antes "00:05.000"). */
    private fun shortDuration(durationUs: Long): String {
        val total = ((durationUs.coerceAtLeast(0L) + 500_000L) / 1_000_000L)
        val h = total / 3600
        val m = total / 60 % 60
        val sec = total % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
    }

    /**
     * "9:16", "16:9", "1:1"... Encaixa nas proporções comuns com 4% de folga (antes um vídeo de
     * celular aparecia como "0.49:1"); fora disso mostra o valor real.
     */
    private fun aspectLabel(project: Project): String {
        val (w, h) = project.dimensions()
        val ratio = w.coerceAtLeast(1).toFloat() / h.coerceAtLeast(1)
        val common = listOf(
            "9:16" to 9f / 16, "16:9" to 16f / 9, "1:1" to 1f, "4:5" to 4f / 5, "5:4" to 5f / 4,
            "3:4" to 3f / 4, "4:3" to 4f / 3, "2:3" to 2f / 3, "3:2" to 3f / 2, "21:9" to 21f / 9,
            "9:19.5" to 9f / 19.5f, "19.5:9" to 19.5f / 9, "9:20" to 9f / 20, "20:9" to 20f / 9,
            "1.85:1" to 1.85f, "2.39:1" to 2.39f
        )
        val best = common.minByOrNull { kotlin.math.abs(it.second - ratio) / it.second }
        if (best != null && kotlin.math.abs(best.second - ratio) / best.second <= 0.04f) return best.first
        return if (ratio >= 1f) String.format(java.util.Locale.ROOT, "%.2f:1", ratio)
        else String.format(java.util.Locale.ROOT, "1:%.2f", 1f / ratio)
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

    /** Canto de cima à esquerda: saudação pela hora do dia e o nome da conta; inicial no perfil. */
    private fun renderHeader() {
        val session = AccountManager.session(this)
        val name = session?.displayName?.replaceFirstChar { it.titlecase() } ?: "Visitante"
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 5..11 -> "BOM DIA"
            in 12..17 -> "BOA TARDE"
            else -> "BOA NOITE"
        }
        findViewById<TextView>(R.id.homeGreeting).text = greeting
        findViewById<TextView>(R.id.homeUserName).text = name
        // Antes a letra "M" era fixa no layout; agora é a inicial de quem entrou.
        findViewById<TextView>(R.id.btnProfile).text =
            if (session != null) name.first().uppercase() else "?"
    }

    /** "3 projetos · 2 vídeos exportados" logo abaixo do nome. */
    private fun renderStats() {
        val stats = findViewById<TextView>(R.id.homeStats)
        val parts = mutableListOf<String>()
        val p = lastProjects.size
        if (p > 0) parts += if (p == 1) "1 projeto" else "$p projetos"
        val r = lastRecordingsCount
        if (r > 0) parts += if (r == 1) "1 vídeo exportado" else "$r vídeos exportados"
        stats.text = parts.joinToString(" · ")
        stats.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Aba Config. (antes o botão da barra e o perfil não levavam a lugar nenhum): conta, sair,
     * excluir conta (exigência da Play Store para apps com cadastro), armazenamento e versão.
     */
    private fun renderSettings() {
        val root = containerSettings
        root.removeAllViews()
        val text = Color.parseColor("#FAFAF6")
        val muted = Color.parseColor("#9A968C")
        val danger = Color.parseColor("#FF4D5E")

        fun title(t: String, size: Float, color: Int = text, top: Int = 0) = TextView(this).apply {
            this.text = t
            setTextColor(color)
            textSize = size
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(top), 0, 0)
        }
        fun section(label: String) = TextView(this).apply {
            this.text = label.uppercase()
            setTextColor(muted)
            textSize = 11f
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(4), dp(22), 0, dp(8))
        }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.recly_card_dark)
        }
        fun row(label: String, value: String? = null, color: Int = text, onClick: (() -> Unit)? = null) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(TextView(this@EditorLibraryActivity).apply {
                    this.text = label
                    setTextColor(color)
                    textSize = 15f
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (value != null) addView(TextView(this@EditorLibraryActivity).apply {
                    this.text = value
                    setTextColor(muted)
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    maxWidth = dp(200)
                })
                if (onClick != null) {
                    isClickable = true
                    isFocusable = true
                    background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, null)
                    setOnClickListener { onClick() }
                    if (value == null) addView(ImageView(this@EditorLibraryActivity).apply {
                        setImageResource(R.drawable.ic_pro_chevron_right)
                        imageTintList = ColorStateList.valueOf(muted)
                    }, LinearLayout.LayoutParams(dp(18), dp(18)))
                }
            }
        fun divider() = View(this).apply { setBackgroundColor(Color.parseColor("#1F1F22")) }
        fun LinearLayout.addRows(vararg rows: View) {
            rows.forEachIndexed { i, v ->
                if (i > 0) addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16) })
                addView(v)
            }
        }

        root.addView(title("Config.", 30f))

        // Conta
        root.addView(section("Conta"))
        val session = AccountManager.session(this)
        val account = card()
        if (session != null) {
            account.addRows(
                row("Nome", session.name.ifBlank { session.displayName }),
                row("E-mail", session.email),
                row("Sair da conta", color = text) { confirmSignOut() },
                row("Excluir conta", color = danger) { confirmDeleteAccount() }
            )
        } else {
            account.addRows(
                row(if (AccountManager.isGuest(this)) "Você está como visitante" else "Sem conta", "Legendas pelo servidor pedem conta"),
                row("Entrar ou criar conta") {
                    AccountGate.openAuth(this, null, com.termex.replay15.account.AuthActivity.MODE_SIGN_IN)
                }
            )
        }
        root.addView(account)

        // Armazenamento
        root.addView(section("Armazenamento"))
        val storage = card()
        val projectsRow = row("Projetos salvos", lastProjects.size.toString())
        val thumbsRow = row("Miniaturas em cache", "…")
        storage.addRows(
            projectsRow,
            row("Vídeos exportados", lastRecordingsCount.toString(), onClick = { showDockTab(DockTab.RECORDINGS) }),
            thumbsRow,
            row("Limpar miniaturas") {
                thumbIo.execute {
                    thumbnails.clear()
                    runOnUiThread {
                        Toast.makeText(this, "Miniaturas apagadas. Elas são refeitas ao abrir o Início.", Toast.LENGTH_SHORT).show()
                        if (currentTab == LibraryTab.SETTINGS) renderSettings()
                    }
                }
            }
        )
        root.addView(storage)
        thumbIo.execute {
            val bytes = thumbnails.diskBytes()
            runOnUiThread {
                ((thumbsRow.getChildAt(1)) as? TextView)?.text = android.text.format.Formatter.formatShortFileSize(this, bytes)
            }
        }

        // Sobre
        root.addView(section("Sobre"))
        val about = card()
        about.addRows(
            row("Versão", com.recly.editor.BuildConfig.VERSION_NAME),
            row("Arquivos originais", "Nunca são alterados")
        )
        root.addView(about)
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sair da conta?")
            .setMessage("Seus projetos continuam neste aparelho.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Sair") { _, _ ->
                AccountManager.signOut(this) {
                    if (!isDestroyed) AccountGate.openAuth(this, null, com.termex.replay15.account.AuthActivity.MODE_SIGN_IN)
                }
            }
            .show()
    }

    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Excluir conta?")
            .setMessage("Sua conta e seus dados de acesso serão apagados de vez. Os projetos e vídeos deste aparelho continuam aqui.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir conta") { _, _ ->
                AccountManager.deleteAccount(this) { result ->
                    if (isDestroyed) return@deleteAccount
                    result.fold(
                        onSuccess = {
                            Toast.makeText(this, "Conta excluída", Toast.LENGTH_LONG).show()
                            AccountGate.openAuth(this, null, com.termex.replay15.account.AuthActivity.MODE_SIGN_UP)
                        },
                        onFailure = { error ->
                            Toast.makeText(this, error.message ?: "Não foi possível excluir a conta", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
            .show()
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(java.util.Locale.ROOT, "%02d:%02d", min, sec)
    }

    override fun onDestroy() {
        io.shutdown()
        thumbIo.shutdownNow()
        super.onDestroy()
    }

    private data class VideoItem(
        val id: Long,
        val name: String,
        val uri: Uri,
        val durationMs: Long
    )
}
