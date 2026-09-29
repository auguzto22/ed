package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import com.termex.replay15.editor.media.MediaSourceAccess
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
        setContentView(R.layout.activity_editor_library)

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

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

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

        val pickVideoAction = View.OnClickListener { openVideoPicker() }
        findViewById<Button>(R.id.btnNewProject).setOnClickListener(pickVideoAction)
        findViewById<View>(R.id.btnImportVideo).setOnClickListener(pickVideoAction)

        tabProjects.setOnClickListener { switchTab(LibraryTab.PROJECTS) }
        tabRecordings.setOnClickListener { switchTab(LibraryTab.RECORDINGS) }
        tabReplays.setOnClickListener { switchTab(LibraryTab.REPLAYS) }

        val bgSilk = findViewById<View>(R.id.libraryBgSilk)
        val libraryScroll = findViewById<ScrollView>(R.id.libraryScroll)
        val bottomDock = findViewById<View>(R.id.bottomDock)
        val glassCards = listOfNotNull(
            cardEmptyProjects,
            cardEmptyRecordings,
            cardEmptyReplays,
            bottomDock
        )
        glassCards.forEach { card ->
            if (card.id == R.id.bottomDock) {
                GlassEffectHelper.applyGlassDock(card)
            } else {
                GlassEffectHelper.applyGlassCard(card, cornerRadiusDp = 24f, elevationDp = 8f)
            }
        }
        if (bgSilk != null) {
            GlassEffectHelper.setupRealtimeBackdrop(this, bgSilk, libraryScroll, glassCards, blurRadiusDp = 24f)
        }

        handleIntentTab(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentTab(intent)
    }

    private fun handleIntentTab(intent: Intent?) {
        val tabExtra = intent?.getStringExtra("initial_tab")
        val initialDockTab = if (tabExtra == "PROJECTS") DockTab.EDITOR else DockTab.RECORDINGS
        if (tabExtra == "PROJECTS") {
            switchTab(LibraryTab.PROJECTS)
        } else {
            switchTab(LibraryTab.RECORDINGS)
        }
        DockNavigationHelper.bind(this, initialDockTab) { tab ->
            when (tab) {
                DockTab.EDITOR -> switchTab(LibraryTab.PROJECTS)
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
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQ_PICK_VIDEO) }
            .onFailure {
                Toast.makeText(this, "Seletor de vídeos indisponível", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_VIDEO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val persisted = runCatching { MediaSourceAccess.persistReadPermission(contentResolver, uri, data.flags) }.getOrDefault(false)
            if (!persisted) {
                Toast.makeText(this, "Não foi possível manter acesso a este vídeo", Toast.LENGTH_LONG).show()
                return
            }
            startActivity(Intent(this, EditorActivity::class.java).putExtra("uri", uri.toString()))
        }
    }

    private fun switchTab(tab: LibraryTab) {
        currentTab = tab

        fun styleTab(view: TextView, active: Boolean) {
            if (active) {
                view.setBackgroundResource(R.drawable.recly_tab_active)
                com.termex.replay15.ui.GlassEffectHelper.applyGlassCard(view, cornerRadiusDp = 22f, elevationDp = 4f)
                view.setTextColor(Color.parseColor("#F5F5F5"))
            } else {
                view.background = null
                view.elevation = 0f
                view.outlineProvider = null
                view.setTextColor(Color.parseColor("#A5A5AA"))
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
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0",
                    arrayOf("Movies/GravadorDeTela%"),
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

                        if (name.contains("Replay", ignoreCase = true)) {
                            replays.add(item)
                        } else {
                            recordings.add(item)
                        }
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
        } else {
            cardEmptyProjects.visibility = View.GONE
            listProjects.visibility = View.VISIBLE

            projects.forEach { project ->
                val card = buildItemCard(
                    title = project.name,
                    subtitle = timeLabel(project.durationUs),
                    iconRes = R.drawable.ic_pro_scissors,
                    actionText = "Editar",
                    onActionClick = {
                        startActivity(Intent(this, EditorActivity::class.java).putExtra("project", project.id))
                    },
                    secondaryActionText = "Excluir",
                    onSecondaryClick = {
                        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
                    }
                )
                listProjects.addView(card)
            }
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
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        root.addView(icon)

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subtitleView = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        }
        textLayout.addView(titleView)
        textLayout.addView(subtitleView)
        root.addView(textLayout)

        if (secondaryActionText != null && onSecondaryClick != null) {
            val secButton = TextView(this).apply {
                text = secondaryActionText
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 13f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { onSecondaryClick() }
            }
            root.addView(secButton)
        }

        val actionBtn = TextView(this).apply {
            text = actionText
            setTextColor(Color.BLACK)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.recly_pill_white)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onActionClick() }
        }
        root.addView(actionBtn)

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
