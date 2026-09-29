package com.termex.replay15.editor.ui

import com.termex.replay15.editor.core.PreviewPacingProbe
import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.core.PreviewPerformanceController
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.export.*
import com.termex.replay15.editor.history.ProjectHistory
import com.termex.replay15.editor.media.AdaptiveProxyManager
import com.termex.replay15.editor.media.MediaImport
import com.termex.replay15.editor.media.MediaSourceAccess
import com.termex.replay15.editor.media.SubtitleImport
import com.termex.replay15.editor.media.CubeLut
import com.termex.replay15.editor.media.SubtitleDocument
import com.termex.replay15.editor.captions.CaptionGenerationSession
import com.termex.replay15.editor.captions.CaptionCorrectionMemory
import com.termex.replay15.editor.captions.CaptionCorrectionRecord
import com.termex.replay15.editor.captions.CaptionErrorType
import com.termex.replay15.editor.captions.CaptionGenerationOptions
import com.termex.replay15.editor.captions.GeminiCaptionGenerator
import com.termex.replay15.editor.render.EditorFonts
import com.termex.replay15.editor.render.GlResourcePool
import com.termex.replay15.editor.render.RenderPlan
import com.termex.replay15.editor.preview.*
import com.termex.replay15.editor.preview.engine.EditorPreviewEngine
import com.termex.replay15.editor.preview.engine.PreviewMediaFailure
import com.termex.replay15.editor.preview.engine.userMessage
import com.termex.replay15.editor.project.ProjectStore
import com.termex.replay15.editor.font.FontCatalog
import com.termex.replay15.editor.font.FontRepository
import com.termex.replay15.editor.timeline.TimelineView
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.recly.editor.R
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@android.annotation.SuppressLint("GestureBackNavigation")
class EditorActivity : Activity() {
    private data class ImportRequest(val uris: List<String>, val audio: Boolean, val replace: Int)
    private data class Session(val history: ProjectHistory, val baseline: Project, val position: Long, val selected: Int,
        val replace: Int, val stickerReplace: String?, val importing: ImportRequest?)
    private val sessionController = com.termex.replay15.editor.core.EditorSessionController()
    private var history: ProjectHistory
        get() = sessionController.history
        set(value) { sessionController.history = value }
    private var baseline = history.current
    private val project get() = history.current
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val captionSession = CaptionGenerationSession()
    private val captionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captionJob: Job? = null
    private lateinit var store: ProjectStore
    private lateinit var preview: EditorPreviewEngine
    private lateinit var timeline: TimelineView
    private lateinit var handles: TextHandlesView
    private lateinit var stickerHandles: StickerHandlesView
    private lateinit var videoHandles: VideoLayerHandlesView
    private lateinit var trackingOverlay: TrackingOverlayView
    private lateinit var videoTrackTools: VideoTrackTools
    private lateinit var effectTools: EffectTools
    private lateinit var backgroundRemovalTools: BackgroundRemovalTools
    private lateinit var transitionTools: TransitionTools
    private lateinit var animationTools: AnimationTools
    private lateinit var autoEditPanel: AutoEditPanel
    private lateinit var referenceStylePanel: ReferenceStylePanel
    private lateinit var previewBox: FrameLayout
    private lateinit var frame: FrameLayout
    private lateinit var top: LinearLayout
    private lateinit var timelinePane: LinearLayout
    private lateinit var bottom: HorizontalScrollView
    private lateinit var contextualTools: LinearLayout
    private lateinit var info: TextView
    private lateinit var projectTitleView: TextView
    private lateinit var clock: TextView
    private lateinit var quality: Button
    private lateinit var play: View
    private lateinit var undo: View
    private lateinit var redo: View
    private lateinit var empty: TextView
    private var loaded = false
    private var resumed = false
    private var busy = false
    private var fullscreen = false
    private var position = 0L
    private var selected = -1
    private var replaceIndex = -1
    private var importing: ImportRequest? = null
    private var copiedClipStyle: VideoClip? = null
    private var voiceRecorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var voiceStartUs = 0L
    private var voiceStartedAt = 0L
    private var voiceDialog: AlertDialog? = null
    private val voiceLimit = Runnable { finishVoiceover(true) }
    private var replaceStickerId: String? = null
    private var pendingLutClipId: String? = null
    /** UI-only caption scope; it is deliberately not part of Project persistence. */
    private var subtitleApplyRange: ApplyRange = ApplyRange.current()
    private var lastExportState = ExportState()
    private val autosave = Runnable { saveAsync(false) }
    private val thermalListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status ->
            PreviewPerformanceController.onThermalStatusChanged(status)
        }
    } else null
    private val tick = object : Runnable {
        override fun run() {
            if (preview.isPlaying) { position = preview.positionUs.coerceIn(0, project.durationUs); updatePosition() }
            val isPlaying = preview.isPlaying
            val controlVisibility = if (isPlaying) View.GONE else View.VISIBLE
            handles.visibility = controlVisibility
            stickerHandles.visibility = controlVisibility
            videoHandles.visibility = controlVisibility
            val iconRes = if (isPlaying) R.drawable.ic_recly_pause else R.drawable.ic_recly_play
            (play as? ViewGroup)?.findViewById<ImageView>(1001)?.setImageResource(iconRes)
            play.contentDescription = if (isPlaying) "Pausar" else "Reproduzir"
            val export = EditorExportService.state
            if (export != lastExportState) {
                val wasActive = lastExportState.active
                lastExportState = export
                if (export.message.isNotBlank()) info.text = export.message
                play.isEnabled = !export.active && project.allVideos.isNotEmpty()
                if (export.active) preview.suspendSources()
                else if (wasActive && loaded && resumed && !busy) preview.load(project, position)
                val exportUri = export.uri
                if (!export.active && exportUri != null) showExportComplete(exportUri)
                else if (wasActive && !export.active &&
                    (export.message.startsWith("Falha") || export.message.startsWith("MP4 preservado"))) {
                    AlertDialog.Builder(this@EditorActivity).setTitle("Exportação não concluída")
                        .setMessage(export.message).setPositiveButton("OK", null).show()
                }
            }
            main.postDelayed(this, 200)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!loaded || busy || currentFocus is EditText || event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val handled = when {
            event.keyCode == KeyEvent.KEYCODE_SPACE -> { if (project.allVideos.isNotEmpty()) preview.toggle(project.durationUs); true }
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Z && event.isShiftPressed -> {
                if (history.canRedo) performHistoryEdit { history.redo() }; true
            }
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Z -> {
                if (history.canUndo) performHistoryEdit { history.undo() }; true
            }
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Y -> {
                if (history.canRedo) performHistoryEdit { history.redo() }; true
            }
            event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL || event.keyCode == KeyEvent.KEYCODE_DEL -> {
                if (selected in project.videos.indices) { confirmDeleteSelected(); true } else false
            }
            else -> false
        }
        return if (handled) true else super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isEditorDebuggable()) {
            System.setProperty("recly.preview.transitionMode", intent.getStringExtra("transition_debug") ?: "NORMAL")
            System.setProperty("recly.preview.traceTransitions", intent.getBooleanExtra("trace_transitions", false).toString())
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .build()
            )
        }
        configureEdgeToEdge()
        FontCatalog.loadCatalog(this)
        store = ProjectStore(File(filesDir, "editor-projects"))
        buildScreen()
        if (isEditorDebuggable() && intent.getBooleanExtra("profile_preview", false)) {
            PreviewPacingProbe.attach(window) { preview.positionUs }
        }
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { closeEditor() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            getSystemService(PowerManager::class.java)?.addThermalStatusListener(thermalListener)
        }
        val retained = lastNonConfigurationInstance as? Session
        replaceIndex = retained?.replace ?: savedInstanceState?.getInt("replace", -1) ?: -1
        replaceStickerId = retained?.stickerReplace ?: savedInstanceState?.getString("stickerReplace")
        pendingLutClipId = savedInstanceState?.getString("lutClip")
        if (retained != null) {
            history = retained.history; baseline = retained.baseline; position = retained.position; selected = retained.selected
            loaded = true; refresh(false); checkAndRequestProxies(project)
            retained.importing?.let { request -> replaceIndex = request.replace; importMedia(request.uris.map(Uri::parse), request.audio) }
        } else {
            val id = savedInstanceState?.getString("project") ?: intent.getStringExtra("project")
            position = savedInstanceState?.getLong("position") ?: 0
            if (id != null) {
                busy = true
                io.execute {
                    val result = runCatching { store.load(id) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        busy = false; loaded = true
                        result.fold({ loaded ->
                            // Restore any missing downloaded fonts used in the project.
                            io.execute {
                                FontRepository(this@EditorActivity).restoreProjectFonts(loaded) { missingId ->
                                    runOnUiThread {
                                        Toast.makeText(this@EditorActivity,
                                            "Fonte \"$missingId\" indisponível; usando fonte padrão",
                                            Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            history = ProjectHistory(loaded); baseline = loaded; selected = -1; refresh(); checkMedia(); checkAndRequestProxies(loaded)
                            savedInstanceState?.getStringArrayList("importUris")?.takeIf { uris -> uris.isNotEmpty() }?.let { uris ->
                                replaceIndex = savedInstanceState.getInt("importReplace", -1)
                                importMedia(uris.map(Uri::parse), savedInstanceState.getBoolean("importAudio"))
                            }
                        },
                            { error("Nao foi possivel abrir o projeto. Ele nao foi sobrescrito. ${it.message}"); loaded = false })
                    }
                }
            } else {
                loaded = true
                val mediaUri: Uri? = intent.data
                    ?: intent.getStringExtra("uri")?.let { Uri.parse(it) }
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                    }
                if (mediaUri != null) {
                    importMedia(listOf(mediaUri), false)
                } else {
                    refresh(false)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun buildTopBar(): LinearLayout = row().apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setBackgroundColor(EditorStyle.BG)

        // Close button (X)
        val closeBtn = FrameLayout(this@EditorActivity).apply {
            contentDescription = "Fechar editor"
            tooltipText = "Fechar editor"
            isClickable = true
            isFocusable = true
            val bg = GradientDrawable().apply {
                setColor(EditorStyle.CARD)
                setStroke(dp(1), EditorStyle.BORDER)
                cornerRadius = dp(14).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), bg, null)
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_recly_close)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            addView(icon, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener { closeEditor() }
            expandTouchTarget(48)
        }
        addView(closeBtn, LinearLayout.LayoutParams(dp(36), dp(36)))

        // Project title with dropdown indicator (Novo projeto ▼)
        projectTitleView = TextView(this@EditorActivity).apply {
            text = "${project.name.ifBlank { "Novo projeto" }}  ▾"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val value = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
            setBackgroundResource(value.resourceId)
            setOnClickListener { projectPanel() }
        }
        addView(projectTitleView, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })

        // Search tools button (🔍)
        val searchBtn = FrameLayout(this@EditorActivity).apply {
            contentDescription = "Buscar ferramentas"
            isClickable = true
            isFocusable = true
            val bg = GradientDrawable().apply {
                setColor(EditorStyle.CARD)
                setStroke(dp(1), EditorStyle.BORDER)
                cornerRadius = dp(10).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), bg, null)
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_pro_search)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            addView(icon, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            setOnClickListener { searchTools() }
        }
        addView(searchBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(0, 0, dp(6), 0) })

        // Export quality button (Resolution selector, e.g. "1080p", "720p")
        quality = action("${project.export.shortSide}p") { exportPanel(false) }.apply {
            contentDescription = "Qualidade de exportação"
            tooltipText = "Qualidade de exportação"
            minWidth = dp(56); minimumWidth = dp(56)
            minHeight = dp(36); minimumHeight = dp(36)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        addView(quality, LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(0, 0, dp(6), 0) })

        // Export button (White pill, black text)
        val exportBtn = action("Exportar", true) { exportPanel(true) }.apply {
            contentDescription = "Exportar vídeo"
            tooltipText = "Exportar vídeo"
            minWidth = dp(74); minimumWidth = dp(74)
            minHeight = dp(36); minimumHeight = dp(36)
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        addView(exportBtn, LinearLayout.LayoutParams(-2, dp(36)))
    }

    private fun buildPlaybackControls(): LinearLayout = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(4))
        setBackgroundColor(EditorStyle.BG)

        fun controlBtn(name: String, iconRes: Int, accent: Boolean = false, callback: () -> Unit): View = FrameLayout(this@EditorActivity).apply {
            contentDescription = name
            tooltipText = name
            isClickable = true
            isFocusable = true
            val bg = GradientDrawable().apply {
                if (accent) {
                    setColor(Color.WHITE)
                    cornerRadius = dp(14).toFloat()
                } else {
                    setColor(EditorStyle.CARD)
                    setStroke(dp(1), EditorStyle.BORDER)
                    cornerRadius = dp(14).toFloat()
                }
            }
            background = RippleDrawable(
                ColorStateList.valueOf(if (accent) 0x33000000 else 0x26FFFFFF),
                bg,
                null,
            )
            val icon = ImageView(context).apply {
                id = 1001
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(if (accent) Color.BLACK else Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { callback() }
        }

        // Play / Pause button
        play = controlBtn("Reproduzir", R.drawable.ic_recly_play, accent = true) {
            preview.endScrub(position)
            if (project.allVideos.isNotEmpty()) preview.toggle(project.durationUs)
        }
        addView(play, LinearLayout.LayoutParams(dp(44), dp(44)))

        // Clock: 00:05 / 00:31
        clock = TextView(this@EditorActivity).apply {
            text = "00:00 / 00:00"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(12), 0, dp(12), 0)
        }
        addView(clock, LinearLayout.LayoutParams(-2, -2))

        // Spacer
        addView(View(this@EditorActivity), LinearLayout.LayoutParams(0, 0, 1f))

        // Frame step back & forward
        val prevFrame = controlBtn("Quadro anterior", R.drawable.ic_recly_prev_frame) {
            seek(position - SECOND / project.export.fps)
        }
        addView(prevFrame, LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(0, 0, dp(6), 0) })

        val nextFrame = controlBtn("Próximo quadro", R.drawable.ic_recly_next_frame) {
            seek(position + SECOND / project.export.fps)
        }
        addView(nextFrame, LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(0, 0, dp(8), 0) })

        // Undo & Redo
        undo = controlBtn("Desfazer", R.drawable.ic_recly_undo) {
            if (!busy && history.canUndo) performHistoryEdit { history.undo() }
        }
        addView(undo, LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(0, 0, dp(6), 0) })

        redo = controlBtn("Refazer", R.drawable.ic_recly_redo) {
            if (!busy && history.canRedo) performHistoryEdit { history.redo() }
        }
        addView(redo, LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(0, 0, dp(6), 0) })

        // Fullscreen toggle
        val fsBtn = controlBtn("Tela cheia", R.drawable.ic_recly_fullscreen) {
            fullscreen = !fullscreen
            listOf<View>(this@EditorActivity.top, timelinePane, this@EditorActivity.bottom, info).forEach { v -> v.visibility = if (fullscreen) View.GONE else View.VISIBLE }
        }
        addView(fsBtn, LinearLayout.LayoutParams(dp(38), dp(38)))
    }

    private fun buildDivider(workspace: LinearLayout, monitor: LinearLayout, landscape: Boolean): View = View(this).apply {
        contentDescription = "Redimensionar previa e linha do tempo"
        isFocusable = true
        background = GradientDrawable().apply {
            setColor(EditorStyle.BG)
            setStroke(dp(1), EditorStyle.BORDER)
        }
        var last = 0f
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { last = if (landscape) event.rawX else event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    val now = if (landscape) event.rawX else event.rawY
                    val delta = now - last; last = now
                    if (landscape) {
                        val total = workspace.width.coerceAtLeast(dp(320))
                        val monitorParams = monitor.layoutParams as LinearLayout.LayoutParams
                        val current = monitor.width + delta.toInt()
                        monitorParams.width = current.coerceIn(total * 35 / 100, total * 72 / 100)
                        monitorParams.weight = 0f; monitor.layoutParams = monitorParams
                    } else {
                        val total = workspace.height.coerceAtLeast(dp(420))
                        val params = timelinePane.layoutParams as LinearLayout.LayoutParams
                        params.height = (params.height - delta.toInt()).coerceIn(dp(160), total * 62 / 100)
                        timelinePane.layoutParams = params
                    }
                    fitPreview(); true
                }
                else -> true
            }
        }
    }

    private fun createTimelineView(): TimelineView = TimelineView(this).apply {
        onSeek = { seek(it) }
        onScrubStart = { preview.beginScrub() }
        onScrub = { scrub(it) }
        onScrubEnd = { preview.endScrub(position) }
        onSelect = { selectMainClip(it) }
        onClearSelection = { clearSelection() }
        onTransitionClick = { leftId, rightId -> transitionTools.open(leftId, rightId) }
        onTrim = { i, start, end -> edit(project.changeVideo(i) { it.copy(inUs = start, outUs = end) }) }
        onMove = { from, to -> selected = to; edit(project.moveVideo(from, to)) }
        onKeyframeMove = { clipIndex, keyIndex, requestedSourceUs ->
            val clip = project.videos.getOrNull(clipIndex)
            val key = clip?.keyframes?.getOrNull(keyIndex)
            if (clip != null && key != null) {
                val previous = clip.keyframes.getOrNull(keyIndex - 1)?.sourceUs?.plus(1) ?: clip.inUs
                val next = clip.keyframes.getOrNull(keyIndex + 1)?.sourceUs?.minus(1) ?: clip.outUs
                val moved = key.copy(sourceUs = requestedSourceUs.coerceIn(previous, next))
                edit(project.changeVideo(clipIndex) {
                    it.copy(keyframes = it.keyframes.mapIndexed { index, item -> if (index == keyIndex) moved else item })
                })
            }
        }
        onText = { textId ->
            val wasAlreadySelected = (handles.selected == textId && timeline.selectedLayerId == textId)
            selected = -1; this.selected = -1; handles.selected = textId; stickerHandles.selected = null
            videoHandles.mainClipIndex = -1; videoHandles.trackId = null; videoHandles.clipId = null
            timeline.selectedLayerId = textId; updateContextualTools()
            if (wasAlreadySelected) {
                openSubtitleOrTextTools(textId)
            }
        }
        onTextTrim = { textId, startUs, endUs ->
            com.termex.replay15.editor.transform.RealtimeProjectState.clearTiming(textId)
            val current = project.texts.firstOrNull { it.id == textId }
            if (current != null && (current.startUs != startUs || current.endUs != endUs)) {
                edit(project.copy(texts = project.texts.map {
                    if (it.id == textId) {
                        val trimmedCues = if (it.wordCues.isNotEmpty()) {
                            it.wordCues.filter { cue -> cue.startUs >= startUs && cue.endUs <= endUs }
                        } else it.wordCues
                        it.copy(startUs = startUs, endUs = endUs, wordCues = trimmedCues)
                    } else it
                }))
            }
        }
        onTextMove = { textId, startUs, endUs ->
            com.termex.replay15.editor.transform.RealtimeProjectState.clearTiming(textId)
            val current = project.texts.firstOrNull { it.id == textId }
            if (current != null && (current.startUs != startUs || current.endUs != endUs)) {
                val deltaUs = startUs - current.startUs
                edit(project.copy(texts = project.texts.map {
                    if (it.id == textId) {
                        val shiftedCues = if (it.wordCues.isNotEmpty()) {
                            it.wordCues.map { cue -> cue.copy(startUs = cue.startUs + deltaUs, endUs = cue.endUs + deltaUs) }
                                .filter { cue -> cue.startUs >= startUs && cue.endUs <= endUs }
                        } else it.wordCues
                        it.copy(startUs = startUs, endUs = endUs, wordCues = shiftedCues)
                    } else it
                }))
            }
        }
        onTextPreviewChange = { textId, startUs, endUs ->
            com.termex.replay15.editor.transform.RealtimeProjectState.updateTiming(textId, startUs, endUs)
            preview.redraw()
        }
        onTextKeyframeClick = { textId, key ->
            selected = -1; this.selected = -1; handles.selected = textId; stickerHandles.selected = null
            timeline.selectedLayerId = textId; updateContextualTools()
            val text = project.texts.find { it.id == textId }
            if (text != null) {
                com.termex.replay15.editor.ui.TextKeyframeTools.show(
                    activity = this@EditorActivity,
                    text = text,
                    timeUs = key.timeUs,
                    seek = ::seek,
                    preview = { changed -> previewEdit(project.copy(texts = project.texts.map { if (it.id == changed.id) changed else it })) },
                    restore = { previewEdit(project) },
                    apply = { changed -> edit(project.copy(texts = project.texts.map { if (it.id == changed.id) changed else it })) }
                )
            }
        }
        onAudio = { audioId ->
            val wasAlreadySelected = (timeline.selectedLayerId == audioId)
            selected = -1; this.selected = -1; handles.selected = null; stickerHandles.selected = null
            videoHandles.mainClipIndex = -1; videoHandles.trackId = null; videoHandles.clipId = null
            timeline.selectedLayerId = audioId; updateContextualTools()
            if (wasAlreadySelected) {
                audioPanel(audioId)
            }
        }
        onAudioTrim = { audioId, startUs, inUs, outUs ->
            val current = project.audio.firstOrNull { it.id == audioId }
            if (current != null && (current.startUs != startUs || current.inUs != inUs || current.outUs != outUs)) {
                edit(project.copy(audio = project.audio.map {
                    if (it.id == audioId) it.copy(startUs = startUs, inUs = inUs, outUs = outUs) else it
                }))
            }
        }
        onAudioMove = { audioId, startUs ->
            val current = project.audio.firstOrNull { it.id == audioId }
            if (current != null && current.startUs != startUs) {
                edit(project.copy(audio = project.audio.map {
                    if (it.id == audioId) it.copy(startUs = startUs) else it
                }))
            }
        }
        onAudioFade = { audioId, fadeInUs, fadeOutUs ->
            val current = project.audio.firstOrNull { it.id == audioId }
            if (current != null && (current.fadeInUs != fadeInUs || current.fadeOutUs != fadeOutUs)) {
                edit(project.copy(audio = project.audio.map {
                    if (it.id == audioId) it.copy(fadeInUs = fadeInUs, fadeOutUs = fadeOutUs) else it
                }))
            }
        }
        onAudioVolumeKeyframeClick = { audioId, key ->
            selected = -1; this.selected = -1; handles.selected = null; stickerHandles.selected = null
            videoHandles.mainClipIndex = -1; videoHandles.trackId = null; videoHandles.clipId = null
            timeline.selectedLayerId = audioId; updateContextualTools()
            val audio = project.audio.firstOrNull { it.id == audioId }
            if (audio != null) {
                seek(audio.startUs + key.timeUs)
            }
        }
        onSticker = { stickerId ->
            val wasAlreadySelected = (stickerHandles.selected == stickerId && timeline.selectedLayerId == stickerId)
            selected = -1; this.selected = -1; handles.selected = null; stickerHandles.selected = stickerId
            videoHandles.mainClipIndex = -1; videoHandles.trackId = null; videoHandles.clipId = null
            timeline.selectedLayerId = stickerId; updateContextualTools()
            if (wasAlreadySelected) {
                stickerPanel(stickerId)
            }
        }
        onStickerTrim = { stickerId, startUs, endUs ->
            com.termex.replay15.editor.transform.RealtimeProjectState.clearTiming(stickerId)
            val current = project.stickers.firstOrNull { it.id == stickerId }
            if (current != null && (current.startUs != startUs || current.endUs != endUs)) {
                edit(project.copy(stickers = project.stickers.map {
                    if (it.id == stickerId) it.copy(startUs = startUs, endUs = endUs) else it
                }))
            }
        }
        onStickerMove = { stickerId, startUs, endUs ->
            com.termex.replay15.editor.transform.RealtimeProjectState.clearTiming(stickerId)
            val current = project.stickers.firstOrNull { it.id == stickerId }
            if (current != null && (current.startUs != startUs || current.endUs != endUs)) {
                edit(project.copy(stickers = project.stickers.map {
                    if (it.id == stickerId) it.copy(startUs = startUs, endUs = endUs) else it
                }))
            }
        }
        onStickerPreviewChange = { stickerId, startUs, endUs ->
            com.termex.replay15.editor.transform.RealtimeProjectState.updateTiming(stickerId, startUs, endUs)
            preview.redraw()
        }
        onStickerKeyframeClick = { stickerId, key ->
            selected = -1; this.selected = -1; handles.selected = null; stickerHandles.selected = stickerId
            videoHandles.mainClipIndex = -1; videoHandles.trackId = null; videoHandles.clipId = null
            timeline.selectedLayerId = stickerId; updateContextualTools()
            seek(key.timeUs)
        }
        onMarker = { markerPanel(it) }
        onAdjustment = { effectTools.openAdjustment(it) }
        onVideoLayer = { track, clip -> videoTrackTools.open(track, clip) }
        onLayerMove = { track, clip, start -> videoTrackTools.move(track, clip, start) }
        onLayerTrim = { track, clip, start, end -> videoTrackTools.update(track, clip.copy(inUs = start, outUs = end)) }
        onTrack = { id -> videoTrackTools.controls(id, "Faixa") }
    }

    private fun buildTimelineContainer(landscape: Boolean): LinearLayout = column().apply {
        setBackgroundColor(EditorStyle.BG)

        val timelineHeader = row().apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }

        // "+ Adicionar áudio" button
        val addAudioBtn = LinearLayout(this@EditorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(5), dp(12), dp(5))
            val bg = GradientDrawable().apply {
                setColor(EditorStyle.CARD)
                setStroke(dp(1), EditorStyle.BORDER)
                cornerRadius = dp(14).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), bg, null)
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_pro_plus)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            addView(icon, LinearLayout.LayoutParams(dp(14), dp(14)))
            val txt = label("+ Adicionar áudio", 12f, Color.WHITE).apply {
                setPadding(dp(6), 0, 0, 0)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            addView(txt)
            setOnClickListener { audioPanel() }
        }
        timelineHeader.addView(addAudioBtn, LinearLayout.LayoutParams(-2, dp(32)))

        // Spacer
        timelineHeader.addView(View(this@EditorActivity), LinearLayout.LayoutParams(0, 0, 1f))

        // Snap button
        val snapBtn = FrameLayout(this@EditorActivity).apply {
            contentDescription = "Encaixe magnético"
            isClickable = true
            isFocusable = true
            val bg = GradientDrawable().apply {
                setColor(EditorStyle.CARD)
                setStroke(dp(1), EditorStyle.BORDER)
                cornerRadius = dp(10).toFloat()
            }
            background = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), bg, null)
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_recly_snap)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            addView(icon, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener {
                timeline.snapping = !timeline.snapping
                Toast.makeText(this@EditorActivity, if (timeline.snapping) "Encaixe magnético ativado" else "Encaixe magnético desativado", Toast.LENGTH_SHORT).show()
            }
        }
        timelineHeader.addView(snapBtn, LinearLayout.LayoutParams(dp(36), dp(32)).apply { setMargins(0, 0, dp(4), 0) })

        // Marker button
        timelineHeader.addView(iconAction("Marcador") { markerPanel() }, LinearLayout.LayoutParams(dp(36), dp(32)).apply { setMargins(0, 0, dp(4), 0) })

        // Zoom buttons
        timelineHeader.addView(iconAction("Zoom menos") { timeline.zoom(.7f) }, LinearLayout.LayoutParams(dp(36), dp(32)).apply { setMargins(0, 0, dp(4), 0) })
        timelineHeader.addView(iconAction("Zoom mais") { timeline.zoom(1.4f) }, LinearLayout.LayoutParams(dp(36), dp(32)))

        addView(timelineHeader)
        addView(timeline, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun addOrToggleKeyframeAtPlayhead() {
        val selectedTextId = handles.selected
        if (selectedTextId != null) {
            val text = project.texts.find { it.id == selectedTextId }
            if (text != null) {
                com.termex.replay15.editor.ui.TextKeyframeTools.show(
                    activity = this,
                    text = text,
                    timeUs = position,
                    seek = ::seek,
                    preview = { changed -> previewEdit(project.copy(texts = project.texts.map { if (it.id == changed.id) changed else it })) },
                    restore = { previewEdit(project) },
                    apply = { changed -> edit(project.copy(texts = project.texts.map { if (it.id == changed.id) changed else it })) }
                )
                return
            }
        }
        val selectedStickerId = stickerHandles.selected
        if (selectedStickerId != null) {
            val sticker = project.stickers.find { it.id == selectedStickerId }
            if (sticker != null) {
                val evaluated = sticker.transformAt(position)
                val newKey = com.termex.replay15.editor.transform.TransformKeyframe(timeUs = position.coerceIn(sticker.startUs, sticker.endUs), transform = evaluated)
                val existingIndex = sticker.transformKeyframes.indexOfFirst { abs(it.timeUs - position) < 25_000L }
                val updatedKeys = if (existingIndex >= 0) {
                    sticker.transformKeyframes.filterIndexed { index, _ -> index != existingIndex }
                } else {
                    (sticker.transformKeyframes + newKey).sortedBy { it.timeUs }
                }
                edit(project.copy(stickers = project.stickers.map {
                    if (it.id == sticker.id) it.copy(transformKeyframes = updatedKeys) else it
                }))
                Toast.makeText(this, if (existingIndex >= 0) "Keyframe removido" else "Keyframe de imagem inserido", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val selectedAudioId = timeline.selectedLayerId
        if (selectedAudioId != null) {
            val audio = project.audio.find { it.id == selectedAudioId }
            if (audio != null) {
                val localTimeUs = (position - audio.startUs).coerceIn(0L, audio.durationUs)
                val existingIndex = audio.volumeKeyframes.indexOfFirst { abs(it.timeUs - localTimeUs) < 25_000L }
                val updatedKeys = if (existingIndex >= 0) {
                    audio.volumeKeyframes.filterIndexed { index, _ -> index != existingIndex }
                } else {
                    val currentVol = audio.volumeAt(localTimeUs)
                    (audio.volumeKeyframes + com.termex.replay15.editor.domain.VolumeKeyframe(timeUs = localTimeUs, volume = currentVol)).sortedBy { it.timeUs }
                }
                edit(project.copy(audio = project.audio.map {
                    if (it.id == audio.id) it.copy(volumeKeyframes = updatedKeys) else it
                }))
                Toast.makeText(this, if (existingIndex >= 0) "Keyframe de volume removido" else "Keyframe de volume inserido", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val clip = selectedClipAtCursor() ?: return
        val clipIndex = selected.takeIf { it in project.videos.indices } ?: return
        val clipStart = project.startOf(clipIndex)
        val localUs = (position - clipStart).coerceIn(0L, clip.durationUs)
        val sourceUs = clip.timeMap.sourceAt(localUs)
        val existingIndex = clip.keyframes.indexOfFirst { abs(it.sourceUs - sourceUs) < 25_000L }
        if (existingIndex >= 0) {
            keyframePanel()
        } else {
            val evaluated = clip.transformAt(sourceUs)
            val newKey = evaluated.copy(sourceUs = sourceUs)
            val updatedKeys = (clip.keyframes + newKey).sortedBy { it.sourceUs }
            edit(project.changeVideo(clipIndex) { it.copy(keyframes = updatedKeys) })
            Toast.makeText(this, "Keyframe inserido no playhead", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildScreen() {
        val root = column().apply {
            setBackgroundColor(EditorStyle.BG)
        }
        editorInsets(root)
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        top = buildTopBar()
        root.addView(top)

        info = label("Novo projeto", 11f, EditorStyle.MUTED).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(16), dp(1), dp(16), dp(3))
            visibility = View.GONE
        }
        root.addView(info)

        val workspace = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))

        val monitor = column()
        workspace.addView(monitor, if (landscape) LinearLayout.LayoutParams(0, -1, 1.15f) else LinearLayout.LayoutParams(-1, 0, 0.46f))

        previewBox = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        frame = FrameLayout(this)
        val surface = SurfaceView(this)
        frame.addView(surface, FrameLayout.LayoutParams(-1, -1))
        videoHandles = VideoLayerHandlesView(this).apply {
            onChange = { track, clip ->
                PreviewPerformanceController.setInteractionMode(false)
                videoTrackTools.update(track, clip)
            }
            onMainChange = { index, clip ->
                PreviewPerformanceController.setInteractionMode(false)
                edit(project.changeVideo(index) { clip })
            }
            onPreviewChange = { _, _, clip ->
                PreviewPerformanceController.setInteractionMode(true)
                previewEdit(project.mapVideo(clip.id) { clip })
            }
            onCancelPreview = {
                PreviewPerformanceController.setInteractionMode(false)
                previewEdit(project)
            }
            onMainSelected = { index ->
                selectMainClip(index)
            }
        }
        frame.addView(videoHandles, FrameLayout.LayoutParams(-1, -1))
        handles = TextHandlesView(this).apply {
            onChange = { t ->
                PreviewPerformanceController.setInteractionMode(false)
                edit(project.copy(texts = project.texts.map { if (it.id == t.id) t else it }))
            }
            onPreviewChange = { changed ->
                PreviewPerformanceController.setInteractionMode(true)
                preview.redraw()
            }
            onCancelPreview = {
                PreviewPerformanceController.setInteractionMode(false)
                preview.redraw()
            }
            onSelected = { id ->
                this@EditorActivity.selected = -1; timeline.selected = -1; stickerHandles.selected = null
                videoHandles.mainClipIndex = -1; videoHandles.trackId = null; videoHandles.clipId = null
                val cleanId = id.takeIf { it.isNotBlank() }
                timeline.selectedLayerId = cleanId; this.selected = cleanId
                updateContextualTools()
            }
            onEdit = { openSubtitleOrTextTools(it) }
        }
        frame.addView(handles, FrameLayout.LayoutParams(-1, -1))
        stickerHandles = StickerHandlesView(this).apply {
            onChange = { changed ->
                PreviewPerformanceController.setInteractionMode(false)
                edit(project.copy(stickers = project.stickers.map { if (it.id == changed.id) changed else it }))
            }
            onPreviewChange = { changed ->
                PreviewPerformanceController.setInteractionMode(true)
                preview.redraw()
            }
            onCancelPreview = {
                PreviewPerformanceController.setInteractionMode(false)
                previewEdit(project)
            }
            onSelected = { id ->
                this@EditorActivity.selected = -1; timeline.selected = -1; handles.selected = null
                videoHandles.mainClipIndex = -1; videoHandles.trackId = null; videoHandles.clipId = null
                val cleanId = id.takeIf { it.isNotBlank() }
                timeline.selectedLayerId = cleanId; this.selected = cleanId
                updateContextualTools()
            }
            onEdit = { stickerPanel(it) }
        }
        frame.addView(stickerHandles, FrameLayout.LayoutParams(-1, -1))
        trackingOverlay = TrackingOverlayView(this).apply {
            visibility = View.GONE
        }
        frame.addView(trackingOverlay, FrameLayout.LayoutParams(-1, -1))
        previewBox.addView(frame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        empty = label("Sua proxima historia\ncomeca aqui\n\nToque para importar um video ou uma foto", 19f, EditorStyle.MUTED).apply {
            gravity = Gravity.CENTER; setOnClickListener { pick(false) }
        }
        previewBox.addView(empty, FrameLayout.LayoutParams(-1, -1))
        monitor.addView(previewBox, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(8), dp(4), dp(8), dp(2)) })
        previewBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
        preview = EditorPreviewEngine(this, surface, ::handlePreviewFailure)
        PreviewPerformanceController.onTierChangedListener = { _, _ ->
            runOnUiThread { if (!isDestroyed && ::preview.isInitialized) preview.quality = 0 }
        }
        effectTools = EffectTools(this, { project }, { position }, ::seek, ::edit, ::previewEdit, {
            runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 709) }
                .onFailure { error("Seletor de pacotes indisponivel") }
        }, openFilters = { filters() })
        backgroundRemovalTools = BackgroundRemovalTools(this, { project }, ::edit, ::previewEdit)
        transitionTools = TransitionTools(this, { project }, { position }, ::seek, ::edit, ::previewEdit) { startUs, endUs ->
            seek(startUs)
            if (!preview.isPlaying) preview.toggle(project.durationUs)
            main.postDelayed({
                if (preview.isPlaying && position >= endUs - 50_000L) {
                    preview.pause()
                }
            }, ((endUs - startUs) / 1000L).coerceIn(300L, 4000L))
        }
        animationTools = AnimationTools(this, { project }, ::edit) {
            runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"), 710) }
                .onFailure { error("Seletor de animacoes indisponivel") }
        }
        autoEditPanel = AutoEditPanel(
            this,
            { project },
            { selected },
            { suggested -> previewEdit(suggested) },
            { suggested -> edit(suggested) },
            { active ->
                busy = active
                if (active) { preview.pause(); info.text = "Analisando clipe..." }
                else refresh(reload = false)
            },
        )
        referenceStylePanel = ReferenceStylePanel(
            this, { project }, { selected }, ::pickReferenceVideo,
            { suggested -> previewEdit(suggested) }, { suggested -> edit(suggested) },
            { active -> busy = active; if (active) { preview.pause(); info.text = "Analisando estilo..." } else refresh(reload = false) },
        )
        videoTrackTools = VideoTrackTools(this, { project }, { position }, ::edit, ::previewEdit, ::seek, { }, ::pickVideoLayer,
            { track, clip -> videoHandles.trackId = track; videoHandles.clipId = clip; timeline.selectedLayerId = clip }, ::pickLut,
            { clip -> effectTools.open(clip) }, { clip -> backgroundRemovalTools.open(clip) }, { clip -> animationTools.open(clip) })

        val playbackControls = buildPlaybackControls()
        monitor.addView(playbackControls, LinearLayout.LayoutParams(-1, dp(52)))

        timeline = createTimelineView()
        timelinePane = buildTimelineContainer(landscape)

        val divider = buildDivider(workspace, monitor, landscape)
        workspace.addView(divider, 1, if (landscape) LinearLayout.LayoutParams(dp(10), -1) else LinearLayout.LayoutParams(-1, dp(6)))
        workspace.addView(timelinePane, if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 0.54f))

        bottom = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(EditorStyle.PANEL) }
        contextualTools = row().apply { setPadding(dp(8), dp(4), dp(8), dp(6)) }
        bottom.addView(contextualTools)
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(72)))

        updateContextualTools()
        setContentView(root)
        installEditorMotion(root)
    }

    private fun updateContextualTools() {
        if (!::contextualTools.isInitialized) return
        contextualTools.removeAllViews()
        val items: List<Pair<String, () -> Unit>> = when {
            handles.selected != null -> listOf(
                "Editar" to { handles.selected?.let(::openSubtitleOrTextTools) },
                "Máscara" to { handles.selected?.let(::textMaskPanel) },
                "Estilo" to { handles.selected?.let(::subtitleStylePanel) },
                "Fonte" to { handles.selected?.let(::subtitleFontPanel) },
                "Animacao" to { handles.selected?.let(::subtitleAnimationPanel) },
                "Palavra" to { handles.selected?.let(::subtitleWordPanel) },
                "Posicao" to { handles.selected?.let(::subtitlePositionPanel) },
                "Keyframe" to { addOrToggleKeyframeAtPlayhead() },
                "-1f" to { seek(position - SECOND / project.export.fps.coerceAtLeast(1)) },
                "+1f" to { seek(position + SECOND / project.export.fps.coerceAtLeast(1)) },
                "Excluir" to {
                    handles.selected?.let { id ->
                        edit(project.copy(texts = project.texts.filterNot { it.id == id }))
                        clearSelection()
                    }
                },
                "Mais" to { searchTools() },
            )
            stickerHandles.selected != null -> listOf(
                "Editar" to { stickerHandles.selected?.let(::stickerPanel) },
                "Máscara" to { stickerHandles.selected?.let(::stickerMaskPanel) },
                "Remover Fundo" to { stickerHandles.selected?.let(backgroundRemovalTools::openSticker) },
                "Keyframe" to { addOrToggleKeyframeAtPlayhead() },
                "-1f" to { seek(position - SECOND / project.export.fps.coerceAtLeast(1)) },
                "+1f" to { seek(position + SECOND / project.export.fps.coerceAtLeast(1)) },
                "Camada" to { stickerMenu() },
                "Excluir" to {
                    stickerHandles.selected?.let { id ->
                        edit(project.copy(stickers = project.stickers.filterNot { it.id == id }))
                        clearSelection()
                    }
                },
                "Mais" to { searchTools() },
            )
            timeline.selectedLayerId != null && project.audio.any { it.id == timeline.selectedLayerId } -> listOf(
                "Editar" to { timeline.selectedLayerId?.let(::audioPanel) },
                "Keyframe" to { addOrToggleKeyframeAtPlayhead() },
                "-1f" to { seek(position - SECOND / project.export.fps.coerceAtLeast(1)) },
                "+1f" to { seek(position + SECOND / project.export.fps.coerceAtLeast(1)) },
                "Volume" to { timeline.selectedLayerId?.let(::audioPanel) },
                "Excluir" to {
                    val id = timeline.selectedLayerId
                    if (id != null) {
                        edit(project.copy(audio = project.audio.filterNot { it.id == id }))
                        clearSelection()
                    }
                },
                "Mais" to { searchTools() },
            )
            selected in project.videos.indices -> {
                val currentClip = project.videos[selected]
                val nextClip = project.videos.getOrNull(selected + 1)
                val list = mutableListOf(
                    "Dividir" to { splitAtCursor() },
                    "Keyframe" to { addOrToggleKeyframeAtPlayhead() },
                    "Velocidade" to { speedPanel() },
                    "Volume" to { volumePanel() },
                    "Transformar" to { transformPanel() },
                    "Máscara" to { videoMaskPanel(currentClip) },
                    "Rastreamento" to { trackingPanel() },
                    "Filtros" to { filters() },
                    "Efeitos" to { effectTools.open(currentClip.id) },
                    "Remover Fundo" to { backgroundRemovalTools.open(currentClip.id) },
                )
                if (nextClip != null) {
                    list.add("Transição" to { transitionTools.open(currentClip.id, nextClip.id) })
                }
                list.addAll(listOf(
                    "Legendas" to { subtitleMenu() },
                    "Ajustar" to { adjustmentsPanel() },
                    "Cortar" to { trimPanel() },
                    "Excluir" to { confirmDeleteSelected() },
                    "Mais" to { editTools() },
                ))
                list
            }
            else -> listOf(
                "Áudio" to { audioPanel() },
                "Texto" to { textMenu() },
                "Voz" to { voiceoverPanel() },
                "Legendas" to { subtitleMenu() },
                "Ajustar" to { adjustmentsPanel() },
                "Filtros" to { selectedClipAtCursor(); filters() },
                "Efeitos" to { effectTools.adjustments() },
                "+ Midia" to { addMediaMenu() },
                "Câmera 3D" to { cameraPanel() },
            )
        }
        items.forEach { (name, click) -> contextualTools.addView(toolAction(name, click)) }
        contextualTools.alpha = 0f
        contextualTools.animate().cancel()
        contextualTools.animate().alpha(1f).setDuration(EditorStyle.MOTION_TOOLBAR).start()
    }

    private fun selectMainClip(index: Int) {
        selected = index.takeIf { it in project.videos.indices } ?: -1
        timeline.selected = selected
        handles.selected = null
        stickerHandles.selected = null
        videoHandles.mainClipIndex = selected
        videoHandles.trackId = null
        videoHandles.clipId = null
        timeline.selectedLayerId = null
        info.text = project.videos.getOrNull(selected)?.name ?: "Novo projeto"
        updateContextualTools()
    }

    private fun clearSelection() {
        selected = -1
        timeline.selected = -1
        timeline.selectedLayerId = null
        handles.selected = null
        stickerHandles.selected = null
        videoHandles.mainClipIndex = -1
        videoHandles.trackId = null
        videoHandles.clipId = null
        info.text = "${project.name}  /  ${project.videos.size} clipes  •  ${project.audio.size} audios  •  ${project.texts.size + project.stickers.size} camadas"
        updateContextualTools()
    }

    private fun fitPreview() {
        if (previewBox.width == 0 || previewBox.height == 0) return
        val (w, h) = project.dimensions()
        val factor = minOf(previewBox.width.toFloat() / w, previewBox.height.toFloat() / h)
        val fw = (w * factor).toInt().coerceAtLeast(1); val fh = (h * factor).toInt().coerceAtLeast(1)
        if (frame.layoutParams.width != fw || frame.layoutParams.height != fh)
            frame.layoutParams = FrameLayout.LayoutParams(fw, fh, Gravity.CENTER)
    }
    private fun edit(next: Project) {
        if (!loaded || busy) return
        val before = project
        val resumePlayback = preview.playWhenReady
        runCatching { sessionController.apply(next) }.fold(
            { if (it) {
                val previousCaptions = before.texts.filter { it.isCaption }.associateBy { it.id }
                val corrections = next.texts.mapNotNull { changed ->
                    val old = previousCaptions[changed.id]
                    if (old != null && old.text != changed.text) CaptionCorrectionRecord(
                        audioSegment = "project:${before.id}:${old.startUs / 1_000}-${old.endUs / 1_000}",
                        prediction = old.text,
                        correctText = changed.text,
                        errorType = CaptionErrorType.PHONETIC_CONFUSION,
                        modelVersion = "editor-caption",
                        startMs = old.startUs / 1_000,
                        endMs = old.endUs / 1_000,
                    ) else null
                }
                if (corrections.isNotEmpty()) {
                    val projectId = before.id
                    io.execute {
                        val memory = CaptionCorrectionMemory(File(filesDir, "caption_corrections/$projectId.json"))
                        corrections.forEach { memory.record(it) }
                    }
                }
                afterEdit(before, resumePlayback)
            } }, { error(it.message ?: "Nao foi possivel alterar o projeto") })
    }
    private var previewDraft: Project? = null
    private fun previewEdit(next: Project) {
        preview.update(next)
        previewDraft = next
    }
    private fun performHistoryEdit(operation: () -> Unit) {
        val before = project
        val resumePlayback = preview.playWhenReady
        operation()
        afterEdit(before, resumePlayback)
    }
    private fun afterEdit(before: Project? = null, resumePlayback: Boolean = false) {
        previewDraft = null
        position = position.coerceIn(0, project.durationUs)
        selected = selected.coerceIn(-1, project.videos.lastIndex)
        if (before == null) refresh(reload = true, resumePlayback = resumePlayback)
        else { preview.update(project); refresh(reload = false) }
        main.removeCallbacks(autosave); main.postDelayed(autosave, 1200)
        checkAndRequestProxies(project)
    }

    private fun checkAndRequestProxies(currentProject: Project) {
        val simultaneousLayers = RenderPlan.layers(currentProject).count { it.clips.isNotEmpty() }
        val allVideos = currentProject.allVideos
        for (clip in allVideos) {
            if (clip.proxyUri == null && !clip.image) {
                AdaptiveProxyManager.requestProxyIfNeeded(
                    context = applicationContext,
                    clip = clip,
                    simultaneousLayers = simultaneousLayers,
                ) { clipId, proxyUri ->
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        val p = project
                        val updated = p.mapVideo(clipId) { it.copy(proxyUri = proxyUri.toString()) }
                        if (updated != p) {
                            history.updateWithoutHistory(updated)
                            if (preview.isPlaying) {
                                preview.update(updated)
                            } else {
                                preview.update(updated, redrawIfPaused = true)
                            }
                            if (isEditorDebuggable()) {
                                android.util.Log.d("ReclyEditor", "Swapped in proxy for clip=$clipId -> $proxyUri")
                            }
                        }
                    }
                }
            }
        }
    }
    private fun refresh(reload: Boolean = true, resumePlayback: Boolean = false) {
        timeline.project = project; timeline.selected = selected
        videoHandles.project = project; videoHandles.mainClipIndex = selected
        if (handles.selected !in project.texts.map { it.id }) handles.selected = null
        if (stickerHandles.selected !in project.stickers.map { it.id }) stickerHandles.selected = null
        handles.texts = project.texts.takeIf { project.visualEnabled(TEXT_TRACK) && !project.trackState(TEXT_TRACK).locked } ?: emptyList(); handles.invalidate()
        stickerHandles.stickers = project.stickers.takeIf { project.visualEnabled(STICKER_TRACK) && !project.trackState(STICKER_TRACK).locked } ?: emptyList(); stickerHandles.invalidate()
        quality.text = "${project.export.shortSide}p"
        quality.contentDescription = "Qualidade de exportação: ${project.export.shortSide}p"
        undo.isEnabled = history.canUndo; undo.alpha = if (history.canUndo) 1.0f else 0.35f
        redo.isEnabled = history.canRedo; redo.alpha = if (history.canRedo) 1.0f else 0.35f
        play.isEnabled = project.allVideos.isNotEmpty() && !EditorExportService.state.active
        val playIconRes = if (preview.isPlaying) R.drawable.ic_recly_pause else R.drawable.ic_recly_play
        (play as? ViewGroup)?.findViewById<ImageView>(1001)?.setImageResource(playIconRes)
        empty.visibility = if (project.allVideos.isEmpty()) View.VISIBLE else View.GONE
        info.text = "${project.name}  /  ${project.videos.size} clipes  •  ${project.audio.size} audios  •  ${project.texts.size + project.stickers.size} camadas"
        if (::projectTitleView.isInitialized) {
            projectTitleView.text = "${project.name.ifBlank { "Novo projeto" }}  ▾"
        }
        updateContextualTools()
        updatePosition(); fitPreview()
        if (reload && resumed && !EditorExportService.state.active) {
            preview.load(project, position, resumePlayback)
        }
    }
    /** Decoder seeks are coalesced by the preview engine; UI feedback stays immediate. */
    private fun scrub(us: Long) {
        position = us.coerceIn(0, project.durationUs)
        syncSelectedClipToPosition()
        updatePosition()
        preview.seek(position)
    }

    private fun seek(us: Long) {
        if (preview.playWhenReady && !preview.isScrubbing) preview.pause()
        position = us.coerceIn(0, project.durationUs)
        syncSelectedClipToPosition()
        preview.seek(position)
        updatePosition()
    }
    private fun updatePosition() {
        val started = PreviewPacingProbe.begin("Recly.updatePosition")
        try { updatePositionMeasured() }
        finally { PreviewPacingProbe.end("Recly.updatePosition", started) }
    }
    private fun updatePositionMeasured() {
        timeline.positionUs = position; handles.timeUs = position; stickerHandles.timeUs = position
        videoHandles.timeUs = position; videoHandles.mainClipIndex = selected
        clock.text = timeLabelCapCut(position, project.durationUs)
    }
    private fun error(message: String) {
        info.text = message
        AlertDialog.Builder(this).setTitle("Editor").setMessage(message).setPositiveButton("OK", null).show()
    }
    private fun handlePreviewFailure(failure: PreviewMediaFailure) {
        if (isDestroyed) return
        val message = failure.userMessage()
        info.text = message
        if (failure !is PreviewMediaFailure.MissingSource && failure !is PreviewMediaFailure.PermissionLost &&
            failure !is PreviewMediaFailure.UnsupportedFormat) return
        val index = project.videos.indexOfFirst { it.id == failure.clipId }
        val dialog = AlertDialog.Builder(this).setTitle("Mídia do projeto").setMessage(message).setNegativeButton("Fechar", null)
        if (index >= 0) {
            dialog.setNeutralButton("Remover") { _, _ ->
                val videos = project.videos.filterIndexed { position, _ -> position != index }
                selected = -1
                edit(project.copy(videos = videos, transitions = project.cleanTransitions(videos)))
            }
            dialog.setPositiveButton("Substituir") { _, _ -> pick(false, index) }
        }
        dialog.show()
    }
    private fun checkMedia() {
        val snapshot = project
        io.execute {
            val statuses = MediaImport.sourceStatuses(this, snapshot)
            val missing = statuses.filterValues { it is com.termex.replay15.editor.media.MediaSourceStatus.Missing }.keys
            val denied = statuses.filterValues { it is com.termex.replay15.editor.media.MediaSourceStatus.PermissionLost }.keys
            runOnUiThread {
                if (!isDestroyed && (missing.isNotEmpty() || denied.isNotEmpty())) {
                    val affected = missing + denied
                    val missingVideo = project.videos.indexOfFirst { it.uri in affected }
                    if (missingVideo >= 0) { selected = missingVideo; timeline.selected = selected }
                    if (denied.isNotEmpty()) error("O Recly perdeu acesso a um arquivo do projeto. Selecione-o novamente.")
                    else error("Arquivo original não foi encontrado. Para vídeo, use Editar > Substituir.")
                }
            }
        }
    }
    private fun saveAsync(exit: Boolean) {
        if (!loaded) { if (exit) finish(); return }
        val snapshot = project
        io.execute {
            val result = runCatching { store.save(snapshot) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold({ if (exit) finish() else if (project == snapshot) info.text = "Projeto salvo no aparelho" },
                    { error("Nao foi possivel salvar o projeto: ${it.message}") })
            }
        }
    }
    private fun closeEditor() {
        if (busy) { Toast.makeText(this, "Aguarde o processamento terminar", Toast.LENGTH_SHORT).show(); return }
        if (fullscreen) {
            fullscreen = false; listOf(top, timelinePane, bottom, info).forEach { it.visibility = View.VISIBLE }; return
        }
        preview.pause()
        if (!loaded || project == baseline) { finish(); return }
        AlertDialog.Builder(this).setTitle("Fechar editor?")
            .setMessage("Salvar guarda a edicao para continuar depois. Os videos originais nao mudam.")
            .setPositiveButton("Salvar projeto") { _, _ -> main.removeCallbacks(autosave); saveAsync(true) }
            .setNegativeButton("Descartar alteracoes") { _, _ ->
                main.removeCallbacks(autosave)
                history = ProjectHistory(baseline)
                io.execute {
                    val result = runCatching { if (baseline.allVideos.isEmpty() && baseline.audio.isEmpty() && baseline.texts.isEmpty() && baseline.stickers.isEmpty()) store.delete(baseline.id) else store.save(baseline) }
                    runOnUiThread { if (result.isSuccess) { loaded = false; finish() } else error("Falha ao descartar alteracoes") }
                }
            }.setNeutralButton("Cancelar", null).show()
    }
    // API 33+ is registered with the platform OnBackInvokedDispatcher in onCreate.
    @Deprecated("Legacy Android back") override fun onBackPressed() { closeEditor() }
    override fun onResume() {
        super.onResume(); resumed = true
        if (loaded && !busy && !EditorExportService.state.active) preview.resume(position)
        main.post(tick)
    }
    override fun onPause() {
        resumed = false; preview.pause(); main.removeCallbacks(tick)
        if (voiceRecorder != null) finishVoiceover(true)
        super.onPause()
    }
    override fun onStop() {
        main.removeCallbacks(autosave)
        if (loaded) saveAsync(false)
        preview.pause(); super.onStop()
    }
    override fun onDestroy() {
        captionSession.cancel()
        captionJob?.cancel()
        captionScope.cancel()
        PreviewPacingProbe.detach()
        main.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            getSystemService(PowerManager::class.java)?.removeThermalStatusListener(thermalListener)
        }
        if (voiceRecorder != null) finishVoiceover(false)
        PreviewPerformanceController.onTierChangedListener = null
        autoEditPanel.close(); referenceStylePanel.close(); preview.release(); timeline.release(); videoTrackTools.close(); effectTools.close(); backgroundRemovalTools.close(); transitionTools.close(); animationTools.close(); io.shutdown(); super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        PreviewPerformanceController.onTrimMemory(level)
        GlResourcePool.clear()
        EffectThumbnailCache.clear()
        FilterThumbnailCache.clear()
        if (::timeline.isInitialized) {
            timeline.clearThumbnails()
        }
    }
    override fun onRetainNonConfigurationInstance(): Any? =
        if (loaded) Session(history, baseline, position, selected, replaceIndex, replaceStickerId, importing) else null
    override fun onSaveInstanceState(outState: Bundle) {
        if (loaded) { outState.putString("project", project.id); outState.putLong("position", position) }
        outState.putInt("replace", replaceIndex)
        outState.putString("stickerReplace", replaceStickerId)
        outState.putString("lutClip", pendingLutClipId)
        importing?.let {
            outState.putStringArrayList("importUris", ArrayList(it.uris)); outState.putBoolean("importAudio", it.audio)
            outState.putInt("importReplace", it.replace)
        }
        super.onSaveInstanceState(outState)
    }

    private fun addMediaMenu() {
        choose("Adicionar", listOf("Video ou foto", "Musica ou audio", "Gravar narracao", "Texto", "Legendas SRT", "Imagem sobreposta / sticker", "Objeto Nulo (Null 3D)")) {
            when (it) { 0 -> pick(false); 1 -> pick(true); 2 -> voiceoverPanel(); 3 -> textPanel(); 4 -> pickSubtitles(); 5 -> pickSticker(); 6 -> addNullObject() }
        }
    }
    private fun addNullObject() {
        if (project.videos.size >= 200) { error("Limite de 200 clipes"); return }
        val nullClip = VideoClip(
            uri = "null://object",
            name = "Null ${project.videos.count { it.isNullObject } + 1}",
            sourceUs = 5_000_000L,
            width = 1080,
            height = 1920,
            isNullObject = true,
            is3D = true,
        )
        edit(project.copy(videos = project.videos + nullClip))
        Toast.makeText(this, "Objeto Nulo adicionado como controlador 3D", Toast.LENGTH_SHORT).show()
    }
    private fun pickVideoLayer() {
        if (busy) return
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 708) }
            .onFailure { error("Seletor de videos indisponivel") }
    }
    private fun pickReferenceVideo() {
        if (busy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        runCatching { startActivityForResult(intent, 711) }.onFailure { error("Seletor de videos indisponivel") }
    }
    private fun pick(audio: Boolean, replace: Int = -1) {
        if (busy) return
        replaceIndex = replace
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (audio) "audio/*" else "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (!audio) intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, !audio && replace < 0)
        runCatching { startActivityForResult(intent, if (audio) 701 else 700) }.onFailure { error("Seletor de arquivos indisponivel") }
    }
    private fun pickSticker(replaceId: String? = null) {
        if (busy) return
        if (project.durationUs <= 0) { error("Adicione um video antes da camada de imagem"); return }
        if (replaceId == null && project.stickers.size >= 24) { error("Limite de 24 camadas de imagem"); return }
        replaceStickerId = replaceId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        runCatching { startActivityForResult(intent, 702) }.onFailure { error("Seletor de imagens indisponivel") }
    }
    private fun pickSubtitles() {
        if (busy || project.durationUs <= 0) return
        if (project.texts.size >= MAX_TEXTS) { error("Limite de $MAX_TEXTS textos/legendas atingido"); return }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-subrip", "text/plain"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        runCatching { startActivityForResult(intent, 704) }.onFailure { error("Seletor de legendas indisponivel") }
    }
    @Deprecated("Platform document picker") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 711) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                if (persistPickedAccess(data, listOf(uri))) referenceStylePanel.analyze(uri)
            }
            return
        }
        if (requestCode == 710) { if (resultCode == RESULT_OK && data?.data != null) animationTools.importPackage(data.data!!); return }
        if (requestCode == 709) { if (resultCode == RESULT_OK && data?.data != null) effectTools.importPackage(data.data!!); return }
        if (requestCode == 708) {
            if (resultCode == RESULT_OK && data?.data != null) {
                if (persistPickedAccess(data, listOf(data.data!!))) videoTrackTools.importVideo(data.data!!)
            }
            return
        }
        if (requestCode == 705) {
            val clipId = pendingLutClipId; pendingLutClipId = null
            if (resultCode == RESULT_OK && data?.data != null && clipId != null) importLut(data.data!!, clipId)
            return
        }
        if (requestCode == 706) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!; val snapshot = project
                io.execute {
                    val result = runCatching {
                        val stream = contentResolver.openOutputStream(uri) ?: throw IllegalArgumentException("Destino indisponivel")
                        stream.bufferedWriter(Charsets.UTF_8).use { it.write(SubtitleDocument.write(snapshot.texts, snapshot.durationUs)) }
                    }
                    runOnUiThread { if (!isDestroyed) result.fold({ info.text = "Arquivo SRT exportado" }, { error("Falha ao salvar SRT: ${it.message}") }) }
                }
            }
            return
        }
        if (requestCode !in setOf(700, 701, 702, 704)) return
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == 702) replaceStickerId = null
            if (requestCode == 700) replaceIndex = -1
            return
        }
        val uris = data.clipData?.let { clip -> List(clip.itemCount) { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        if (!persistPickedAccess(data, uris)) return
        when (requestCode) {
            702 -> importSticker(uris.firstOrNull() ?: return)
            704 -> importSubtitles(uris.firstOrNull() ?: return)
            else -> importMedia(uris, requestCode == 701)
        }
    }

    private fun persistPickedAccess(result: Intent, uris: List<Uri>): Boolean = try {
        val ok = uris.all { uri -> uri.scheme != "content" || MediaSourceAccess.persistReadPermission(contentResolver, uri, result.flags) }
        if (!ok) error("Este provedor não ofereceu acesso persistente. Escolha o arquivo pelo armazenamento do aparelho.")
        ok
    } catch (_: SecurityException) {
        error("O Recly não conseguiu manter acesso ao arquivo. Escolha-o novamente pelo armazenamento do aparelho.")
        false
    }
    private fun importMedia(uris: List<Uri>, audio: Boolean) {
        if (busy || uris.isEmpty()) return
        if (!audio && project.videos.size + uris.size > 200) { error("Limite de 200 clipes por projeto"); return }
        busy = true; preview.suspendSources(); info.text = "Importando midia..."
        val snapshot = project; val replace = replaceIndex; replaceIndex = -1; val at = position
        importing = ImportRequest(uris.map { it.toString() }, audio, replace)
        io.execute {
            val result = runCatching {
                if (audio) {
                    require(snapshot.durationUs > 0) { "Adicione um video antes da musica" }
                    require(snapshot.audio.size < 8) { "Limite de 8 faixas de audio" }
                    snapshot.copy(audio = snapshot.audio + MediaImport.audio(this, uris.first(), minOf(at, snapshot.durationUs - 1)))
                } else {
                    val clips = uris.map { MediaImport.video(this, it) }
                    val list = snapshot.videos.toMutableList()
                    if (replace in list.indices) list[replace] = clips.first() else list.addAll(clips)
                    val firstMedia = snapshot.allVideos.isEmpty() && replace < 0
                    val automaticExport = if (firstMedia) ExportSettings.forSource(clips.first(), snapshot.export.audioBitrate) else snapshot.export
                    snapshot.copy(videos = list,
                        name = if (snapshot.videos.isEmpty()) clips.first().name.substringBeforeLast('.').take(100) else snapshot.name,
                        export = automaticExport)
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false; importing = null
                result.fold({ next -> selected = if (replace >= 0) replace else selected; edit(next) },
                    { refresh(); error("Nao foi possivel importar: ${it.message}") })
            }
        }
    }
    private fun importSticker(uri: Uri) {
        val replacing = replaceStickerId
        replaceStickerId = null
        if (busy || project.durationUs <= 0 || (replacing == null && project.stickers.size >= 24)) return
        busy = true
        preview.suspendSources()
        info.text = "Importando camada de imagem..."
        val snapshot = project
        val at = minOf(position, (snapshot.durationUs - MIN_CLIP).coerceAtLeast(0))
        io.execute {
            val result = runCatching {
                MediaImport.sticker(this, uri, at, minOf(snapshot.durationUs, at + 5 * SECOND))
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.fold({ imported ->
                    val previous = snapshot.stickers.find { it.id == replacing }
                    val sticker = if (previous == null) imported else previous.copy(uri = imported.uri, name = imported.name)
                    stickerHandles.selected = sticker.id
                    handles.selected = null
                    edit(snapshot.copy(stickers = if (previous == null) snapshot.stickers + sticker else
                        snapshot.stickers.map { if (it.id == previous.id) sticker else it }))
                    seek(sticker.startUs)
                }, { refresh(); error("Nao foi possivel importar a camada: ${it.message}") })
            }
        }
    }
    private fun importSubtitles(uri: Uri) {
        if (busy) return
        busy = true
        info.text = "Importando legendas..."
        val snapshot = project
        io.execute {
            val result = runCatching { SubtitleImport.read(this, uri, snapshot.durationUs, MAX_TEXTS - snapshot.texts.size) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.fold({ captions ->
                    edit(snapshot.copy(texts = snapshot.texts + captions))
                    info.text = "${captions.size} legendas importadas"
                }, { refresh(); error("Nao foi possivel importar as legendas: ${it.message}") })
            }
        }
    }

    private fun choose(title: String, options: List<String>, action: (Int) -> Unit) {
        choiceSheet(title, options, choose = action)
    }
    private fun colorPanel() {
        val clip = selectedClip() ?: return
        StudioPanels.grade(this, clip,
            { changed -> edit(project.mapVideo(clip.id) { changed }) },
            { pickLut(clip.id) },
            { changed -> previewEdit(project.mapVideo(clip.id) { changed }) },
            { previewEdit(project) })
    }
    private fun masksPanel() {
        val clip = selectedClip() ?: return
        StudioPanels.masks(this, clip,
            { changed -> edit(project.mapVideo(clip.id) { changed }) },
            { changed -> previewEdit(project.mapVideo(clip.id) { changed }) },
            { previewEdit(project) })
    }

    private fun videoMaskPanel(clip: VideoClip) {
        val mainIndex = project.videos.indexOfFirst { it.id == clip.id }
        val placed = project.videoTracks.asSequence().flatMap { track -> track.clips.asSequence() }
            .firstOrNull { it.clip.id == clip.id }
        val startUs = if (mainIndex >= 0) project.startOf(mainIndex) else placed?.startUs ?: 0L
        val timelineUs = (position - startUs).coerceIn(0L, clip.durationUs)
        val sourceUs = clip.timeMap.sourceAt(timelineUs)
        MaskTools.show(
            activity = this,
            initial = clip.mask,
            localTimeUs = sourceUs,
            trackingTracks = clip.trackingTracks,
            preview = { mask -> previewEdit(project.mapVideo(clip.id) { it.copy(mask = mask) }) },
            apply = { mask -> edit(project.mapVideo(clip.id) { it.copy(mask = mask) }) },
            restore = { previewEdit(project) },
            onRequestTracking = { trackingPanel() },
        )
    }

    private fun trackingPanel() {
        val clip = selectedClip() ?: return
        val mainIndex = project.videos.indexOfFirst { it.id == clip.id }
        val placed = project.videoTracks.asSequence().flatMap { track -> track.clips.asSequence() }
            .firstOrNull { it.clip.id == clip.id }
        val startUs = if (mainIndex >= 0) project.startOf(mainIndex) else placed?.startUs ?: 0L
        val timelineUs = (position - startUs).coerceIn(0L, clip.durationUs)

        TrackingTools.show(
            activity = this,
            clip = clip,
            currentTimelineUs = timelineUs,
            overlay = trackingOverlay,
            onUpdateClip = { updated ->
                edit(project.mapVideo(clip.id) { updated })
            },
            onOpenMask = {
                videoMaskPanel(clip)
            },
        )
    }

    private fun textMaskPanel(id: String) {
        val text = project.texts.firstOrNull { it.id == id } ?: return
        MaskTools.show(
            activity = this,
            initial = text.mask,
            localTimeUs = (position - text.startUs).coerceIn(0L, text.durationUs),
            preview = { mask -> previewEdit(project.copy(texts = project.texts.map {
                if (it.id == id) it.copy(mask = mask) else it
            })) },
            apply = { mask -> edit(project.copy(texts = project.texts.map {
                if (it.id == id) it.copy(mask = mask) else it
            })) },
            restore = { previewEdit(project) },
        )
    }

    private fun stickerMaskPanel(id: String) {
        val sticker = project.stickers.firstOrNull { it.id == id } ?: return
        MaskTools.show(
            activity = this,
            initial = sticker.mask,
            localTimeUs = (position - sticker.startUs).coerceIn(0L, sticker.durationUs),
            preview = { mask -> previewEdit(project.copy(stickers = project.stickers.map {
                if (it.id == id) it.copy(mask = mask) else it
            })) },
            apply = { mask -> edit(project.copy(stickers = project.stickers.map {
                if (it.id == id) it.copy(mask = mask) else it
            })) },
            restore = { previewEdit(project) },
        )
    }
    private fun keyframePanel() {
        val selectedTextId = handles.selected
        if (selectedTextId != null) {
            val text = project.texts.find { it.id == selectedTextId }
            if (text != null) {
                com.termex.replay15.editor.ui.TextKeyframeTools.show(
                    activity = this,
                    text = text,
                    timeUs = position,
                    seek = ::seek,
                    preview = { changed -> previewEdit(project.copy(texts = project.texts.map { if (it.id == changed.id) changed else it })) },
                    restore = { previewEdit(project) },
                    apply = { changed -> edit(project.copy(texts = project.texts.map { if (it.id == changed.id) changed else it })) }
                )
                return
            }
        }
        val clip = selectedClipAtCursor() ?: return
        val local = (position - project.startOf(selected)).coerceIn(0, clip.durationUs)
        val clipStart = project.startOf(selected)
        StudioPanels.keyframe(this, clip, clip.timeMap.sourceAt(local),
            { source -> seek(clipStart + clip.timeMap.timelineAt(source)) },
            { changed -> previewEdit(project.mapVideo(clip.id) { changed }) },
            { previewEdit(project) },
            project = project) { changed ->
            edit(project.copy(videos = project.videos.map { if (it.id == clip.id) changed else it }))
        }
    }

    private fun cameraPanel() {
        preview.pause()
        position = preview.positionUs
        StudioPanels.camera(this, project, position,
            seek = { time -> seek(time) },
            preview = { changed -> previewEdit(changed) },
            restore = { previewEdit(project) },
            apply = { changed -> edit(changed) }
        )
    }
    private fun pickLut(clipId: String) {
        if (busy) return
        pendingLutClipId = clipId
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 705) }
            .onFailure { pendingLutClipId = null; error("Nao foi possivel abrir o seletor de LUT") }
    }
    private fun importLut(uri: Uri, clipId: String) {
        if (busy || project.allVideos.none { it.id == clipId }) return
        busy = true; preview.suspendSources(); info.text = "Importando LUT 3D..."
        val snapshot = project
        io.execute {
            val result = runCatching {
                val directory = File(filesDir, "editor-luts").apply { check(exists() || mkdirs()) }
                val file = File(directory, "${newId()}.cube")
                try {
                    contentResolver.openInputStream(uri)?.use { source -> file.outputStream().use { dest ->
                        val buffer = ByteArray(16_384); var total = 0
                        while (true) { val n = source.read(buffer); if (n < 0) break; total += n; require(total <= 12_000_000) { "LUT maior que 12 MB" }; dest.write(buffer, 0, n) }
                    } } ?: throw IllegalArgumentException("Arquivo indisponivel")
                    CubeLut.parse(file.reader())
                    fun changed(clip: VideoClip) = if (clip.id == clipId) clip.copy(grade = clip.grade.copy(lutPath = file.absolutePath)) else clip
                    snapshot.copy(videos = snapshot.videos.map(::changed), videoTracks = snapshot.videoTracks.map { track ->
                        track.copy(clips = track.clips.map { it.copy(clip = changed(it.clip)) }) })
                } catch (e: Exception) { file.delete(); throw e }
            }
            runOnUiThread { if (!isDestroyed) { busy = false; result.fold({ edit(it) }, { refresh(); error("Falha ao importar LUT: ${it.message}") }) } }
        }
    }
    private fun markerPanel(id: String? = null) {
        if (project.durationUs <= 0) return
        if (id == null && project.markers.size >= 500) { error("Limite de 500 marcadores"); return }
        val marker = project.markers.find { it.id == id } ?: TimelineMarker(timeUs = position, name = "Marcador ${project.markers.size + 1}")
        val body = column()
        body.addView(sectionTitle("Marque seus melhores momentos", "Os marcadores ajudam a navegar e alinhar cortes."))
        val name = EditText(this).apply { setText(marker.name); isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(100)) }; body.addView(name)
        val time = numberField(body, "Posicao (segundos)", marker.timeUs / SECOND.toDouble())
        var color = marker.color
        val colors = row()
        listOf(0xFFFFC66D.toInt(), 0xFFC3A0FF.toInt(), 0xFF5BC7BC.toInt(), 0xFFFF7C8F.toInt()).forEach { value ->
            colors.addView(action("●") { color = value }.apply { setTextColor(value); textSize = 26f }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        body.addView(colors)
        lateinit var dialog: Dialog
        body.addView(action("Salvar marcador", true) {
            runCatching { marker.copy(name = name.text.toString().trim(), timeUs = seconds(time).also { require(it <= project.durationUs) }, color = color) }.fold({ changed ->
                edit(project.copy(markers = (project.markers.filterNot { it.id == marker.id } + changed).sortedBy { it.timeUs })); seek(changed.timeUs); dialog.dismiss()
            }, { name.error = "Informe um nome e um tempo dentro do projeto" })
        })
        if (id != null) body.addView(action("Excluir marcador") { edit(project.copy(markers = project.markers.filterNot { it.id == id })); dialog.dismiss() })
        project.markers.filter { it.id != id && it.timeUs <= project.durationUs }.forEach { m ->
            body.addView(action("${timeLabel(m.timeUs)}  ${m.name}") { dialog.dismiss(); seek(m.timeUs); markerPanel(m.id) })
        }
        dialog = sheet("Marcadores", body)
    }
    private fun freezeFrame() {
        val clip = selectedClip() ?: return
        if (busy || clip.image) { if (clip.image) error("Este clipe ja e uma foto"); return }
        if (project.videos.size >= 198) { error("Nao ha espaco para dividir e inserir outro clipe"); return }
        val snapshot = project; val index = selected; val at = position
        val source = clip.timeMap.sourceAt(at - snapshot.startOf(index))
        busy = true; preview.suspendSources(); info.text = "Criando quadro congelado..."
        io.execute {
            val result = runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                val bitmap = try { retriever.setDataSource(this, Uri.parse(clip.uri)); requireNotNull(retriever.getFrameAtTime(source, android.media.MediaMetadataRetriever.OPTION_CLOSEST)) }
                    finally { retriever.release() }
                val folder = File(filesDir, "editor-frames").apply { check(exists() || mkdirs()) }
                val file = File(folder, "${newId()}.png")
                val key = clip.transformAt(source)
                val still = try {
                    file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                    clip.copy(id = newId(), uri = Uri.fromFile(file).toString(), name = "Quadro congelado", sourceUs = 3 * SECOND,
                        width = bitmap.width, height = bitmap.height, image = true, inUs = 0, outUs = 3 * SECOND,
                        speed = 1f, speedCurve = emptyList(), volume = 0f, keyframes = emptyList(), motion = ClipMotion.NONE,
                        zoom = key.zoom, offsetX = key.x, offsetY = key.y, fineRotation = key.rotation, opacity = key.opacity,
                        transitionIn = ClipTransition.NONE, transitionOut = ClipTransition.NONE)
                } finally { bitmap.recycle() }
                val split = snapshot.split(index, at)
                val insertion = if (at <= snapshot.startOf(index)) index else index + 1
                split.copy(videos = split.videos.toMutableList().apply { add(insertion, still) })
            }
            runOnUiThread { if (!isDestroyed) { busy = false; result.fold({ edit(it) }, { refresh(); error("Nao foi possivel congelar: ${it.message}") }) } }
        }
    }
    private fun selectedClip(): VideoClip? {
        if (selected !in project.videos.indices) selected = project.indexAt(position)
        timeline.selected = selected
        videoHandles.mainClipIndex = selected
        return project.videos.getOrNull(selected).also { if (it == null) Toast.makeText(this, "Adicione um video ou foto", Toast.LENGTH_SHORT).show() }
    }

    private fun selectedClipAtCursor(): VideoClip? {
        selected = project.indexAt(position)
        timeline.selected = selected
        videoHandles.mainClipIndex = selected
        return selectedClip()
    }

    private fun syncSelectedClipToPosition() {
        if (project.videos.isEmpty()) {
            selected = -1
            return
        }
        if (selected !in project.videos.indices) {
            timeline.selected = -1
            videoHandles.mainClipIndex = -1
            return
        }
        val selectedStart = project.startOf(selected)
        val selectedEnd = selectedStart + project.videos[selected].durationUs
        if (position !in selectedStart until selectedEnd) {
            selected = -1
        }
        timeline.selected = selected
        videoHandles.mainClipIndex = selected
    }

    private fun editTools() {
        val clip = selectedClip() ?: return
        val body = column().apply { setPadding(dp(4), 0, dp(4), dp(4)) }
        body.addView(label(clip.name, 15f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        body.addView(label("${timeLabel(clip.durationUs)}  •  Clipe ${selected + 1} de ${project.videos.size}", 12f, EditorStyle.MUTED).apply {
            setPadding(dp(8), 0, dp(8), dp(12))
        })
        lateinit var dialog: Dialog
        fun runAndClose(action: () -> Unit) { dialog.dismiss(); action() }
        body.addView(editorToolCard("Cortar", "Ajuste o inicio e o fim com precisao", accent = true) {
            runAndClose { trimPanel() }
        }, LinearLayout.LayoutParams(-1, dp(82)).apply { bottomMargin = dp(10) })

        val grid = GridLayout(this).apply { columnCount = 3; alignmentMode = GridLayout.ALIGN_BOUNDS }
        fun addTool(name: String, subtitle: String, danger: Boolean = false, action: () -> Unit) {
            grid.addView(editorToolCard(name, subtitle, danger = danger) { runAndClose(action) }, GridLayout.LayoutParams().apply {
                width = 0; height = dp(84); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        addTool("Dividir", "No cursor") { splitAtCursor() }
        addTool("✨ Auto Edit", "Edicao inteligente") { autoEditPanel.open() }
        addTool("Velocidade", "Constante e curvas") { speedPanel() }
        addTool("Volume", "Som do clipe") { volumePanel() }
        addTool("Extrair audio", "Do video") { extractAudio() }
        addTool("Ajustar", "Cor e luz") { adjustmentsPanel() }
        addTool("Filtros", "Looks e cores") { filters() }
        addTool("Cor", "Curvas e LUT 3D") { colorPanel() }
        addTool("Keyframe", "Animar no tempo") { keyframePanel() }
        addTool("Câmera 3D", "Perspectiva e visão") { cameraPanel() }
        addTool("Animacoes", "Entrada, saida e loop") { animationTools.open(clip.id) }
        addTool("Mascaras", "Forma e chroma") { masksPanel() }
        addTool("Rastreamento", "Seguir objeto") { trackingPanel() }
        addTool("Congelar", "Foto do quadro") { freezeFrame() }
        addTool("Transformar", "Zoom e posicao") { transformPanel() }
        addTool("Transicao", "Entrada e saida") { transitionPanel() }
        addTool("Recorte", "Enquadramento") { cropPanel() }
        addTool("Girar", "90 graus") { edit(project.changeVideo(selected) { it.copy(rotation = (it.rotation + 90) % 360) }) }
        addTool("Espelhar", "Horizontal") { edit(project.changeVideo(selected) { it.copy(flip = !it.flip) }) }
        addTool("Duplicar", "Criar copia") { duplicateSelected() }
        addTool("Copiar estilo", "Efeitos do clipe") { copyClipStyle() }
        if (copiedClipStyle != null) addTool("Colar estilo", "Aplicar efeitos") { pasteClipStyle() }
        addTool("Mover", "Ordem do clipe") { movePanel() }
        addTool("Substituir", "Trocar arquivo") { pick(false, selected) }
        body.addView(grid)
        body.addView(editorToolCard("Excluir", "Remover este clipe da timeline", danger = true) {
            runAndClose { confirmDeleteSelected() }
        }, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(10) })
        dialog = sheet("Editar clipe", body)
    }

    private fun splitAtCursor() {
        val splitIndex = project.indexAt(position)
        val next = project.splitAt(position)
        if (next == project) {
            error("Posicione o cursor dentro do clipe, longe das bordas.")
        } else {
            selected = (splitIndex + 1).coerceAtMost(next.videos.lastIndex)
            edit(next)
            Toast.makeText(this, "Parte cortada selecionada. Arraste-a na timeline ou use Editar > Mover.", Toast.LENGTH_LONG).show()
        }
    }

    private fun duplicateSelected() {
        if (project.videos.size >= 200) { error("Limite de 200 clipes por projeto"); return }
        val list = project.videos.toMutableList()
        list.add(selected + 1, list[selected].copy(id = newId()))
        selected += 1
        edit(project.copy(videos = list))
    }

    private fun copyClipStyle() {
        copiedClipStyle = selectedClip()
        Toast.makeText(this, "Estilo do clipe copiado", Toast.LENGTH_SHORT).show()
    }

    private fun pasteClipStyle() {
        val source = copiedClipStyle ?: return
        selectedClip() ?: return
        edit(project.changeVideo(selected) { target -> target.copy(
            volume = source.volume, rotation = source.rotation, flip = source.flip, crop = source.crop,
            filter = source.filter, brightness = source.brightness, contrast = source.contrast,
            saturation = source.saturation, hue = source.hue, lightness = source.lightness,
            temperature = source.temperature, filterStrength = source.filterStrength,
            zoom = source.zoom, offsetX = source.offsetX, offsetY = source.offsetY,
            fineRotation = source.fineRotation, opacity = source.opacity, blur = source.blur,
            motion = source.motion, transitionIn = source.transitionIn, transitionOut = source.transitionOut,
            transitionDurationUs = source.transitionDurationUs,
            audioFadeInUs = source.audioFadeInUs, audioFadeOutUs = source.audioFadeOutUs,
            grade = source.grade,
        ) })
    }

    private fun movePanel() {
        val targets = project.videos.indices.filter { it != selected }
        if (targets.isEmpty()) { Toast.makeText(this, "Este e o unico clipe", Toast.LENGTH_SHORT).show(); return }
        val options = targets.map { target ->
            val clipName = project.videos[target].name.take(34)
            "Posicao ${target + 1} - $clipName"
        }
        choose("Mover clipe para", options) { index ->
            val target = targets[index]; val next = project.moveVideo(selected, target); selected = target; edit(next)
        }
    }

    private fun confirmDeleteSelected() {
        val clip = project.videos.getOrNull(selected) ?: return
        AlertDialog.Builder(this).setTitle("Excluir clipe?").setMessage("${clip.name} sera removido somente deste projeto.")
            .setPositiveButton("Excluir") { _, _ -> edit(project.copy(videos = project.videos.filterIndexed { i, _ -> i != selected })) }
            .setNegativeButton("Cancelar", null).show()
    }
    private fun numberField(parent: LinearLayout, title: String, value: Double): EditText {
        parent.addView(label(title, color = EditorStyle.MUTED))
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(java.lang.String.format(java.util.Locale.ROOT, "%.3f", value)); selectAll(); parent.addView(this)
        }
    }
    private fun seconds(field: EditText) = ((field.text.toString().replace(',', '.').toDoubleOrNull()
        ?: throw IllegalArgumentException("Digite um tempo valido")) * SECOND).toLong().also { require(it >= 0) }
    private fun trimPanel() {
        val clip = selectedClip() ?: return
        val body = column().apply { setPadding(dp(4), 0, dp(4), dp(4)) }
        body.addView(label("Arraste os controles ou use a linha branca da timeline para marcar o corte.", 12f, EditorStyle.MUTED))
        val values = row().apply { setPadding(dp(4), dp(10), dp(4), dp(4)) }
        val startValue = label(timeLabel(clip.inUs), 15f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val durationValue = label(timeLabel(clip.durationUs), 13f, EditorStyle.ACCENT).apply { gravity = Gravity.CENTER }
        val endValue = label(timeLabel(clip.outUs), 15f).apply { gravity = Gravity.END; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        values.addView(startValue, LinearLayout.LayoutParams(0, -2, 1f)); values.addView(durationValue, LinearLayout.LayoutParams(0, -2, 1f))
        values.addView(endValue, LinearLayout.LayoutParams(0, -2, 1f)); body.addView(values)
        var startUs = clip.inUs
        var endUs = clip.outUs
        val rangeMax = 10_000
        fun progress(us: Long) = ((us.toDouble() / clip.sourceUs.coerceAtLeast(1)) * rangeMax).toInt().coerceIn(0, rangeMax)
        fun source(progress: Int) = (clip.sourceUs * (progress.toDouble() / rangeMax)).toLong()
        fun updateValues() {
            startValue.text = timeLabel(startUs); endValue.text = timeLabel(endUs)
            durationValue.text = timeLabel(clip.copy(inUs = startUs, outUs = endUs).durationUs)
        }
        body.addView(label("INICIO", 10f, EditorStyle.MUTED))
        val startSlider = SeekBar(this).apply { max = rangeMax; this.progress = progress(startUs); minimumHeight = dp(52) }
        body.addView(startSlider)
        body.addView(label("FIM", 10f, EditorStyle.MUTED))
        val endSlider = SeekBar(this).apply { max = rangeMax; this.progress = progress(endUs); minimumHeight = dp(52) }
        body.addView(endSlider)
        val fullTime = clip.copy(inUs = 0, outUs = clip.sourceUs).timeMap
        val minimumDuration = minOf(MIN_CLIP, clip.durationUs)
        fun latestStart() = fullTime.sourceAt((fullTime.timelineAt(endUs) - minimumDuration).coerceAtLeast(0)).coerceAtMost(endUs - 1)
        fun earliestEnd() = fullTime.sourceAt(fullTime.timelineAt(startUs) + minimumDuration).coerceAtLeast(startUs + 1)
        startSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                startUs = source(value).coerceIn(0, latestStart()); updateValues()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) { bar.progress = progress(startUs) }
        })
        endSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                endUs = source(value).coerceIn(earliestEnd(), clip.sourceUs); updateValues()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) { bar.progress = progress(endUs) }
        })
        val clipStart = project.startOf(selected)
        val cursorSource = clip.timeMap.sourceAt(position - clipStart)
        val cursorActions = row()
        cursorActions.addView(action("Inicio no cursor") {
            startUs = cursorSource.coerceAtMost(latestStart()); startSlider.progress = progress(startUs); updateValues()
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(4) })
        cursorActions.addView(action("Fim no cursor") {
            endUs = cursorSource.coerceAtLeast(earliestEnd()); endSlider.progress = progress(endUs); updateValues()
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(4) })
        body.addView(cursorActions)
        lateinit var dialog: Dialog
        val footer = row().apply { setPadding(0, dp(12), 0, 0) }
        footer.addView(action("Restaurar") {
            startUs = 0; endUs = clip.sourceUs; startSlider.progress = 0; endSlider.progress = rangeMax; updateValues()
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(4) })
        footer.addView(action("Aplicar corte", true) {
            val changed = clip.copy(inUs = startUs, outUs = endUs)
            edit(project.changeVideo(selected) { changed }); seek(project.startOf(selected)); dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(56), 1.35f).apply { marginStart = dp(4) })
        body.addView(footer)
        dialog = sheet("Cortar clipe", body)
    }
    private fun speedPanel() {
        val clip = selectedClip() ?: return
        val clipIndex = project.videos.indexOfFirst { it.id == clip.id }
        if (clipIndex < 0) return
        val originalProject = project
        val originalPosition = position
        val sourceAnchor = clip.timeMap.sourceAt((position - originalProject.startOf(clipIndex)).coerceIn(0L, clip.durationUs))
        fun candidate(changed: VideoClip): Project {
            val videos = originalProject.videos.map { if (it.id == clip.id) changed else it }
            val withoutTransitions = originalProject.copy(videos = videos, transitions = emptyList())
            return originalProject.transitions.fold(withoutTransitions) { current, transition ->
                if (videos.zipWithNext().any { (left, right) -> left.id == transition.leftClipId && right.id == transition.rightClipId })
                    current.withTransition(transition) else current
            }
        }
        fun anchoredPosition(snapshot: Project, changed: VideoClip): Long =
            (snapshot.startOf(clipIndex) + changed.timeMap.timelineAt(sourceAnchor))
                .coerceIn(0L, snapshot.durationUs)
        SpeedTools.show(this, clip,
            preview = { changed ->
                runCatching {
                    val draft = candidate(changed)
                    previewEdit(draft)
                    timeline.project = draft; timeline.selected = clipIndex
                    position = anchoredPosition(draft, changed)
                    preview.seek(position); updatePosition()
                }
            },
            restore = {
                previewDraft = null
                preview.update(originalProject)
                timeline.project = originalProject; timeline.selected = clipIndex
                position = originalPosition.coerceIn(0L, originalProject.durationUs)
                preview.seek(position); updatePosition()
            },
        ) { changed ->
            val next = candidate(changed)
            position = anchoredPosition(next, changed)
            if (isEditorDebuggable()) android.util.Log.d("ReclySpeed", "SPEED_CHANGE clip=${clip.id} old=${clip.speed} new=${changed.speed} curve=${changed.speedCurve.size} sourceDurationUs=${clip.outUs-clip.inUs} timelineDurationUs=${changed.durationUs}")
            edit(next)
            seek(position)
        }
    }
    private fun volumePanel() {
        val clip = selectedClip() ?: return
        val body = column(); var volume = (clip.volume * 100).toInt()
        var fadeInMs = (clip.audioFadeInUs / 1000).toInt()
        var fadeOutMs = (clip.audioFadeOutUs / 1000).toInt()
        slider(body, "Volume (%)", volume, 200) { volume = it }
        slider(body, "Fade-in do audio (ms)", fadeInMs, 10_000) { fadeInMs = it }
        slider(body, "Fade-out do audio (ms)", fadeOutMs, 10_000) { fadeOutMs = it }
        body.addView(label("Os fades sao ajustados automaticamente se forem maiores que metade do clipe. Acima de 100% o volume pode distorcer.", color = EditorStyle.MUTED))
        lateinit var dialog: Dialog
        body.addView(action("Aplicar", true) {
            edit(project.changeVideo(selected) { it.copy(
                volume = volume / 100f, audioFadeInUs = fadeInMs * 1000L, audioFadeOutUs = fadeOutMs * 1000L,
            ) }); dialog.dismiss()
        })
        dialog = sheet("Volume do clipe", body)
    }

    private fun transformPanel() {
        val clip = selectedClip() ?: return
        val sourceUs = RenderPlan.sourceTime(clip, project.startOf(selected), position)
        if (clip.keyframes.size >= 200 && clip.keyframes.none { it.sourceUs == sourceUs }) {
            error("Limite de 200 keyframes. Posicione o cursor sobre um keyframe existente.")
            return
        }
        val currentTransform = clip.transformAt(sourceUs).copy(sourceUs = sourceUs)
        val body = column().apply { setPadding(dp(3), 0, dp(3), dp(6)) }
        body.addView(label("TRANSFORMACAO", 11f, EditorStyle.ACCENT).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        var zoom = (currentTransform.zoom * 100).toInt()
        var horizontal = (currentTransform.x * 100).toInt()
        var vertical = (currentTransform.y * 100).toInt()
        var angle = currentTransform.rotation.toInt()
        var opacity = (currentTransform.opacity * 100).toInt()
        var blur = (clip.blur * 10).toInt()
        var motion = clip.motion
        fun transformed(current: VideoClip, reset: Boolean = false): VideoClip {
            val value = if (reset) currentTransform.copy(zoom = 1f, x = 0f, y = 0f, rotation = 0f, opacity = 1f)
            else currentTransform.copy(zoom = zoom / 100f, x = horizontal / 100f, y = vertical / 100f,
                rotation = angle.toFloat(), opacity = opacity / 100f)
            return if (current.keyframes.isEmpty()) current.copy(
                zoom = value.zoom, offsetX = value.x, offsetY = value.y, fineRotation = value.rotation,
                opacity = value.opacity, blur = if (reset) 0f else blur / 10f, motion = if (reset) ClipMotion.NONE else motion,
            ) else current.copy(
                keyframes = (current.keyframes.filter { it.sourceUs != sourceUs } + value).sortedBy { it.sourceUs },
                blur = if (reset) 0f else blur / 10f, motion = if (reset) ClipMotion.NONE else motion,
            )
        }
        fun draft() = previewEdit(project.mapVideo(clip.id) { transformed(it) })
        slider(body, "Zoom (%)", zoom, 400, minimum = 25) { zoom = it; draft() }
        slider(body, "Posicao horizontal", horizontal + 50, 100) { horizontal = it - 50; draft() }
        slider(body, "Posicao vertical", vertical + 50, 100) { vertical = it - 50; draft() }
        slider(body, "Rotacao livre", angle + 180, 360) { angle = it - 180; draft() }
        slider(body, "Opacidade (%)", opacity, 100, minimum = 5) { opacity = it; draft() }
        slider(body, "Desfoque", blur, 250) { blur = it }
        lateinit var motionButton: Button
        motionButton = action("Movimento: ${motion.label}") {
            choose("Movimento automatico", ClipMotion.entries.map { it.label }) { index ->
                motion = ClipMotion.entries[index]; motionButton.text = "Movimento: ${motion.label}"; draft()
            }
        }
        body.addView(motionButton, LinearLayout.LayoutParams(-1, dp(56)))
        body.addView(label("Movimentos automaticos criam efeito Ken Burns e panoramica, especialmente util em fotos.", 11f, EditorStyle.MUTED))
        lateinit var dialog: Dialog
        val footer = row()
        footer.addView(action("Restaurar") {
            edit(project.changeVideo(selected) { transformed(it, reset = true) }); dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(4) })
        footer.addView(action("Aplicar", true) {
            edit(project.changeVideo(selected) { transformed(it) }); dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(56), 1.2f).apply { marginStart = dp(4) })
        body.addView(footer)
        dialog = sheet("Transformar clipe", body)
        dialog.setOnDismissListener { previewEdit(project) }
    }

    private fun transitionPanel() {
        val clip = selectedClip() ?: return
        val body = column()
        var input = clip.transitionIn
        var output = clip.transitionOut
        var durationMs = (clip.transitionDurationUs / 1000).toInt()
        lateinit var inputButton: Button
        lateinit var outputButton: Button
        inputButton = action("Entrada: ${input.label}") {
            choose("Transicao de entrada", ClipTransition.entries.map { it.label }) { index ->
                input = ClipTransition.entries[index]; inputButton.text = "Entrada: ${input.label}"
            }
        }
        outputButton = action("Saida: ${output.label}") {
            choose("Transicao de saida", ClipTransition.entries.map { it.label }) { index ->
                output = ClipTransition.entries[index]; outputButton.text = "Saida: ${output.label}"
            }
        }
        body.addView(inputButton, LinearLayout.LayoutParams(-1, dp(56)))
        body.addView(outputButton, LinearLayout.LayoutParams(-1, dp(56)))
        slider(body, "Duracao (ms)", durationMs, 2000, minimum = 100) { durationMs = it }
        body.addView(label("A duracao e limitada automaticamente em clipes curtos para preservar o conteudo.", 11f, EditorStyle.MUTED))
        lateinit var dialog: Dialog
        body.addView(action("Aplicar transicoes", true) {
            edit(project.changeVideo(selected) { it.copy(
                transitionIn = input, transitionOut = output, transitionDurationUs = durationMs * 1000L,
            ) }); dialog.dismiss()
        })
        body.addView(action("Aplicar a todos os clipes") {
            edit(project.copy(videos = project.videos.map { it.copy(
                transitionIn = input, transitionOut = output, transitionDurationUs = durationMs * 1000L,
            ) })); dialog.dismiss()
        })
        dialog = sheet("Transicoes", body)
    }
    private fun extractAudio() {
        val clip = selectedClip() ?: return
        if (clip.image) { error("Fotos nao possuem audio para extrair."); return }
        if (clip.speed != 1f || clip.speedCurve.isNotEmpty()) { error("Para manter o audio sincronizado, extraia antes de alterar a velocidade do clipe."); return }
        if (project.audio.size >= 8) { error("Limite de 8 faixas de audio"); return }
        val track = AudioClip(
            uri = clip.uri, name = "Audio - ${clip.name}", sourceUs = clip.sourceUs,
            startUs = project.startOf(selected), inUs = clip.inUs, outUs = clip.outUs,
        )
        val withTrack = project.copy(audio = project.audio + track)
        AlertDialog.Builder(this).setTitle("Audio extraido")
            .setMessage("Deseja silenciar o som original deste clipe para evitar audio duplicado?")
            .setPositiveButton("Extrair e silenciar") { _, _ -> edit(withTrack.changeVideo(selected) { it.copy(volume = 0f) }) }
            .setNegativeButton("Manter os dois") { _, _ -> edit(withTrack) }
            .setNeutralButton("Cancelar", null).show()
    }
    private fun cropPanel() {
        val clip = selectedClip() ?: return
        val body = column()
        body.addView(label("Recorte as margens em %. Isso remove pixels, sem alterar o original."))
        var left = (clip.crop.left * 100).toInt(); var right = ((1 - clip.crop.right) * 100).toInt()
        var top = (clip.crop.top * 100).toInt(); var bottom = ((1 - clip.crop.bottom) * 100).toInt()
        slider(body, "Esquerda", left, 90) { left = it }; slider(body, "Direita", right, 90) { right = it }
        slider(body, "Acima", top, 90) { top = it }; slider(body, "Abaixo", bottom, 90) { bottom = it }
        lateinit var dialog: Dialog
        body.addView(action("Aplicar recorte", true) {
            runCatching { CropRect(left / 100f, top / 100f, 1 - right / 100f, 1 - bottom / 100f) }.fold(
                { crop -> edit(project.changeVideo(selected) { it.copy(crop = crop) }); dialog.dismiss() },
                { error("Mantenha pelo menos 5% da largura e altura.") })
        })
        body.addView(action("Restaurar imagem inteira") { edit(project.changeVideo(selected) { it.copy(crop = CropRect()) }); dialog.dismiss() })
        dialog = sheet("Recortar imagem", body)
    }
    private fun filters() {
        val clip = selectedClip() ?: return
        val clipStartUs = if (selected in project.videos.indices) project.startOf(selected) else 0L
        val timeInClipUs = (position - clipStartUs).coerceIn(0L, clip.durationUs)
        val sourceTimeUs = clip.timeMap.sourceAt(timeInClipUs)
        FilterGallery.show(this, clip,
            sessionTimestampUs = sourceTimeUs,
            preview = { filter, strength -> previewEdit(project.mapVideo(clip.id) { it.copy(filter = filter, filterStrength = strength) }) },
            restore = { previewEdit(project) }) { filter, strength, all ->
            edit(project.copy(videos = project.videos.map { if (all || it.id == clip.id) it.copy(filter = filter, filterStrength = strength) else it }))
        }
    }
    private fun adjustmentsPanel() {
        val clip = selectedClip() ?: return
        val body = column().apply { setPadding(dp(3), 0, dp(3), dp(6)) }
        body.addView(label("CORRECAO DE COR", 11f, EditorStyle.ACCENT).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        body.addView(label("Os ajustes aparecem na previa e no arquivo exportado.", 12f, EditorStyle.MUTED))
        var brightness = (clip.brightness * 100).toInt()
        var contrast = (clip.contrast * 100).toInt()
        var saturation = clip.saturation.toInt()
        var hue = clip.hue.toInt()
        var lightness = clip.lightness.toInt()
        var temperature = (clip.temperature * 100).toInt()
        fun adjusted(current: VideoClip) = current.copy(
            brightness = brightness / 100f, contrast = contrast / 100f,
            saturation = saturation.toFloat(), hue = hue.toFloat(), lightness = lightness.toFloat(),
            temperature = temperature / 100f,
        )
        fun draft() = previewEdit(project.mapVideo(clip.id) { adjusted(it) })
        slider(body, "Brilho", brightness + 100, 200) { brightness = it - 100; draft() }
        slider(body, "Contraste", contrast + 90, 180) { contrast = it - 90; draft() }
        slider(body, "Saturacao", saturation + 100, 200) { saturation = it - 100; draft() }
        slider(body, "Matiz", hue + 180, 360) { hue = it - 180; draft() }
        slider(body, "Luminosidade", lightness + 100, 200) { lightness = it - 100; draft() }
        slider(body, "Temperatura", temperature + 100, 200) { temperature = it - 100; draft() }
        lateinit var dialog: Dialog
        val footer = row()
        footer.addView(action("Restaurar") {
            edit(project.changeVideo(selected) { it.copy(brightness = 0f, contrast = 0f, saturation = 0f, hue = 0f, lightness = 0f, temperature = 0f) })
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(4) })
        footer.addView(action("Aplicar ajustes", true) {
            edit(project.changeVideo(selected) { adjusted(it) })
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(56), 1.35f).apply { marginStart = dp(4) })
        body.addView(footer)
        body.addView(action("Aplicar em todos os clipes") {
            edit(project.copy(videos = project.videos.map { video -> video.copy(
                brightness = brightness / 100f, contrast = contrast / 100f,
                saturation = saturation.toFloat(), hue = hue.toFloat(), lightness = lightness.toFloat(),
                temperature = temperature / 100f,
            ) }))
            dialog.dismiss()
        })
        dialog = sheet("Ajustar imagem", body)
        dialog.setOnDismissListener { previewEdit(project) }
    }
    private fun aspectPanel() {
        StudioPanels.canvas(this, project, ::edit)
    }

    private fun audioPanel(id: String? = null) {
        if (id == null) {
            choose("Faixas de audio", listOf("+ Importar musica ou audio", "● Gravar narracao") + project.audio.mapIndexed { index, track ->
                "${index + 1}. ${track.name.take(42)}"
            }) { index ->
                if (index == 0) {
                    if (project.audio.size >= 8) error("Limite de 8 faixas de audio") else pick(true)
                } else if (index == 1) voiceoverPanel() else audioPanel(project.audio[index - 2].id)
            }
            return
        }
        val audio = project.audio.find { it.id == id } ?: return
        val body = column(); body.addView(label(audio.name))
        val start = numberField(body, "Posicao na timeline (s)", audio.startUs / SECOND.toDouble())
        val sourceStart = numberField(body, "Inicio no arquivo (s)", audio.inUs / SECOND.toDouble())
        val sourceEnd = numberField(body, "Fim no arquivo (s)", audio.outUs / SECOND.toDouble())
        var volume = (audio.volume * 100).toInt()
        var fadeInMs = (audio.fadeInUs / 1000).toInt()
        var fadeOutMs = (audio.fadeOutUs / 1000).toInt()
        slider(body, "Volume (%)", volume, 200) { volume = it }
        slider(body, "Fade-in (ms)", fadeInMs, 10_000) { fadeInMs = it }
        slider(body, "Fade-out (ms)", fadeOutMs, 10_000) { fadeOutMs = it }
        body.addView(label("Uma faixa de musica adicional, misturada ao som dos videos. O audio termina junto com a timeline.", color = EditorStyle.MUTED))
        lateinit var dialog: Dialog
        body.addView(action("Aplicar", true) {
            runCatching {
                require(seconds(start) < project.durationUs)
                audio.copy(startUs = seconds(start), inUs = seconds(sourceStart), outUs = seconds(sourceEnd),
                    volume = volume / 100f, fadeInUs = fadeInMs * 1000L, fadeOutUs = fadeOutMs * 1000L)
            }.fold({ changed ->
                edit(project.copy(audio = project.audio.map { if (it.id == id) changed else it })); dialog.dismiss()
            }, { error("Intervalo de audio invalido") })
        })
        val actions = row()
        actions.addView(action("Duplicar faixa") {
            if (project.audio.size >= 8) error("Limite de 8 faixas de audio") else {
                val copy = audio.copy(id = newId(), startUs = (audio.startUs + SECOND).coerceAtMost((project.durationUs - 1).coerceAtLeast(0)))
                edit(project.copy(audio = project.audio + copy)); dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(3) })
        actions.addView(action("Remover faixa") {
            edit(project.copy(audio = project.audio.filterNot { it.id == id })); dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(3) })
        body.addView(actions)
        if (project.audio.size < 8) body.addView(action("+ Adicionar outra faixa") { dialog.dismiss(); pick(true) })
        dialog = sheet("Editar faixa de audio", body)
    }

    private fun voiceoverPanel() {
        if (project.durationUs <= 0) { error("Adicione um video antes da narracao"); return }
        if (project.audio.size >= 8) { error("Limite de 8 faixas de audio"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 703)
            return
        }
        val body = column()
        body.addView(label("A narracao comeca na posicao atual da timeline: ${timeLabel(position)}", 14f))
        body.addView(label("Use fones para evitar que o som da previa volte pelo microfone. A gravacao para automaticamente no fim do projeto.", 12f, EditorStyle.MUTED))
        lateinit var dialog: Dialog
        body.addView(action("Iniciar narracao", true) { dialog.dismiss(); startVoiceover() })
        dialog = sheet("Gravar narracao", body)
    }

    @Suppress("DEPRECATION")
    private fun startVoiceover() {
        if (voiceRecorder != null || project.audio.size >= 8) return
        val folder = File(filesDir, "editor-voice").apply { mkdirs() }
        val file = File(folder, "narracao_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        val started = runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(48_000)
            recorder.setAudioEncodingBitRate(192_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
        }
        if (started.isFailure) {
            recorder.release(); file.delete(); error("Nao foi possivel iniciar o microfone: ${started.exceptionOrNull()?.message}"); return
        }
        voiceRecorder = recorder
        voiceFile = file
        voiceStartUs = position.coerceAtMost((project.durationUs - 1).coerceAtLeast(0))
        voiceStartedAt = SystemClock.elapsedRealtime()
        seek(voiceStartUs)
        if (!preview.isPlaying) preview.toggle(project.durationUs)
        main.postDelayed(voiceLimit, ((project.durationUs - voiceStartUs) / 1000).coerceAtLeast(300))
        voiceDialog = AlertDialog.Builder(this).setTitle("Gravando narracao")
            .setMessage("Fale agora. A previa esta acompanhando a timeline.")
            .setPositiveButton("Parar e adicionar", null)
            .setNegativeButton("Cancelar", null)
            .setCancelable(false).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { finishVoiceover(true) }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finishVoiceover(false) }
                }
                dialog.show()
            }
    }

    private fun finishVoiceover(save: Boolean) {
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        main.removeCallbacks(voiceLimit)
        val file = voiceFile
        voiceFile = null
        val elapsedUs = (SystemClock.elapsedRealtime() - voiceStartedAt) * 1000
        val stopped = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        preview.pause()
        voiceDialog?.dismiss()
        voiceDialog = null
        if (!save || !stopped || file == null || elapsedUs < 300_000) {
            file?.delete()
            if (save) Toast.makeText(this, "Narracao curta demais", Toast.LENGTH_SHORT).show()
            return
        }
        val start = voiceStartUs
        io.execute {
            val result = runCatching { MediaImport.audio(this, Uri.fromFile(file), start).copy(name = "Narracao") }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold({ track ->
                    if (project.audio.size < 8) edit(project.copy(audio = project.audio + track)) else file.delete()
                }, { file.delete(); error("Nao foi possivel adicionar a narracao: ${it.message}") })
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 703) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) voiceoverPanel()
            else error("Permita o microfone para gravar narracao.")
        }
    }

    private fun stickerMenu() {
        if (project.durationUs <= 0) { error("Adicione um video antes da camada de imagem"); return }
        if (project.stickers.isEmpty()) { pickSticker(); return }
        choose("Camadas de imagem", listOf("+ Adicionar imagem / sticker") + project.stickers.mapIndexed { index, sticker ->
            "${index + 1}. ${sticker.name.take(42)}"
        }) { index -> if (index == 0) pickSticker() else stickerPanel(project.stickers[index - 1].id) }
    }

    private fun stickerPanel(id: String) {
        val sticker = project.stickers.find { it.id == id } ?: return
        stickerHandles.selected = id
        handles.selected = null
        val body = column().apply { setPadding(dp(3), 0, dp(3), dp(6)) }
        body.addView(label(sticker.name, 15f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        body.addView(label("CAMADA VISUAL", 11f, EditorStyle.ACCENT))
        var size = (sticker.size * 100).toInt()
        var opacity = (sticker.opacity * 100).toInt()
        var rotation = sticker.rotation.toInt()
        var horizontal = (sticker.x * 100).toInt()
        var vertical = (sticker.y * 100).toInt()
        var animation = sticker.animation
        slider(body, "Tamanho (%)", size, 150, minimum = 3) { size = it }
        slider(body, "Opacidade (%)", opacity, 100, minimum = 5) { opacity = it }
        slider(body, "Rotacao", rotation + 180, 360) { rotation = it - 180 }
        slider(body, "Horizontal (%)", horizontal, 100) { horizontal = it }
        slider(body, "Vertical (%)", vertical, 100) { vertical = it }
        val flip = CheckBox(this).apply { this.text = "Espelhar horizontalmente"; isChecked = sticker.flip }
        body.addView(flip)
        lateinit var animationButton: Button
        animationButton = action("Animacao: ${animation.label}") {
            choose("Animacao da camada", TextAnimation.entries.map { it.label }) { index ->
                animation = TextAnimation.entries[index]
                animationButton.text = "Animacao: ${animation.label}"
            }
        }
        body.addView(animationButton, LinearLayout.LayoutParams(-1, dp(54)))
        val start = numberField(body, "Inicio na timeline (s)", sticker.startUs / SECOND.toDouble())
        val end = numberField(body, "Fim na timeline (s)", sticker.endUs / SECOND.toDouble())
        body.addView(label("Arraste na previa para posicionar, pince para redimensionar e toque duas vezes para editar.", 11f, EditorStyle.MUTED))
        lateinit var dialog: Dialog
        body.addView(action("Aplicar camada", true) {
            runCatching {
                val startUs = seconds(start)
                val endUs = seconds(end)
                require(endUs <= project.durationUs)
                sticker.copy(
                    startUs = startUs, endUs = endUs, x = horizontal / 100f, y = vertical / 100f,
                    size = size / 100f, rotation = rotation.toFloat(), opacity = opacity / 100f,
                    flip = flip.isChecked, animation = animation,
                )
            }.fold({ changed ->
                edit(project.copy(stickers = project.stickers.map { if (it.id == id) changed else it }))
                seek(changed.startUs)
                dialog.dismiss()
            }, { error("Confira o intervalo e os valores da camada.") })
        })
        body.addView(action("Substituir imagem") { dialog.dismiss(); pickSticker(id) })
        val order = row()
        order.addView(action("Enviar para tras") {
            edit(project.copy(stickers = listOf(sticker) + project.stickers.filterNot { it.id == id })); dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(3) })
        order.addView(action("Trazer para frente") {
            edit(project.copy(stickers = project.stickers.filterNot { it.id == id } + sticker)); dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(3) })
        body.addView(order)
        val actions = row()
        actions.addView(action("Duplicar") {
            if (project.stickers.size >= 24) error("Limite de 24 camadas") else {
                val copy = sticker.copy(id = newId(), x = (sticker.x + .04f).coerceAtMost(1f), y = (sticker.y + .04f).coerceAtMost(1f))
                stickerHandles.selected = copy.id
                edit(project.copy(stickers = project.stickers + copy)); dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(3) })
        actions.addView(action("Excluir") {
            edit(project.copy(stickers = project.stickers.filterNot { it.id == id })); dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(3) })
        body.addView(actions)
        dialog = sheet("Editar camada", body)
    }

    private fun subtitleMenu() {
        val body = column()
        body.addView(label("LEGENDAS", 11f, EditorStyle.ACCENT).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        body.addView(label("Gere legendas com timestamps por palavra ou importe um SRT. As legendas viram camadas de texto editáveis.", 12f, EditorStyle.MUTED))
        body.addView(label("A transcrição usa Gemini 3.5 Transcribe em DEBUG; a correção contextual é opcional.", 11f, EditorStyle.MUTED))
        lateinit var dialog: Dialog
        val languageNames = listOf("Português Brasileiro", "Inglês", "Automático (PT-BR/Inglês)")
        val languageCodes = listOf("pt-BR", "en-US", "auto")
        body.addView(action("Idioma: ${languageNames[languageCodes.indexOf(project.captionLanguage).coerceAtLeast(0)]}") {
            choose("Idioma das legendas", languageNames) { index ->
                edit(project.copy(captionLanguage = languageCodes[index]))
                dialog.dismiss()
            }
        })
        body.addView(action("Gerar legendas automáticas", true) { dialog.dismiss(); automaticCaptionsDialog() })
        body.addView(action("Importar arquivo SRT", true) { dialog.dismiss(); pickSubtitles() })
        body.addView(action("Fonte global das legendas") {
            dialog.dismiss()
            StudioPanels.fonts(this, project.captionGlobalFontId) { selected ->
                edit(CaptionStyleResolver.applyGlobalFont(project, selected))
            }
        })
        if (project.texts.any { it.isCaption }) body.addView(action("Aplicar fonte global a todas") {
            edit(CaptionStyleResolver.useGlobalFontForAll(project)); dialog.dismiss()
        })
        if (project.texts.any { it.isCaption && it.wordCues.isNotEmpty() }) body.addView(action("Palavras por bloco") {
            dialog.dismiss()
            choose("Agrupamento automático de legendas", listOf("Automático") + (1..12).map { count -> "$count ${if (count == 1) "palavra" else "palavras"}" }) { index ->
                val count = index.takeIf { it > 0 }
                val next = SubtitleRegrouper.regroup(project, count)
                if (next == project) info.text = "As legendas já estão neste agrupamento ou não possuem cues por palavra"
                else edit(next)
            }
        })
        body.addView(action("Vocabulário personalizado") { dialog.dismiss(); captionVocabularyDialog() })
        if (project.texts.isNotEmpty()) body.addView(action("Editar textos e legendas") { dialog.dismiss(); textMenu() })
        if (project.texts.isNotEmpty()) body.addView(action("Exportar textos como SRT") {
            dialog.dismiss()
            runCatching { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-subrip").putExtra(Intent.EXTRA_TITLE, "Legendas.srt"), 706) }.onFailure { error("Seletor de destino indisponivel") }
        })
        dialog = sheet("Legendas", body)
    }

    private fun captionVocabularyDialog() {
        val input = EditText(this).apply {
            hint = "Um nome ou termo por linha"
            minLines = 6
            maxLines = 12
            setText(project.captionVocabulary.sorted().joinToString("\n"))
        }
        AlertDialog.Builder(this).setTitle("Vocabulário personalizado")
            .setMessage("Nomes e termos do projeto ajudam a escolher entre hipóteses do reconhecimento.")
            .setView(input).setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val terms = input.text.lines().map(String::trim).filter(String::isNotEmpty).toSet()
                if (terms.size > 500 || terms.any { it.length > 80 }) error("Limite: 500 termos de até 80 caracteres")
                else edit(project.copy(captionVocabulary = terms))
            }.show()
    }

    private fun automaticCaptionsDialog() {
        val body = column()
        val languageNames = listOf("Português (Brasil)", "Inglês", "Automático")
        val languageCodes = listOf("pt-BR", "en-US", "auto")
        val language = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, languageNames)
            setSelection(languageCodes.indexOf(project.captionLanguage).coerceAtLeast(0))
        }
        body.addView(label("Idioma", 12f, EditorStyle.MUTED)); body.addView(language)
        body.addView(label("Modelo: Gemini 3.5 Transcribe · modo Exata", 12f, EditorStyle.MUTED))
        val maxWords = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item,
                (2..12).map { count -> "$count palavras" })
            setSelection(2)
        }
        body.addView(label("Máximo de palavras", 12f, EditorStyle.MUTED)); body.addView(maxWords)
        val maxLines = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, listOf("1 linha", "2 linhas"))
            setSelection(1)
        }
        body.addView(label("Máximo de linhas", 12f, EditorStyle.MUTED)); body.addView(maxLines)
        val correction = CheckBox(this).apply { text = "Correção inteligente"; isChecked = true }
        val terms = CheckBox(this).apply { text = "Reconhecer nomes e gírias"; isChecked = true }
        body.addView(correction); body.addView(terms)
        body.addView(label("Termos personalizados: ${project.captionVocabulary.size}. Eles são usados somente na correção, nunca junto com timestamps.", 11f, EditorStyle.MUTED))
        lateinit var dialog: Dialog
        body.addView(action("Gerar legendas", true) {
            dialog.dismiss()
            generateAutomaticCaptions(CaptionGenerationOptions(
                languageCode = languageCodes[language.selectedItemPosition],
                maxWords = maxWords.selectedItemPosition + 2,
                maxLines = maxLines.selectedItemPosition + 1,
                contextualCorrection = correction.isChecked,
                recognizeNamesAndSlang = terms.isChecked,
            ))
        })
        body.addView(action("Editar termos personalizados") { dialog.dismiss(); captionVocabularyDialog() })
        dialog = sheet("Legendas automáticas", body)
    }

    private fun generateAutomaticCaptions(options: CaptionGenerationOptions, confirmedExisting: Boolean = false) {
        if (busy) return
        val snapshot = project
        val clips = snapshot.videos.indices.filter { !snapshot.videos[it].image }
        if (clips.isEmpty()) { error("Adicione um vídeo com áudio para gerar legendas"); return }
        val existingCaptions = snapshot.texts.count { it.isCaption }
        if (existingCaptions > 0 && !confirmedExisting) {
            AlertDialog.Builder(this).setTitle("Legendas existentes")
                .setMessage("Este projeto já tem $existingCaptions legendas. Gerar novamente adicionará uma nova geração; nada será apagado e a operação poderá ser desfeita inteira.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Adicionar geração") { _, _ -> generateAutomaticCaptions(options, confirmedExisting = true) }
                .show()
            return
        }
        val generationId = captionSession.begin()
        val cancellation = captionSession.cancellation(generationId)
        busy = true
        preview.pause()
        info.text = "Preparando áudio..."
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Legendas automáticas")
            .setMessage("Preparando áudio...")
            .setNegativeButton("Cancelar") { _, _ -> captionSession.cancel(); captionJob?.cancel(); info.text = "Cancelando legendas..." }
            .create().apply { setCancelable(false); show() }
        captionJob = captionScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val generator = GeminiCaptionGenerator(this@EditorActivity)
                    var updated = snapshot
                    for ((ordinal, index) in clips.withIndex()) {
                        cancellation.check()
                        val clipLabel = "Clipe ${ordinal + 1}/${clips.size}"
                        updated = generator.generate(updated, index, options, { event ->
                            main.post {
                                if (!isDestroyed && progressDialog.isShowing) {
                                    val message = "$clipLabel • ${event.stage}"
                                    progressDialog.setMessage(message); info.text = message
                                }
                            }
                        }, { cancellation.check() }).first
                    }
                    cancellation.check(); updated
                }
            }
            if (isDestroyed) return@launch
            progressDialog.dismiss(); busy = false
            result.fold({ updated ->
                if (runCatching { cancellation.check() }.isFailure) { info.text = "Geração cancelada"; return@fold }
                val count = updated.texts.size - snapshot.texts.size
                edit(updated)
                info.text = "$count legendas geradas com Gemini."
                AlertDialog.Builder(this@EditorActivity).setTitle("Legendas criadas")
                    .setMessage("$count legendas foram inseridas na timeline. Texto, fonte, estilo, animação, posição e duração continuam editáveis.")
                    .setPositiveButton("Editar") { _, _ -> textMenu() }
                    .setNegativeButton("Depois", null).show()
            }, { failure ->
                if (failure is CancellationException) info.text = "Geração cancelada"
                else error(captionErrorMessage(failure))
            })
        }
    }

    private fun captionErrorMessage(error: Throwable): String = when (error) {
        is com.termex.replay15.editor.captions.InvalidApiKey -> error.message.orEmpty()
        is com.termex.replay15.editor.captions.NoInternet -> error.message.orEmpty()
        is com.termex.replay15.editor.captions.QuotaExceeded -> error.message.orEmpty()
        is com.termex.replay15.editor.captions.UnsupportedAudio -> error.message.orEmpty()
        is com.termex.replay15.editor.captions.AudioExtractionFailed -> error.message.orEmpty()
        is com.termex.replay15.editor.captions.UploadFailed -> error.message.orEmpty()
        is com.termex.replay15.editor.captions.CaptionBackendNotConfigured -> error.message.orEmpty()
        else -> "Não foi possível gerar legendas. Tente novamente mais tarde."
    }

    private fun openSubtitleOrTextTools(id: String) {
        val text = project.texts.firstOrNull { it.id == id } ?: return
        if (text.isCaption) subtitleContentPanel(id) else textPanel(id)
    }

    private fun subtitlePatchPreview(id: String, patch: SubtitleStylePatch) {
        val changed = project.copy(texts = project.texts.map { if (it.id == id) patch.applyTo(it) else it })
        previewEdit(changed)
    }

    private fun applySubtitlePatch(id: String, patch: SubtitleStylePatch) {
        val changed = SubtitleApplyRangeResolver.apply(project, id, subtitleApplyRange, patch)
        if (changed == project) {
            info.text = "Nenhuma legenda encontrada neste escopo"
        } else {
            edit(changed)
            val count = SubtitleApplyRangeResolver.indices(project.texts.filter { it.isCaption }.sortedBy { it.startUs }, id, subtitleApplyRange).size
            info.text = "Estilo aplicado em $count ${if (count == 1) "legenda" else "legendas"}"
        }
    }

    private fun subtitleDialog(title: String, body: LinearLayout): Dialog = sheet(title, body).also { dialog ->
        dialog.setOnDismissListener { previewEdit(project) }
    }

    /** Shared mobile apply bar. The selected range is session-only and never enters Project. */
    private fun addSubtitleApplyBar(body: LinearLayout, id: String, patch: () -> SubtitleStylePatch) {
        body.addView(sectionTitle("Aplicar em", "O escopo vale somente para esta edição. Cada legenda mantém seus outros estilos."))
        val chips = row()
        fun chip(title: String, range: ApplyRange, weight: Float = 1f) {
            chips.addView(action(title, subtitleApplyRange == range) {
                subtitleApplyRange = range
                window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                applySubtitlePatch(id, patch())
            }, LinearLayout.LayoutParams(0, dp(52), weight).apply { marginEnd = dp(3) })
        }
        chip("Esta", ApplyRange.current())
        chip("+3", ApplyRange.next(3))
        chip("+5", ApplyRange.next(5))
        chip("+10", ApplyRange.next(10))
        chip("Fim", ApplyRange.untilEnd())
        chip("Todas", ApplyRange.all())
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(-1, dp(64)))

        val nextRow = row()
        val count = label("${subtitleApplyRange.nextCount}", 16f, EditorStyle.ACCENT).apply {
            gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        nextRow.addView(label("Próximas", 13f, EditorStyle.MUTED), LinearLayout.LayoutParams(0, dp(48), 1f))
        nextRow.addView(action("−") {
            subtitleApplyRange = ApplyRange.next((subtitleApplyRange.nextCount - 1).coerceAtLeast(0)); count.text = subtitleApplyRange.nextCount.toString()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        nextRow.addView(count, LinearLayout.LayoutParams(dp(48), dp(48)))
        nextRow.addView(action("+") {
            subtitleApplyRange = ApplyRange.next((subtitleApplyRange.nextCount + 1).coerceAtMost(MAX_TEXTS)); count.text = subtitleApplyRange.nextCount.toString()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        nextRow.addView(action("Aplicar") { applySubtitlePatch(id, patch()) }, LinearLayout.LayoutParams(dp(100), dp(48)))
        body.addView(nextRow)
        body.addView(action("Selecionar intervalo") {
            val rangeBody = column()
            val first = EditText(this).apply { hint = "Primeira legenda (1...)"; inputType = InputType.TYPE_CLASS_NUMBER }
            val last = EditText(this).apply { hint = "Última legenda"; inputType = InputType.TYPE_CLASS_NUMBER }
            rangeBody.addView(first); rangeBody.addView(last)
            AlertDialog.Builder(this).setTitle("Intervalo de legendas").setView(rangeBody)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aplicar") { _, _ ->
                    val start = first.text.toString().toIntOrNull()?.minus(1)
                    val end = last.text.toString().toIntOrNull()?.minus(1)
                    if (start == null || end == null || start < 0 || end < start) {
                        error("Informe um intervalo válido")
                    } else {
                        subtitleApplyRange = ApplyRange.custom(start, end)
                        applySubtitlePatch(id, patch())
                    }
                }.show()
        })
    }

    private fun subtitleContentPanel(id: String) {
        val text = project.texts.firstOrNull { it.id == id } ?: return
        val body = column()
        body.addView(sectionTitle("Editar legenda", "O texto é aplicado somente à legenda selecionada."))
        val input = EditText(this).apply {
            setText(text.text); minLines = 2; maxLines = 5
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
        }
        body.addView(input)
        body.addView(action("Salvar legenda", true) {
            val value = input.text.toString().trim()
            if (value.isBlank()) error("A legenda não pode ficar vazia") else {
                edit(project.copy(texts = project.texts.map { if (it.id == id) it.copy(text = value) else it }))
                seek(text.startUs)
            }
        })
        subtitleDialog("Editar legenda", body)
    }

    private fun subtitleStylePanel(id: String) {
        val text = project.texts.firstOrNull { it.id == id } ?: return
        if (!text.isCaption) { textPanel(id); return }
        val body = column()
        var patch = SubtitleStylePatch()
        fun update(next: SubtitleStylePatch) { patch = patch.plus(next); subtitlePatchPreview(id, patch) }
        body.addView(sectionTitle("Estilo", "A prévia é atualizada enquanto você arrasta. Desfazer será uma única ação ao aplicar."))
        val presets = row()
        fun preset(name: String, next: SubtitleStylePatch) {
            presets.addView(action(name) { update(next) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(3) })
        }
        preset("Minimal", SubtitleStylePatch(fontId = "poppins_regular", fontWeight = 600, color = Color.WHITE,
            backgroundColor = Color.TRANSPARENT, outlineWidth = 0f, shadow = false))
        preset("Podcast", SubtitleStylePatch(fontId = "montserrat_semibold", fontWeight = 600, color = Color.WHITE,
            backgroundColor = 0xCC000000.toInt(), outlineWidth = 0f, shadow = true))
        preset("Bold", SubtitleStylePatch(fontId = "anton_regular", fontWeight = 700, color = 0xFFFFCF5A.toInt(),
            backgroundColor = Color.TRANSPARENT, outlineColor = Color.BLACK, outlineWidth = .012f, shadow = true))
        preset("TikTok", SubtitleStylePatch(fontId = "outfit_regular", fontWeight = 700, color = Color.WHITE,
            backgroundColor = 0xCC6E35C7.toInt(), outlineWidth = 0f, shadow = true))
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presets) })
        slider(body, "Peso da fonte", text.fontWeight, 900, 100) { update(SubtitleStylePatch(fontWeight = it)) }
        slider(body, "Tamanho (%)", (text.size * 100).toInt(), 25, 2) { update(SubtitleStylePatch(size = it / 100f)) }
        slider(body, "Opacidade (%)", (text.opacity * 100).toInt(), 100, 10) { update(SubtitleStylePatch(opacity = it / 100f)) }
        slider(body, "Espaço entre letras", (text.letterSpacing * 100 + 5).toInt(), 35, 0) { update(SubtitleStylePatch(letterSpacing = (it - 5) / 100f)) }
        slider(body, "Espaço entre linhas", (text.lineSpacing * 100).toInt(), 200, 70) { update(SubtitleStylePatch(lineSpacing = it / 100f)) }

        val align = row()
        TextAlignment.entries.forEach { value ->
            align.addView(action(when (value) { TextAlignment.LEFT -> "Esquerda"; TextAlignment.CENTER -> "Centro"; TextAlignment.RIGHT -> "Direita" }) {
                update(SubtitleStylePatch(alignment = value))
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(3) })
        }
        body.addView(align)

        body.addView(sectionTitle("Cor do texto"))
        val colors = row()
        listOf(Color.WHITE, Color.BLACK, 0xFFFFCF5A.toInt(), 0xFFFF6B7A.toInt(), 0xFFB77AFF.toInt(), 0xFF5EDADB.toInt(), 0xFF58E28A.toInt()).forEach { color ->
            colors.addView(action("●") { update(SubtitleStylePatch(color = color)) }.apply {
                textSize = 25f; setTextColor(color)
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(colors) })
        val customColor = EditText(this).apply {
            hint = "#FFFFFF ou #80FFFFFF"; isSingleLine = true
            setText(String.format("#%08X", text.color))
        }
        body.addView(row().apply {
            addView(customColor, LinearLayout.LayoutParams(0, dp(54), 1f))
            addView(action("Usar") {
                runCatching { Color.parseColor(customColor.text.toString().trim()) }
                    .onSuccess { update(SubtitleStylePatch(color = it)) }
                    .onFailure { customColor.error = "Use #RRGGBB ou #AARRGGBB" }
            }, LinearLayout.LayoutParams(dp(90), dp(54)))
        })

        body.addView(sectionTitle("Contorno, sombra e fundo"))
        val outlineColors = row()
        listOf(Color.BLACK, Color.WHITE, 0xFFB77AFF.toInt(), 0xFFFFCF5A.toInt()).forEach { color ->
            outlineColors.addView(action("Contorno") { update(SubtitleStylePatch(outlineColor = color)) }.apply { setTextColor(color) },
                LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        body.addView(outlineColors)
        slider(body, "Espessura do contorno", (text.outlineWidth * 1000).toInt(), 40) { update(SubtitleStylePatch(outlineWidth = it / 1000f)) }
        val shadow = CheckBox(this).apply {
            this.text = "Sombra suave"; isChecked = text.shadow
            setOnCheckedChangeListener { _, checked -> update(SubtitleStylePatch(shadow = checked)) }
        }
        body.addView(shadow)
        val backgrounds = row()
        listOf(Color.TRANSPARENT, 0xCC000000.toInt(), 0xCCFFFFFF.toInt(), 0xCC6E35C7.toInt(), 0xCC1B5960.toInt()).forEachIndexed { index, color ->
            backgrounds.addView(action(if (index == 0) "Sem" else "●") { update(SubtitleStylePatch(backgroundColor = color)) }.apply {
                if (index > 0) { textSize = 25f; setTextColor(color or 0xFF000000.toInt()) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        body.addView(backgrounds)
        addSubtitleApplyBar(body, id) { patch }
        subtitleDialog("Estilo da legenda", body)
    }

    private fun subtitleFontPanel(id: String) {
        val text = project.texts.firstOrNull { it.id == id } ?: return
        if (!text.isCaption) { textPanel(id); return }
        val body = column()
        var patch = SubtitleStylePatch()
        fun update(fontId: String) { patch = patch.plus(SubtitleStylePatch(fontId = fontId)); subtitlePatchPreview(id, patch) }
        val current = CaptionStyleResolver.resolve(project, text).fontId
        body.addView(sectionTitle("Fonte", "Cada legenda conserva cor, posição, tamanho e demais propriedades."))
        val selected = label(FontCatalog.requireOrDefault(current).displayName, 16f, EditorStyle.ACCENT)
        body.addView(selected)
        body.addView(action("Abrir galeria de fontes", true) {
            StudioPanels.fonts(this, current) { fontId ->
                selected.text = FontCatalog.requireOrDefault(fontId).displayName
                update(fontId)
            }
        })
        addSubtitleApplyBar(body, id) { patch }
        subtitleDialog("Fonte da legenda", body)
    }

    private fun subtitleAnimationPanel(id: String) {
        val text = project.texts.firstOrNull { it.id == id } ?: return
        if (!text.isCaption) { textPanel(id); return }
        val body = column()
        var patch = SubtitleStylePatch()
        fun update(animation: TextAnimation, slot: SubtitleApplyScope) {
            patch = patch.plus(when (slot) {
                SubtitleApplyScope.CURRENT -> SubtitleStylePatch(enterAnimation = animation, animation = TextAnimation.NONE)
                SubtitleApplyScope.NEXT_N -> SubtitleStylePatch(duringAnimation = animation, animation = TextAnimation.NONE)
                else -> SubtitleStylePatch(exitAnimation = animation, animation = TextAnimation.NONE)
            })
            subtitlePatchPreview(id, patch)
        }
        fun animationButton(title: String, current: TextAnimation, set: (TextAnimation) -> Unit) {
            body.addView(action("$title: ${current.label}") {
                choose(title, TextAnimation.entries.map { it.label }) { index ->
                    set(TextAnimation.entries[index])
                    subtitlePatchPreview(id, patch)
                }
            })
        }
        val enter = if (text.enterAnimation == TextAnimation.NONE) text.animation else text.enterAnimation
        animationButton("Entrada", enter) { update(it, SubtitleApplyScope.CURRENT) }
        animationButton("Durante", text.duringAnimation) { update(it, SubtitleApplyScope.NEXT_N) }
        animationButton("Saída", text.exitAnimation) { update(it, SubtitleApplyScope.UNTIL_END) }
        body.addView(label("A animação usa o tempo real da legenda e a mesma avaliação no preview e na exportação.", 12f, EditorStyle.MUTED))
        addSubtitleApplyBar(body, id) { patch }
        subtitleDialog("Animação da legenda", body)
    }

    private fun subtitleWordPanel(id: String) {
        val text = project.texts.firstOrNull { it.id == id } ?: return
        if (!text.isCaption) { textPanel(id); return }
        val body = column()
        var patch = SubtitleStylePatch()
        fun chooseWordStyle(name: String, style: SubtitleWordStyle, animation: TextAnimation) {
            patch = patch.plus(SubtitleStylePatch(wordStyle = style, duringAnimation = animation, animation = TextAnimation.NONE))
            subtitlePatchPreview(id, patch)
        }
        body.addView(sectionTitle("Palavra", if (text.wordCues.isEmpty())
            "Esta legenda não tem timestamps por palavra; gere legendas automáticas com alinhamento para ativar o modo palavra."
        else "A palavra ativa acompanha os timestamps medidos pela transcrição."))
        body.addView(action("Sem destaque") {
            chooseWordStyle("Sem destaque", SubtitleWordStyle(), TextAnimation.NONE)
        })
        body.addView(action("Word Pop") {
            chooseWordStyle("Word Pop", SubtitleWordStyle(
                normalColor = text.color, spokenColor = text.color, futureColor = text.color,
                activeColor = 0xFFFFCF5A.toInt(), activeScale = 1.15f, boldCurrentWord = true,
            ), TextAnimation.WORD_POP)
        })
        body.addView(action("Karaoke") {
            chooseWordStyle("Karaoke", SubtitleWordStyle(
                normalColor = text.color, futureColor = text.color, spokenColor = 0xFFFFCF5A.toInt(),
                activeColor = Color.WHITE, activeScale = 1f, boldCurrentWord = true,
            ), TextAnimation.KARAOKE)
        })
        body.addView(action("Destacar palavra atual") {
            chooseWordStyle("Palavra atual", SubtitleWordStyle(
                normalColor = text.color, futureColor = text.color, spokenColor = text.color,
                activeColor = 0xFFFFCF5A.toInt(), activeScale = 1f, boldCurrentWord = true,
            ), TextAnimation.CURRENT_WORD_HIGHLIGHT)
        })
        addSubtitleApplyBar(body, id) { patch }
        subtitleDialog("Animação por palavra", body)
    }

    private fun subtitlePositionPanel(id: String) {
        val text = project.texts.firstOrNull { it.id == id } ?: return
        if (!text.isCaption) { textPanel(id); return }
        val body = column()
        var patch = SubtitleStylePatch()
        fun update(next: SubtitleStylePatch) { patch = patch.plus(next); subtitlePatchPreview(id, patch) }
        body.addView(sectionTitle("Posição", "As coordenadas são normalizadas e funcionam em qualquer formato de vídeo."))
        slider(body, "Horizontal (%)", (text.x * 100).toInt(), 100) { update(SubtitleStylePatch(x = it / 100f)) }
        slider(body, "Vertical (%)", (text.y * 100).toInt(), 100) { update(SubtitleStylePatch(y = it / 100f)) }
        slider(body, "Escala (%)", (text.scale * 100).toInt().coerceIn(5, 2000), 2000, 5) { update(SubtitleStylePatch(scale = it / 100f)) }
        slider(body, "Rotação", text.rotation.toInt() + 180, 360) { update(SubtitleStylePatch(rotation = (it - 180).toFloat())) }
        addSubtitleApplyBar(body, id) { patch }
        subtitleDialog("Posição da legenda", body)
    }

    private fun textMenu() {
        if (project.texts.isEmpty()) { textPanel(); return }
        val body = column()
        lateinit var dialog: Dialog
        body.addView(action("+ Adicionar texto", true) { dialog.dismiss(); textPanel() })
        val search = EditText(this).apply { hint = "Buscar texto ou legenda"; isSingleLine = true }; body.addView(search)
        val list = ListView(this).apply { dividerHeight = dp(5) }
        var filtered = project.texts.sortedBy { it.startUs }
        fun update(query: String) {
            filtered = project.texts.filter { it.text.contains(query, true) }.sortedBy { it.startUs }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filtered.map { "${timeLabel(it.startUs)}   ${it.text.replace('\n', ' ').take(90)}" })
        }
        list.setOnItemClickListener { _, _, index, _ -> val text = filtered[index]; dialog.dismiss(); seek(text.startUs); textPanel(text.id) }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        body.addView(list, LinearLayout.LayoutParams(-1, dp(280))); update(""); dialog = sheet("Textos e legendas", body)
    }
    private fun textPanel(id: String? = null) {
        if (project.durationUs <= 0) { error("Adicione um video antes do texto"); return }
        if (id == null && project.texts.size >= MAX_TEXTS) { error("Limite de $MAX_TEXTS textos por projeto"); return }
        val existing = project.texts.find { it.id == id }
        val at = minOf(position, (project.durationUs - MIN_CLIP).coerceAtLeast(0))
        val text = existing ?: TextClip(text = "Seu texto", startUs = at, endUs = minOf(project.durationUs, at + 5 * SECOND))
        val body = column().apply { setPadding(dp(2), 0, dp(2), dp(8)) }
        fun section(title: String, subtitle: String = "") {
            body.addView(label(title.uppercase(), 11f, EditorStyle.ACCENT).apply {
                setPadding(dp(8), dp(16), dp(8), if (subtitle.isBlank()) dp(5) else 0)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (subtitle.isNotBlank()) body.addView(label(subtitle, 11f, EditorStyle.MUTED).apply { setPadding(dp(8), 0, dp(8), dp(6)) })
        }

        var fontId = CaptionStyleResolver.resolve(project, text).fontId
        var boldValue = text.bold
        var italicValue = text.italic
        var underlineValue = text.underline
        var alignment = text.alignment
        var color = text.color
        var backgroundColor = text.backgroundColor
        var outlineColor = text.outlineColor
        var outlineWidth = (text.outlineWidth * 1000).toInt()
        var opacity = (text.opacity * 100).toInt()
        var letterSpacing = (text.letterSpacing * 100).toInt()
        var lineSpacing = (text.lineSpacing * 100).toInt()
        var boxWidth = (text.boxWidth * 100).toInt()
        var rotation = text.rotation.toInt()
        var xPosition = (text.x * 100).toInt()
        var yPosition = (text.y * 100).toInt()
        var animation = text.animation

        section("Conteudo", "Veja uma amostra do estilo antes de aplicar")
        val input = EditText(this).apply {
            setText(text.text); minLines = 2; maxLines = 6
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        body.addView(input)

        lateinit var fontButton: Button
        lateinit var alignmentButton: Button
        lateinit var animationButton: Button
        lateinit var bold: CheckBox
        lateinit var italic: CheckBox
        lateinit var underline: CheckBox
        lateinit var shadow: CheckBox
        lateinit var sizeSlider: SeekBar
        lateinit var opacitySlider: SeekBar
        lateinit var widthSlider: SeekBar
        lateinit var outlineSlider: SeekBar

        fun previewColor(value: Int): Int {
            val alpha = (Color.alpha(value) * (opacity / 100f)).toInt().coerceIn(0, 255)
            return (value and 0x00FFFFFF) or (alpha shl 24)
        }
        fun updatePreview() {
            val style = when {
                boldValue && italicValue -> android.graphics.Typeface.BOLD_ITALIC
                boldValue -> android.graphics.Typeface.BOLD
                italicValue -> android.graphics.Typeface.ITALIC
                else -> android.graphics.Typeface.NORMAL
            }
            input.typeface = EditorFonts.get(this, fontId, style)
            input.setTextColor(previewColor(color))
            input.setBackgroundColor(previewColor(backgroundColor))
            input.paint.isUnderlineText = underlineValue
            input.letterSpacing = letterSpacing / 100f
            input.setLineSpacing(0f, lineSpacing / 100f)
            input.gravity = Gravity.CENTER_VERTICAL or when (alignment) {
                TextAlignment.LEFT -> Gravity.START
                TextAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
                TextAlignment.RIGHT -> Gravity.END
            }
            val fontAsset = com.termex.replay15.editor.font.FontCatalog.requireOrDefault(fontId)
            fontButton.text = "Fonte: ${fontAsset.displayName}"
            fontButton.typeface = EditorFonts.get(this, fontId)
            alignmentButton.text = "Alinhamento: ${when (alignment) {
                TextAlignment.LEFT -> "Esquerda"; TextAlignment.CENTER -> "Centro"; TextAlignment.RIGHT -> "Direita"
            }}"
            animationButton.text = "Animacao: ${animation.label}"
            bold.isChecked = boldValue
            italic.isChecked = italicValue
            underline.isChecked = underlineValue
            outlineSlider.progress = outlineWidth
            widthSlider.progress = boxWidth
        }

        section("Estilos rapidos")
        val presets = row()
        fun preset(name: String, apply: () -> Unit) = action(name) { apply(); updatePreview() }
        presets.addView(preset("Limpo") {
            fontId = TextFont.MODERNA.id; boldValue = true; italicValue = false; color = Color.WHITE
            backgroundColor = Color.TRANSPARENT; outlineColor = Color.BLACK; outlineWidth = 3
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(3) })
        presets.addView(preset("Impacto") {
            fontId = TextFont.IMPACTO.id; boldValue = true; italicValue = false; color = 0xFFFFCF5A.toInt()
            backgroundColor = Color.TRANSPARENT; outlineColor = Color.BLACK; outlineWidth = 12
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        presets.addView(preset("Legenda") {
            fontId = TextFont.MODERNA.id; boldValue = true; italicValue = false; color = Color.WHITE
            backgroundColor = 0xCC000000.toInt(); outlineWidth = 0; boxWidth = 80
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(3) })
        body.addView(presets)

        section("Fonte e formato")
        fontButton = action("Fonte: ${com.termex.replay15.editor.font.FontCatalog.requireOrDefault(fontId).displayName}") {
            StudioPanels.fonts(this, fontId) { selectedFontId -> fontId = selectedFontId; updatePreview() }
        }.apply { typeface = EditorFonts.get(this@EditorActivity, fontId) }
        body.addView(fontButton, LinearLayout.LayoutParams(-1, dp(54)))
        if (text.isCaption) {
            body.addView(action("Usar fonte global") {
                fontId = project.captionGlobalFontId
                edit(project.copy(texts = project.texts.map {
                    if (it.id == text.id) it.copy(captionFontOverride = null) else it
                }))
                updatePreview()
            })
            body.addView(action("Aplicar fonte a todas as legendas") {
                edit(CaptionStyleResolver.useGlobalFontForAll(
                    CaptionStyleResolver.applyGlobalFont(project, fontId)))
                updatePreview()
            })
        }
        val styleRow = row()
        bold = CheckBox(this).apply {
            this.text = "Negrito"; isChecked = boldValue; setOnCheckedChangeListener { _, checked -> boldValue = checked; updatePreview() }
        }
        italic = CheckBox(this).apply {
            this.text = "Italico"; isChecked = italicValue; setOnCheckedChangeListener { _, checked -> italicValue = checked; updatePreview() }
        }
        underline = CheckBox(this).apply {
            this.text = "Sublinhado"; isChecked = underlineValue; setOnCheckedChangeListener { _, checked -> underlineValue = checked; updatePreview() }
        }
        styleRow.addView(bold, LinearLayout.LayoutParams(0, -2, 1f))
        styleRow.addView(italic, LinearLayout.LayoutParams(0, -2, 1f))
        styleRow.addView(underline, LinearLayout.LayoutParams(0, -2, 1.25f))
        body.addView(styleRow)
        alignmentButton = action("") {
            choose("Alinhamento", listOf("Esquerda", "Centro", "Direita")) { index ->
                alignment = TextAlignment.entries[index]; updatePreview()
            }
        }
        body.addView(alignmentButton, LinearLayout.LayoutParams(-1, dp(52)))

        section("Cor do texto", "Escolha uma cor ou digite um codigo hexadecimal")
        val colors = listOf(Color.WHITE, Color.BLACK, 0xFFFFCF5A.toInt(), 0xFFFF6B7A.toInt(), 0xFFB77AFF.toInt(), 0xFF5EDADB.toInt(), 0xFF58E28A.toInt())
        val colorRow = row()
        colors.forEach { chosen ->
            colorRow.addView(action("●") { color = chosen; updatePreview() }.apply { textSize = 25f; setTextColor(chosen) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        body.addView(colorRow)
        val customColorRow = row()
        val customColor = EditText(this).apply { hint = "#FFFFFF"; isSingleLine = true; setText(String.format("#%06X", color and 0xFFFFFF)) }
        customColorRow.addView(customColor, LinearLayout.LayoutParams(0, dp(54), 1f))
        customColorRow.addView(action("Usar cor") {
            runCatching {
                val value = customColor.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }
                require(value.matches(Regex("#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}")))
                Color.parseColor(value)
            }.fold({ color = it; updatePreview() }, { error("Use uma cor como #FFFFFF ou #80FFFFFF") })
        }, LinearLayout.LayoutParams(dp(112), dp(54)))
        body.addView(customColorRow)

        section("Fundo, contorno e sombra")
        val backgroundRow = row()
        listOf(Color.TRANSPARENT, 0xCC000000.toInt(), 0xCCFFFFFF.toInt(), 0xCC6E35C7.toInt(), 0xCC1B5960.toInt()).forEachIndexed { index, chosen ->
            backgroundRow.addView(action(if (index == 0) "Sem" else "●") { backgroundColor = chosen; updatePreview() }.apply {
                if (index > 0) { textSize = 25f; setTextColor(chosen or 0xFF000000.toInt()) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        body.addView(backgroundRow)
        val outlineRow = row()
        listOf(Color.BLACK, Color.WHITE, 0xFFB77AFF.toInt(), 0xFFFFCF5A.toInt()).forEach { chosen ->
            outlineRow.addView(action("Contorno") { outlineColor = chosen }.apply { setTextColor(chosen) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        }
        body.addView(outlineRow)
        outlineSlider = slider(body, "Espessura do contorno", outlineWidth, 40) { outlineWidth = it }
        shadow = CheckBox(this).apply { this.text = "Sombra suave"; isChecked = text.shadow }
        body.addView(shadow)

        section("Tamanho e composicao")
        var size = (text.size * 100).toInt()
        sizeSlider = slider(body, "Tamanho (% da altura)", size, 25, minimum = 2) { size = it }
        opacitySlider = slider(body, "Opacidade (%)", opacity, 100, minimum = 10) { opacity = it; updatePreview() }
        widthSlider = slider(body, "Largura da caixa (0 = automatica)", boxWidth, 95) { boxWidth = if (it in 1..19) 20 else it }
        slider(body, "Espacamento entre letras", letterSpacing + 5, 35) { letterSpacing = it - 5; updatePreview() }
        slider(body, "Espacamento entre linhas", lineSpacing, 200, minimum = 70) { lineSpacing = it; updatePreview() }
        slider(body, "Rotacao", rotation + 180, 360) { rotation = it - 180 }

        section("Posicao precisa", "Tambem e possivel arrastar e redimensionar direto no video")
        slider(body, "Horizontal (%)", xPosition, 100) { xPosition = it }
        slider(body, "Vertical (%)", yPosition, 100) { yPosition = it }

        section("Tempo e movimento")
        val start = numberField(body, "Inicio na timeline (s)", text.startUs / SECOND.toDouble())
        val end = numberField(body, "Fim na timeline (s)", text.endUs / SECOND.toDouble())
        animationButton = action("") {
            choose("Animacao de entrada e saida", TextAnimation.entries.map { it.label }) { index ->
                animation = TextAnimation.entries[index]; updatePreview()
            }
        }
        body.addView(animationButton, LinearLayout.LayoutParams(-1, dp(54)))
        body.addView(label("A animacao aparece na previa e no video exportado. Arraste o texto no video, pince para redimensionar e toque duas vezes para editar.", color = EditorStyle.MUTED))

        updatePreview()
        lateinit var dialog: Dialog
        body.addView(action("Aplicar texto", true) {
            runCatching {
                require(seconds(end) <= project.durationUs)
                text.copy(
                    text = input.text.toString().trim(), startUs = seconds(start), endUs = seconds(end),
                    x = xPosition / 100f, y = yPosition / 100f, size = size / 100f,
                    color = color, bold = boldValue, fontId = fontId, italic = italicValue,
                    captionFontOverride = if (text.isCaption && fontId != project.captionGlobalFontId) fontId else null,
                    underline = underlineValue, alignment = alignment, opacity = opacity / 100f,
                    letterSpacing = letterSpacing / 100f, lineSpacing = lineSpacing / 100f,
                    boxWidth = if (boxWidth < 20) 0f else boxWidth / 100f, rotation = rotation.toFloat(),
                    backgroundColor = backgroundColor, outlineColor = outlineColor,
                    outlineWidth = outlineWidth / 1000f, shadow = shadow.isChecked, animation = animation,
                )
            }.fold({ changed ->
                handles.selected = changed.id
                stickerHandles.selected = null
                edit(project.copy(texts = if (existing == null) project.texts + changed else project.texts.map { if (it.id == id) changed else it }))
                seek(changed.startUs); dialog.dismiss()
            }, { error("Preencha o texto e um intervalo valido dentro do video.") })
        })
        if (existing != null) {
            val layerRow = row()
            layerRow.addView(action("Enviar para tras") {
                val others = project.texts.filterNot { it.id == id }
                edit(project.copy(texts = listOf(text) + others)); dialog.dismiss()
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(3) })
            layerRow.addView(action("Trazer para frente") {
                val others = project.texts.filterNot { it.id == id }
                edit(project.copy(texts = others + text)); dialog.dismiss()
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(3) })
            body.addView(layerRow)
            val editRow = row()
            editRow.addView(action("Duplicar") {
                if (project.texts.size >= MAX_TEXTS) error("Limite de $MAX_TEXTS textos") else {
                    val copy = text.copy(id = newId(), x = (text.x + .04f).coerceAtMost(1f), y = (text.y + .04f).coerceAtMost(1f))
                    handles.selected = copy.id; edit(project.copy(texts = project.texts + copy)); dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(3) })
            editRow.addView(action("Excluir texto") { edit(project.copy(texts = project.texts.filterNot { it.id == id })); dialog.dismiss() },
                LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(3) })
            body.addView(editRow)
        }
        dialog = sheet(if (existing == null) "Novo texto" else "Estudio de texto", body)
    }

    private fun previewQuality() {
        if (EditorExportService.state.active) { error("A previa fica pausada durante a exportacao para economizar memoria."); return }
        choose("Qualidade da previa (nao altera a exportacao)", listOf("Automatica (recomendado)", "Leve: 480p", "Media: 720p", "Alta: ate 1080p")) {
            preview.quality = when (it) { 0 -> 0; 1 -> 480; 2 -> 720; else -> 1080 }
            if (!EditorExportService.state.active) preview.load(project, position)
        }
    }
    private fun projectPanel() {
        choose("Projeto", listOf("Salvar agora", "Renomear", "Abrir biblioteca", "Recursos desta versao")) { i -> when (i) {
            0 -> saveAsync(false)
            1 -> {
                val input = EditText(this).apply { setText(project.name); filters = arrayOf(android.text.InputFilter.LengthFilter(100)) }
                AlertDialog.Builder(this).setTitle("Nome do projeto").setView(input).setPositiveButton("Salvar") { _, _ ->
                    edit(project.copy(name = input.text.toString().trim().ifEmpty { "Novo projeto" }))
                }.setNegativeButton("Cancelar", null).show()
            }
            2 -> { saveAsync(false); startActivity(Intent(this, EditorLibraryActivity::class.java)) }
            3 -> AlertDialog.Builder(this).setTitle("Recly Editor V3: base multicamadas")
                .setMessage("Video principal e ate sete faixas sobrepostas com posicao, escala, corte, divisao, volume, visibilidade e bloqueio. O limite pratico depende dos decoders do aparelho.\n\nPilha de efeitos GPU com intensidade e parametros, seis efeitos incluidos e importacao de pacotes .reclyfx. Previa e exportacao usam a mesma composicao.\n\nFiltros, LUTs, curvas de cor, mascaras, chroma, keyframes, textos, stickers, SRT, rascunhos de legendas automaticas, tracking de movimento e narracao continuam disponiveis. Revise as legendas geradas antes de exportar. Waveform real nas faixas de audio importadas.\n\nEsta etapa ainda nao inclui reverse, speed ramp ou transicoes entre dois videos. Exportacao H.264/AAC com resolucao e FPS validados pelo encoder.")
                .setPositiveButton("OK", null).show()
        } }
    }
    private fun searchTools() {
        val tools = listOf<Pair<String, () -> Unit>>("Importar video ou foto" to { pick(false) }, "Editar / Dividir / Excluir" to { editTools() },
            "Aparar" to { trimPanel() }, "Velocidade" to { speedPanel() }, "Volume" to { volumePanel() }, "Extrair audio" to { extractAudio() },
            "Transformar / Zoom / Posicao / Desfoque" to { transformPanel() }, "Transicoes" to { transitionPanel() },
            "Keyframes / Animar / Interpolacao" to { keyframePanel() }, "Cor / Curvas / LUT / Nitidez / Grao / Vinheta" to { colorPanel() },
            "Mascaras / Chroma key" to { masksPanel() }, "Rastreamento de movimento" to { trackingPanel() }, "Congelar quadro" to { freezeFrame() }, "Marcadores" to { markerPanel() },
            "Ajustar timeline na tela" to { timeline.fit() }, "Qualidade da previa" to { previewQuality() },
            "Ajustar cor e luz" to { adjustmentsPanel() }, "Audio / Musica / Faixas" to { audioPanel() }, "Gravar narracao" to { voiceoverPanel() },
            "Texto / Fontes / Animacoes" to { textMenu() }, "Legendas SRT" to { subtitleMenu() }, "Camada / Sticker" to { stickerMenu() },
            "Recortar imagem" to { cropPanel() }, "Filtros" to { filters() }, "Proporcao" to { aspectPanel() },
            "Salvar projeto" to { saveAsync(false) }, "Exportar" to { exportPanel(true) },
            "Qualidade de exportacao" to { exportPanel(false) })
        val body = column(); val input = EditText(this).apply { hint = "Buscar ferramenta" }; body.addView(input)
        val results = column(); body.addView(results)
        lateinit var dialog: Dialog
        fun update(query: String) {
            results.removeAllViews()
            tools.filter { it.first.contains(query, true) }.forEach { (name, callback) ->
                results.addView(action(name) { dialog.dismiss(); callback() })
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        update(""); dialog = sheet("Ferramentas", body)
    }
    private fun exportPanel(startExport: Boolean) {
        preview.pause()
        if (EditorExportService.state.active) {
            AlertDialog.Builder(this).setTitle("Exportacao em andamento").setMessage(EditorExportService.state.message)
                .setPositiveButton("Continuar", null).setNegativeButton("Cancelar exportacao") { _, _ ->
                    startService(Intent(this, EditorExportService::class.java).setAction(EditorExportService.CANCEL))
                }.show(); return
        }
        if (project.allVideos.isEmpty()) { error("Adicione um video ou foto para exportar"); return }
        if (busy) return
        ExportPanel.show(this, project, startExport, io) { candidate ->
            edit(candidate)
            if (startExport) beginExport()
        }
    }
    private fun beginExport() {
        if (busy || EditorExportService.state.active) return
        val snapshot = project
        busy = true; preview.suspendSources(); info.text = "Preparando exportacao..."
        main.removeCallbacks(autosave)
        io.execute {
            val result = runCatching {
                require(MediaImport.missing(this, snapshot).isEmpty()) { "Arquivo de midia nao encontrado" }
                EditorExportService.activeProjectSnapshot = snapshot
                store.save(snapshot)
                ProjectStore(File(filesDir, "editor-export")).save(snapshot)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.fold({
                    runCatching { startForegroundService(Intent(this, EditorExportService::class.java).putExtra("project", snapshot.id)) }
                        .onFailure { refresh(); error("Nao foi possivel iniciar exportacao: ${it.message}") }
                }, { refresh(); error("Nao foi possivel preparar exportacao: ${it.message}") })
            }
        }
    }
    private fun showExportComplete(uri: String) {
        if (isFinishing || isDestroyed) return
        val savedName = runCatching {
            contentResolver.query(Uri.parse(uri), arrayOf(android.provider.MediaStore.Video.Media.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: "Vídeo MP4"
        AlertDialog.Builder(this).setTitle("Video exportado")
            .setMessage("$savedName\nMovies/GravadorDeTela/Editados")
            .setPositiveButton("Abrir") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    .onFailure { error("Nenhum player de video instalado") }
            }.setNeutralButton("Compartilhar") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar video"))
            }.setNegativeButton("Fechar", null).show()
    }
}
