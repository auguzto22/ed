package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import com.termex.replay15.editor.autoedit.*
import com.termex.replay15.editor.domain.Project
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AutoEditPanel(
    private val activity: Activity,
    private val project: () -> Project,
    private val selectedIndex: () -> Int,
    private val preview: (Project) -> Unit,
    private val apply: (Project) -> Unit,
    private val processing: (Boolean) -> Unit,
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var cancellation: AutoEditCancellation? = null
    private var progressDialog: AlertDialog? = null
    private var suggestionDialog: Dialog? = null
    private var closing = false

    fun open() {
        val snapshot = project()
        val index = selectedIndex()
        val clip = snapshot.videos.getOrNull(index) ?: run {
            Toast.makeText(activity, "Selecione um clipe existente", Toast.LENGTH_SHORT).show(); return
        }
        val words = ClipAnalyzer.wordsFromCaptions(snapshot.texts, snapshot.startOf(index), clip.durationUs)
        val body = activity.column().apply { setPadding(activity.dp(4), 0, activity.dp(4), activity.dp(10)) }

        // Clipe analisado
        body.addView(activity.row().apply {
            background = activity.reclySurface(EditorStyle.CARD, EditorStyle.BORDER)
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
            addView(activity.column().apply {
                addView(activity.label("CLIPE", 11f, EditorStyle.BLUE).apply {
                    typeface = ReclyType.body(activity, 700); letterSpacing = 0.08f; setPadding(0, 0, 0, 0)
                })
                addView(activity.label(clip.name, 15f).apply {
                    typeface = ReclyType.body(activity, 600); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, activity.dp(2), 0, 0)
                })
                addView(activity.label("Cortes, keyframes, enquadramento e efeitos — tudo editável depois.", 12f, EditorStyle.MUTED).apply {
                    setPadding(0, activity.dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2))

        val style = chips(body, "Estilo", AutoEditStyle.entries.map { it.label }, AutoEditStyle.DYNAMIC.ordinal)
        val intensity = chips(body, "Intensidade", AutoEditIntensity.entries.map { it.label }, AutoEditIntensity.BALANCED.ordinal)
        val cleanup = chips(body, "Limpeza de pausas", SpeechCleanup.entries.map { it.label }, SpeechCleanup.BALANCED.ordinal)

        val options = activity.column().apply { background = activity.reclySurface(EditorStyle.CARD, EditorStyle.BORDER) }
        body.addView(options, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(16) })
        val captions = check(options, "Legendas", "Reorganiza as legendas do clipe", words.isNotEmpty(), words.isNotEmpty())
        val clean = check(options, "Cortar silêncios", "Tira pausas e respirações longas", true)
        val zoom = check(options, "Zoom nos destaques", "Keyframes de zoom nas falas-chave", true)
        val reframe = check(options, "Auto Reframe 9:16", "Mantém o assunto no centro", true)
        val audio = check(options, "Equilibrar áudio", "Nivela volume e voz", true, last = true)
        if (words.isEmpty()) body.addView(activity.label("Gere ou importe legendas para o Auto Edit reorganizá-las. O Recly não inventa texto.", 11f, EditorStyle.MUTED).apply {
            setPadding(activity.dp(4), activity.dp(8), activity.dp(4), 0)
        })

        lateinit var dialog: Dialog
        body.addView(activity.action("Montar minha edição", true) {
            dialog.dismiss()
            generate(snapshot, index, words, AutoEditOptions(
                style = AutoEditStyle.entries[style()],
                intensity = AutoEditIntensity.entries[intensity()],
                cleanup = SpeechCleanup.entries[cleanup()],
                captions = captions.isChecked,
                cleanPauses = clean.isChecked,
                smartZoom = zoom.isChecked,
                autoReframe = reframe.isChecked,
                audioEnhancement = audio.isChecked,
            ))
        }.apply { textSize = 16f; typeface = ReclyType.display(activity) },
            LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(16) })
        body.addView(activity.label("Sai como projeto editável — nada é renderizado até exportar.", 12f, EditorStyle.MUTED).apply {
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        dialog = activity.sheet("Auto Edit", body)
    }

    /** Grupo de chips de escolha única; devolve o índice selecionado. */
    private fun chips(parent: LinearLayout, title: String, values: List<String>, selected: Int): () -> Int {
        parent.addView(activity.heading(title, 15f).apply { setPadding(activity.dp(4), activity.dp(16), 0, activity.dp(8)) })
        val flow = android.widget.GridLayout(activity).apply { columnCount = if (values.size > 4) 3 else 2 }
        var current = selected.coerceIn(0, values.lastIndex)
        val buttons = mutableListOf<android.widget.Button>()
        fun paint() = buttons.forEachIndexed { i, b ->
            val on = i == current
            b.background = if (on) activity.reclySurface(EditorStyle.BLUE_DEEP, EditorStyle.BLUE, EditorStyle.RADIUS_CONTROL.toFloat())
                else activity.reclySurface(EditorStyle.CARD, EditorStyle.BORDER, EditorStyle.RADIUS_CONTROL.toFloat())
            b.setTextColor(if (on) 0xFFFFFFFF.toInt() else EditorStyle.INK)
            b.isSelected = on
        }
        values.forEachIndexed { i, value ->
            val button = activity.pillButton(value, i == current) { current = i; paint() }
            buttons += button
            flow.addView(button, android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = activity.dp(42)
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3))
            })
        }
        paint()
        parent.addView(flow, LinearLayout.LayoutParams(-1, -2))
        return { current }
    }

    private fun check(parent: LinearLayout, title: String, hint: String, checked: Boolean, enabled: Boolean = true, last: Boolean = false): CheckBox {
        val box = CheckBox(activity).apply {
            isChecked = checked; isEnabled = enabled
            buttonTintList = android.content.res.ColorStateList.valueOf(EditorStyle.PRIMARY)
            contentDescription = title
        }
        val rowView = activity.row().apply {
            minimumHeight = activity.dp(56)
            setPadding(activity.dp(12), activity.dp(6), activity.dp(6), activity.dp(6))
            alpha = if (enabled) 1f else 0.45f
            isClickable = enabled
            setOnClickListener { if (box.isEnabled) box.isChecked = !box.isChecked }
            addView(activity.column().apply {
                addView(activity.label(title, 15f).apply { typeface = ReclyType.body(activity, 600); setPadding(0, 0, 0, 0) })
                addView(activity.label(hint, 12f, EditorStyle.MUTED).apply { setPadding(0, activity.dp(1), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(box)
        }
        parent.addView(rowView)
        if (!last) parent.addView(android.view.View(activity).apply { setBackgroundColor(EditorStyle.BORDER) },
            LinearLayout.LayoutParams(-1, activity.dp(EditorStyle.STROKE)))
        return box
    }

    private fun generate(snapshot: Project, index: Int, words: List<TimedWord>, options: AutoEditOptions) {
        cancellation?.cancel()
        val token = AutoEditCancellation().also { cancellation = it }
        processing(true)
        val progressText = activity.label(AutoEditProgress.Inspecting.label, 15f)
        progressDialog = AlertDialog.Builder(activity)
            .setTitle("Analisando clipe")
            .setView(progressText)
            .setNegativeButton("Cancelar") { _, _ -> token.cancel() }
            .setOnCancelListener { token.cancel() }
            .show()
        worker.execute {
            val result = runCatching {
                val clip = snapshot.videos[index]
                val analysis = ClipAnalyzer.analyze(activity, clip, words, token) { stage ->
                    activity.runOnUiThread { if (!activity.isDestroyed) progressText.text = stage.label }
                }
                activity.runOnUiThread { if (!activity.isDestroyed) progressText.text = AutoEditProgress.Planning.label }
                token.check()
                val plan = AutoEditPlanner.plan(analysis, options)
                token.check()
                activity.runOnUiThread { if (!activity.isDestroyed) progressText.text = AutoEditProgress.Validating.label }
                AutoEditPlanner.validate(plan)
                token.check()
                plan to AutoEditExecutor.execute(snapshot, index, plan)
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                progressDialog?.dismiss(); progressDialog = null; processing(false)
                result.fold(
                    onSuccess = { (plan, edited) -> showPreview(snapshot, index, words, options, plan, edited) },
                    onFailure = { error ->
                        if (error is InterruptedException) Toast.makeText(activity, "Auto Edit cancelado", Toast.LENGTH_SHORT).show()
                        else AlertDialog.Builder(activity).setTitle("Auto Edit").setMessage("Não foi possível analisar o clipe: ${error.message}").setPositiveButton("OK", null).show()
                    },
                )
            }
        }
    }

    private fun showPreview(original: Project, index: Int, words: List<TimedWord>, options: AutoEditOptions, plan: AutoEditPlan, edited: Project) {
        preview(edited)
        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(8)) }
        body.addView(activity.sectionTitle("Prévia pronta", plan.summary()))
        if (plan.warnings.isNotEmpty()) body.addView(activity.label(plan.warnings.joinToString("\n"), 11f, EditorStyle.ACCENT))
        val compare = activity.row()
        compare.addView(activity.action("Original") { preview(original) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { rightMargin = activity.dp(4) })
        compare.addView(activity.action("Editado", true) { preview(edited) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { leftMargin = activity.dp(4) })
        body.addView(compare)
        lateinit var dialog: Dialog
        body.addView(activity.action("Aplicar edição", true) {
            dialog.dismiss(); preview(original); apply(edited)
        }, LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(10) })
        body.addView(activity.action("Gerar outra versão") {
            dialog.dismiss(); preview(original); generate(original, index, words, options.copy(variation = options.variation + 1))
        }, LinearLayout.LayoutParams(-1, activity.dp(48)).apply { topMargin = activity.dp(6) })
        body.addView(activity.action("Cancelar") { dialog.dismiss(); preview(original) }, LinearLayout.LayoutParams(-1, activity.dp(48)).apply { topMargin = activity.dp(6) })
        dialog = activity.sheet("Auto Edit", body)
        suggestionDialog = dialog
        dialog.setOnDismissListener {
            suggestionDialog = null
            if (!closing && !activity.isDestroyed) preview(original)
        }
    }

    fun close() {
        closing = true
        cancellation?.cancel(); progressDialog?.dismiss(); progressDialog = null
        suggestionDialog?.dismiss(); suggestionDialog = null
        worker.shutdownNow()
    }
}
