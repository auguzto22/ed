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
        // The analysis runs beside the editor in a floating card; editing stays available.
        val card = EditorOverlayHost.task("Analisando clipe") { token.cancel() }
        val fallbackText = activity.label(AutoEditProgress.Inspecting.label, 15f)
        if (card == null) {
            processing(true)
            progressDialog = AlertDialog.Builder(activity)
                .setTitle("Analisando clipe")
                .setView(fallbackText)
                .setNegativeButton("Cancelar") { _, _ -> token.cancel() }
                .setOnCancelListener { token.cancel() }
                .show()
        }
        fun stage(label: String, progress: Float) = activity.runOnUiThread {
            if (activity.isDestroyed) return@runOnUiThread
            card?.update(label, progress) ?: run { fallbackText.text = label }
        }
        stage(AutoEditProgress.Inspecting.label, .05f)
        val clip = snapshot.videos[index]
        worker.execute {
            val result = runCatching {
                val analysis = ClipAnalyzer.analyze(activity, clip, words, token) { progress ->
                    stage(progress.label, when (progress) {
                        AutoEditProgress.Audio -> .25f
                        AutoEditProgress.Visuals -> .55f
                        else -> .1f
                    })
                }
                stage(AutoEditProgress.Planning.label, .8f)
                token.check()
                val plan = AutoEditPlanner.plan(analysis, options)
                token.check()
                stage(AutoEditProgress.Validating.label, .92f)
                AutoEditPlanner.validate(plan)
                token.check()
                plan
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (card == null) { progressDialog?.dismiss(); progressDialog = null; processing(false) }
                result.fold(
                    onSuccess = { plan ->
                        // The user may have kept editing: apply the plan to the project as it is now,
                        // as long as the analysed clip itself was not changed.
                        val current = project()
                        val currentIndex = current.videos.indexOfFirst { it.id == clip.id }
                        if (currentIndex < 0 || current.videos[currentIndex] != clip) {
                            card?.fail("O clipe mudou durante a análise. Analise de novo.")
                                ?: Toast.makeText(activity, "O clipe mudou durante a analise", Toast.LENGTH_SHORT).show()
                            return@fold
                        }
                        val edited = runCatching { AutoEditExecutor.execute(current, currentIndex, plan) }.getOrElse { error ->
                            card?.fail("Não foi possível montar a edição: ${error.message}"); return@fold
                        }
                        if (card != null) card.succeed(plan.summary().lineSequence().firstOrNull().orEmpty().ifBlank { "Edição pronta" }, "Ver resultado") {
                            showPreview(current, currentIndex, words, options, plan, edited)
                        } else showPreview(current, currentIndex, words, options, plan, edited)
                    },
                    onFailure = { error ->
                        if (error is InterruptedException) {
                            card?.fail("Análise cancelada") ?: Toast.makeText(activity, "Auto Edit cancelado", Toast.LENGTH_SHORT).show()
                        } else if (card != null) card.fail("Não foi possível analisar: ${error.message}")
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
