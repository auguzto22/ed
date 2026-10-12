package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.recly.editor.R
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.media.MediaSourceAccess
import com.termex.replay15.editor.project.ProjectStore
import com.termex.replay15.account.AccountGate
import com.termex.replay15.account.AccountManager
import com.termex.replay15.account.AuthActivity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Recly — Início.
 *
 * Só edição: criar projeto, Auto Edit, analisar um vídeo que vende e os projetos recentes.
 * Nada de gravações de tela ou replays; o editor funciona sozinho.
 */
class EditorLibraryActivity : Activity() {

    companion object {
        private const val REQ_PICK_VIDEO = 810
        private const val COLLAPSED_COUNT = 4

        /** Extra lido pelo EditorActivity para abrir uma ferramenta logo após importar. */
        const val EXTRA_OPEN_TOOL = "recly_open_tool"
        const val TOOL_AUTO_EDIT = "auto_edit"
        const val TOOL_ANALYZE = "analyze"

        /** Tons dos cartões sem miniatura, na ordem da grade. */
        private val TONES = intArrayOf(0xFF2A2D35.toInt(), 0xFF332D28.toInt(), 0xFF2A2F2B.toInt(), 0xFF2E2B34.toInt())
    }

    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = ConcurrentHashMap<String, Bitmap>()
    private lateinit var store: ProjectStore
    private lateinit var cardEmptyProjects: View
    private lateinit var listProjects: LinearLayout
    private lateinit var btnSeeAll: TextView
    private var projects: List<Project> = emptyList()
    private var expanded = false
    private var pendingTool: String? = null
    private var warnedUnreadable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Primeira abertura: sem conta, vai para o cadastro e volta para cá depois.
        if (!AccountGate.ensure(this)) return
        setContentView(R.layout.activity_editor_library)
        store = ProjectStore(File(filesDir, "editor-projects"))
        pendingTool = savedInstanceState?.getString(EXTRA_OPEN_TOOL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        applyInsets()
        applyType()

        cardEmptyProjects = findViewById(R.id.cardEmptyProjects)
        listProjects = findViewById(R.id.listProjects)
        btnSeeAll = findViewById(R.id.btnSeeAll)

        findViewById<View>(R.id.btnNewProject).setOnClickListener { openVideoPicker(null) }
        findViewById<View>(R.id.btnImportVideo).setOnClickListener { openVideoPicker(null) }
        findViewById<View>(R.id.btnAutoEdit).setOnClickListener { openVideoPicker(TOOL_AUTO_EDIT) }
        findViewById<View>(R.id.cardAnalyze).setOnClickListener { openVideoPicker(TOOL_ANALYZE) }
        findViewById<View>(R.id.navPresets).setOnClickListener { openVideoPicker(TOOL_AUTO_EDIT) }
        findViewById<View>(R.id.navSettings).setOnClickListener { showAccountSheet() }
        findViewById<View>(R.id.btnProfile).setOnClickListener { showAccountSheet() }
        findViewById<View>(R.id.navProjects).setOnClickListener {
            findViewById<android.widget.ScrollView>(R.id.libraryScroll).smoothScrollTo(0, 0)
        }
        btnSeeAll.setOnClickListener { expanded = !expanded; renderProjects() }

        installEditorMotion(findViewById(R.id.libraryRoot))
        showAccountInitial()
        intro()
    }

    /** O botão de perfil mostra a inicial do nome da conta, como no protótipo. */
    private fun showAccountInitial() {
        val session = AccountManager.session(this) ?: return
        val initial = session.displayName.firstOrNull()?.uppercaseChar() ?: return
        findViewById<View>(R.id.profileIcon).visibility = View.GONE
        findViewById<TextView>(R.id.profileInitial).apply {
            text = initial.toString()
            typeface = ReclyType.body(context, 700)
            visibility = View.VISIBLE
        }
        findViewById<View>(R.id.btnProfile).contentDescription = "Conta de ${session.displayName}"
    }

    override fun onResume() {
        super.onResume()
        loadProjects()
        // Renova o token em segundo plano; só desloga se o servidor recusar a sessão.
        AccountManager.refreshIfNeeded(this) { AccountGate.openAuth(this, null, AuthActivity.MODE_SIGN_IN, expired = true) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_OPEN_TOOL, pendingTool)
    }

