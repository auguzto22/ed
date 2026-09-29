package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
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
        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(10)) }
        body.addView(activity.sectionTitle("Edicao automatica inteligente", "Analisa somente este clipe e gera cortes, keyframes, enquadramento e efeitos editaveis."))
        val style = spinner(body, "Estilo", AutoEditStyle.entries.map { it.label }, AutoEditStyle.DYNAMIC.ordinal)
        val intensity = spinner(body, "Intensidade", AutoEditIntensity.entries.map { it.label }, AutoEditIntensity.BALANCED.ordinal)
        val cleanup = spinner(body, "Limpeza de pausas", SpeechCleanup.entries.map { it.label }, SpeechCleanup.BALANCED.ordinal)
        val captions = check(body, "Legendas", words.isNotEmpty(), words.isNotEmpty())
        if (words.isEmpty()) body.addView(activity.label("Importe um SRT temporizado para o Auto Edit reorganizar legendas. O Recly ainda nao possui transcricao local e nao inventara texto.", 11f, EditorStyle.MUTED))
        val clean = check(body, "Limpar pausas longas", true)
        val zoom = check(body, "Smart Zoom", true)
        val reframe = check(body, "Auto Reframe para 9:16", true)
        val audio = check(body, "Equilibrar audio", true)
        lateinit var dialog: Dialog
        body.addView(activity.action("Analisar clipe", true) {
            dialog.dismiss()
            generate(snapshot, index, words, AutoEditOptions(
                style = AutoEditStyle.entries[style.selectedItemPosition],
                intensity = AutoEditIntensity.entries[intensity.selectedItemPosition],
                cleanup = SpeechCleanup.entries[cleanup.selectedItemPosition],
                captions = captions.isChecked,
                cleanPauses = clean.isChecked,
                smartZoom = zoom.isChecked,
                autoReframe = reframe.isChecked,
                audioEnhancement = audio.isChecked,
            ))
        }, LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(12) })
        dialog = activity.sheet("Auto Edit", body)
    }

    private fun spinner(parent: LinearLayout, title: String, values: List<String>, selected: Int): Spinner {
        parent.addView(activity.label(title, 12f, EditorStyle.MUTED))
        return Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(selected)
            parent.addView(this, LinearLayout.LayoutParams(-1, activity.dp(48)))
        }
    }

    private fun check(parent: LinearLayout, title: String, checked: Boolean, enabled: Boolean = true): CheckBox =
        CheckBox(activity).apply {
            text = title; isChecked = checked; isEnabled = enabled; setTextColor(EditorStyle.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            minHeight = activity.dp(44); parent.addView(this)
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
                        else AlertDialog.Builder(activity).setTitle("Auto Edit").setMessage("Nao foi possivel analisar o clipe: ${error.message}").setPositiveButton("OK", null).show()
                    },
                )
            }
        }
    }

    private fun showPreview(original: Project, index: Int, words: List<TimedWord>, options: AutoEditOptions, plan: AutoEditPlan, edited: Project) {
        preview(edited)
        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(8)) }
        body.addView(activity.sectionTitle("Preview pronto", plan.summary()))
        if (plan.warnings.isNotEmpty()) body.addView(activity.label(plan.warnings.joinToString("\n"), 11f, 0xFFFFC66D.toInt()))
        val compare = activity.row()
        compare.addView(activity.action("Original") { preview(original) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { rightMargin = activity.dp(4) })
        compare.addView(activity.action("Editado", true) { preview(edited) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply { leftMargin = activity.dp(4) })
        body.addView(compare)
        lateinit var dialog: Dialog
        body.addView(activity.action("Aplicar Auto Edit", true) {
            dialog.dismiss(); preview(original); apply(edited)
        }, LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(10) })
        body.addView(activity.action("Gerar outra versao") {
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