    /** Barras do sistema transparentes: o cabeçalho e a navegação recebem o recuo. */
    @Suppress("DEPRECATION")
    private fun applyInsets() {
        val root = findViewById<View>(R.id.libraryRoot)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun applyType() {
        val display = ReclyType.display(this)
        listOf(R.id.libraryTitle, R.id.libraryBrand, R.id.labelNewProject, R.id.labelAutoEdit,
            R.id.recentTitle, R.id.emptyTitle).forEach { findViewById<TextView>(it).typeface = display }
        listOf(R.id.analyzeTitle, R.id.labelImport, R.id.navProjectsText)
            .forEach { findViewById<TextView>(it).typeface = ReclyType.body(this, 700) }
        listOf(R.id.analyzeBody, R.id.btnSeeAll)
            .forEach { findViewById<TextView>(it).typeface = ReclyType.body(this, 500) }

        val title = findViewById<TextView>(R.id.libraryTitle)
        val text = getString(R.string.library_title)
        val highlight = getString(R.string.library_title_highlight)
        val spannable = SpannableString(text)
        val at = text.indexOf(highlight)
        if (at >= 0) {
            spannable.setSpan(ForegroundColorSpan(EditorStyle.ACCENT), at, at + highlight.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        title.text = spannable
    }

    /** Entrada curta e escalonada, como no protótipo. */
    private fun intro() {
        val ids = intArrayOf(R.id.libraryTitle, R.id.btnNewProject, R.id.cardAnalyze, R.id.recentTitle)
        ids.forEachIndexed { i, id ->
            val v = findViewById<View>(id)
            val target = if (id == R.id.btnNewProject) v.parent as View else v
            target.alpha = 0f
            target.translationY = dp(12).toFloat()
            target.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(420L)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).start()
        }
    }

    private fun openVideoPicker(tool: String?) {
        pendingTool = tool
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQ_PICK_VIDEO) }
            .onFailure { Toast.makeText(this, "Seletor de vídeos indisponível", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_VIDEO) return
        val tool = pendingTool
        pendingTool = null
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val persisted = runCatching { MediaSourceAccess.persistReadPermission(contentResolver, uri, data.flags) }.getOrDefault(false)
        if (!persisted) {
            Toast.makeText(this, "Não foi possível manter acesso a este vídeo", Toast.LENGTH_LONG).show()
            return
        }
        val open = Intent(this, EditorActivity::class.java).putExtra("uri", uri.toString())
        if (tool != null) open.putExtra(EXTRA_OPEN_TOOL, tool)
        startActivity(open)
    }

    private fun loadProjects() {
        io.execute {
            val listing = runCatching { store.listWithFailures() }.getOrNull()
            val loaded = listing?.projects.orEmpty().sortedByDescending { it.modifiedAt }
            val unreadable = listing?.unreadable?.size ?: 0
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                projects = loaded
                renderProjects()
                // A file that no longer decodes used to vanish silently; say so once per session.
                if (unreadable > 0 && !warnedUnreadable) {
                    warnedUnreadable = true
                    Toast.makeText(this,
                        if (unreadable == 1) "1 projeto não pôde ser aberto e foi ocultado."
                        else "$unreadable projetos não puderam ser abertos e foram ocultados.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderProjects() {
        listProjects.removeAllViews()
        if (projects.isEmpty()) {
            cardEmptyProjects.visibility = View.VISIBLE
            listProjects.visibility = View.GONE
            btnSeeAll.visibility = View.GONE
            return
        }
        cardEmptyProjects.visibility = View.GONE
        listProjects.visibility = View.VISIBLE
        btnSeeAll.visibility = if (projects.size > COLLAPSED_COUNT) View.VISIBLE else View.GONE
        btnSeeAll.text = if (expanded) "Mostrar menos" else "Ver todos"

        val shown = if (expanded) projects else projects.take(COLLAPSED_COUNT)
        shown.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            pair.forEachIndexed { col, project ->
                val index = rowIndex * 2 + col
                row.addView(projectCard(project, index), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (col == 0) marginEnd = dp(7) else marginStart = dp(7)
                })
            }
            if (pair.size == 1) row.addView(spacer(), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(7) })
            listProjects.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = dp(14) })
        }
        installEditorMotion(listProjects)
    }

    private fun spacer() = View(this)

    private fun projectCard(project: Project, index: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Abrir ${project.name}"
            setOnClickListener {
                startActivity(Intent(this@EditorLibraryActivity, EditorActivity::class.java).putExtra("project", project.id))
            }
            setOnLongClickListener { confirmDelete(project); true }
        }

        val thumb = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(TONES[index % TONES.size])
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1.5f), EditorStyle.BORDER)
            }
            clipToOutline = true
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        thumb.addView(image, FrameLayout.LayoutParams(-1, -1))
        loadThumbnail(project, image)

        thumb.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.rc_play_disc)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_rc_play)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER))
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))

        thumb.addView(badge(clock(project.durationUs), mono = true),
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(7), 0, 0, dp(8)) })
        formatLabel(project)?.let { format ->
            thumb.addView(badge(format, mono = false),
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(7), dp(7), 0) })
        }
        card.addView(thumb, LinearLayout.LayoutParams(-1, dp(132)))

        card.addView(TextView(this).apply {
            text = project.name
            textSize = 14f
            setTextColor(EditorStyle.INK)
            typeface = ReclyType.body(context, 600)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(7), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = editedLabel(project.modifiedAt)
            textSize = 12f
            setTextColor(EditorStyle.MUTED)
            typeface = ReclyType.body(context, 500)
            maxLines = 1
        })
        return card
    }

    private fun badge(value: String, mono: Boolean) = TextView(this).apply {
        text = value
        textSize = 11f
        setTextColor(EditorStyle.INK)
        typeface = if (mono) ReclyType.mono(context) else ReclyType.body(context, 700)
        setBackgroundResource(R.drawable.rc_badge_dark)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Primeiro quadro do primeiro clipe, em segundo plano; sem miniatura o cartão fica no tom sólido. */
    private fun loadThumbnail(project: Project, target: ImageView) {
        val clip = project.videos.firstOrNull { !it.image } ?: return
        val key = "${project.id}:${clip.id}:${clip.inUs}"
        thumbs[key]?.let { target.setImageBitmap(it); return }
        target.tag = key
        io.execute {
            val bitmap = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, Uri.parse(clip.uri))
                    retriever.getScaledFrameAtTime(clip.inUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
                } finally {
                    retriever.release()
                }
            }.getOrNull() ?: return@execute
            thumbs[key] = bitmap
            runOnUiThread {
                if (!isDestroyed && target.tag == key) {
                    target.alpha = 0f
                    target.setImageBitmap(bitmap)
                    target.animate().alpha(1f).setDuration(180L).start()
                }
            }
        }
    }

    private fun formatLabel(project: Project): String? {
        val ratio = when {
            project.aspect > 0f -> project.aspect
            else -> project.videos.firstOrNull()?.let { clip ->
                val rotated = clip.rotation % 180 != 0
                val w = if (rotated) clip.height else clip.width
                val h = if (rotated) clip.width else clip.height
                if (w > 0 && h > 0) w.toFloat() / h else null
            } ?: return null
        }
        val known = listOf(9f / 16f to "9:16", 4f / 5f to "4:5", 1f to "1:1", 4f / 3f to "4:3", 16f / 9f to "16:9")
        return known.minByOrNull { kotlin.math.abs(it.first - ratio) }?.takeIf { kotlin.math.abs(it.first - ratio) < 0.04f }?.second
    }

    private fun editedLabel(modifiedAt: Long): String {
        if (modifiedAt <= 0L) return "Projeto salvo"
        val relative = DateUtils.getRelativeTimeSpanString(modifiedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return "Editado $relative".replace("Editado em ", "Editado ")
    }

    private fun clock(us: Long): String {
        val totalSec = (us / 1_000_000L).coerceAtLeast(0)
        return String.format(java.util.Locale.ROOT, "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun confirmDelete(project: Project) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Excluir \"${project.name}\"?")
            .setMessage("Só o projeto é apagado. Os vídeos originais continuam no aparelho.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                io.execute {
                    runCatching { store.delete(project.id) }
                    loadProjects()
                }
            }
            .show()
    }

    /** Ajustes: conta (nome, e-mail, sair, excluir) e informações do app. */
    private fun showAccountSheet() {
        val session = AccountManager.session(this)
        val body = column().apply { setPadding(dp(4), 0, dp(4), dp(8)) }
        if (session != null) {
            body.addView(row().apply {
                background = reclySurface(EditorStyle.CARD, EditorStyle.BORDER)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(context).apply {
                    text = (session.displayName.firstOrNull()?.uppercaseChar() ?: '?').toString()
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setTextColor(EditorStyle.ON_ACCENT)
                    typeface = ReclyType.body(context, 700)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(EditorStyle.PRIMARY) }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(column().apply {
                    setPadding(dp(12), 0, 0, 0)
                    addView(TextView(context).apply {
                        text = session.name.ifBlank { session.displayName }
                        textSize = 16f; setTextColor(EditorStyle.INK); typeface = ReclyType.body(context, 700)
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        text = session.email
                        textSize = 13f; setTextColor(EditorStyle.MUTED); typeface = ReclyType.body(context, 500)
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        lateinit var dialog: android.app.Dialog
        body.addView(action("Sobre o Recly") { dialog.dismiss(); showAbout() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(14) })
        if (session != null) {
            body.addView(action("Sair da conta") {
                dialog.dismiss()
                AccountManager.signOut(this) { AccountGate.openAuth(this, null, AuthActivity.MODE_SIGN_IN) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) })
            body.addView(TextView(this).apply {
                text = "Excluir conta"
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(EditorStyle.PRIMARY_SOFT)
                typeface = ReclyType.body(context, 600)
                isClickable = true; isFocusable = true
                setOnClickListener { dialog.dismiss(); confirmDeleteAccount() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
        }
        dialog = sheet("Ajustes", body)
    }

    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Excluir sua conta?")
            .setMessage("A conta e o login são apagados para sempre. Os projetos e vídeos que estão neste aparelho continuam aqui.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                AccountManager.deleteAccount(this) { result ->
                    result.onSuccess {
                        Toast.makeText(this, "Conta excluída.", Toast.LENGTH_LONG).show()
                        AccountGate.openAuth(this, null, AuthActivity.MODE_SIGN_UP)
                    }.onFailure { error ->
                        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Não foi possível excluir")
                            .setMessage(error.message ?: "Tente de novo.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showAbout() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Recly $version".trim())
            .setMessage("Editor de vídeo com legendas automáticas e Auto Edit.\n\nSeus vídeos originais nunca são alterados: cada projeto guarda só as decisões de edição.\n\nToque e segure um projeto para excluí-lo.")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
