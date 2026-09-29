package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.termex.replay15.editor.autoedit.ClipAnalyzer
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.reference.*
import java.io.File
import java.util.concurrent.Executors

class ReferenceStylePanel(
    private val activity: Activity,
    private val project: () -> Project,
    private val selectedIndex: () -> Int,
    private val pickReference: () -> Unit,
    private val preview: (Project) -> Unit,
    private val apply: (Project) -> Unit,
    private val processing: (Boolean) -> Unit,
) {
    private val store = ReferenceStyleStore(File(activity.filesDir, "reference-styles"))
    private val worker = Executors.newSingleThreadExecutor()
    private var cancellation: ReferenceAnalysisCancellation? = null
    private var dialog: Dialog? = null
    private var previewDialog: Dialog? = null
    private var closing = false

    fun open() {
        if (selectedIndex() !in project().videos.indices) {
            Toast.makeText(activity, "Selecione um clipe para aplicar um estilo", Toast.LENGTH_SHORT).show(); return
        }
        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(8)) }
        body.addView(activity.sectionTitle("Estilo de referencia", "Analisa a linguagem de edicao de um unico video. Nenhum frame, audio ou asset e copiado para o projeto."))
        body.addView(activity.action("Usar novo video como referencia", true) { dialog?.dismiss(); pickReference() }, LinearLayout.LayoutParams(-1, activity.dp(52)))
        val profiles = store.list()
        if (profiles.isEmpty()) body.addView(activity.label("Nenhum estilo salvo ainda.", 12f, EditorStyle.MUTED))
        else {
            body.addView(activity.sectionTitle("Estilos salvos"))
            profiles.forEach { profile ->
                body.addView(activity.action(profile.name) { dialog?.dismiss(); showProfile(profile) }, LinearLayout.LayoutParams(-1, activity.dp(48)))
            }
        }
        dialog = activity.sheet("Reference Style", body)
    }

    fun analyze(uri: Uri) {
        val token = ReferenceAnalysisCancellation().also { cancellation = it }
        processing(true)
        val progressText = activity.label(ReferenceAnalysisStage.PREPARING.label, 15f)
        val progressDialog = AlertDialog.Builder(activity).setTitle("Analisando referencia").setView(progressText)
            .setNegativeButton("Cancelar") { _, _ -> token.cancel() }.setOnCancelListener { token.cancel() }.show()
        worker.execute {
            val result = runCatching {
                val info = ReferenceMediaProbe.probe(activity, uri)
                store.findValid(info.fingerprint) ?: ReferenceStyleAnalyzer(activity).analyze(uri, token) { stage ->
                    activity.runOnUiThread { if (!activity.isDestroyed) progressText.text = stage.label }
                }.also(store::save)
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                progressDialog.dismiss(); processing(false)
                result.fold(::showProfile) { error ->
                    if (error is InterruptedException) Toast.makeText(activity, "Analise cancelada", Toast.LENGTH_SHORT).show()
                    else AlertDialog.Builder(activity).setTitle("Video de referencia").setMessage("Nao foi possivel analisar: ${error.message ?: "arquivo ou codec indisponivel"}").setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun showProfile(profile: ReferenceStyleProfile) {
        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(8)) }
        body.addView(activity.sectionTitle("Estilo extraido", profile.summary().joinToString("\n")))
        val details = buildList {
            if (profile.cutCount > 0) add("Corte mediano: %.2fs".format(profile.medianCutIntervalUs / 1_000_000.0))
            if (profile.medianZoomScale > 1f) add("Zoom: %.0f%%".format((profile.medianZoomScale - 1f) * 100))
            if (profile.medianShakeDurationUs > 0) add("Shake: ${profile.medianShakeDurationUs / 1000}ms")
            add("Confianca: ${(profile.overallConfidence * 100).toInt()}%")
        }
        body.addView(activity.label(details.joinToString(" · "), 11f, EditorStyle.MUTED))
        val name = EditText(activity).apply { setText(profile.name); hint = "Nome do estilo"; isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT }
        body.addView(name)
        body.addView(activity.action("Salvar nome") {
            runCatching { store.rename(profile.id, name.text.toString()) }.fold({ Toast.makeText(activity, "Estilo salvo", Toast.LENGTH_SHORT).show() }, { name.error = it.message })
        })
        body.addView(activity.action("Aplicar ao clipe selecionado", true) { dialog?.dismiss(); applyProfile(profile) }, LinearLayout.LayoutParams(-1, activity.dp(52)))
        if (profile.warnings.isNotEmpty()) body.addView(activity.label(profile.warnings.joinToString("\n"), 11f, 0xFFFFC66D.toInt()))
        dialog = activity.sheet("Reference Style", body)
    }

    private fun applyProfile(profile: ReferenceStyleProfile) {
        val snapshot = project(); val index = selectedIndex(); val clip = snapshot.videos.getOrNull(index) ?: return
        val words = ClipAnalyzer.wordsFromCaptions(snapshot.texts, snapshot.startOf(index), clip.durationUs)
        val token = ReferenceAnalysisCancellation().also { cancellation = it }; processing(true)
        val progressText = activity.label("Analisando o novo clipe", 15f)
        val progressDialog = AlertDialog.Builder(activity).setTitle("Aplicando estilo").setView(progressText)
            .setNegativeButton("Cancelar") { _, _ -> token.cancel() }.setOnCancelListener { token.cancel() }.show()
        worker.execute {
            val result = runCatching {
                val autoToken = com.termex.replay15.editor.autoedit.AutoEditCancellation()
                token.check()
                val analysis = ClipAnalyzer.analyze(activity, clip, words, autoToken) { stage ->
                    token.check(); activity.runOnUiThread { if (!activity.isDestroyed) progressText.text = stage.label }
                }
                token.check(); activity.runOnUiThread { if (!activity.isDestroyed) progressText.text = "Criando plano editavel" }
                val plan = ReferenceStylePlanner.plan(profile, analysis)
                token.check(); plan to ReferenceStyleExecutor.execute(snapshot, index, plan, profile)
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                progressDialog.dismiss(); processing(false)
                result.fold({ (plan, edited) -> showPreview(snapshot, profile, plan, edited) }, { error ->
                    if (error is InterruptedException) Toast.makeText(activity, "Aplicacao cancelada", Toast.LENGTH_SHORT).show()
                    else AlertDialog.Builder(activity).setTitle("Reference Style").setMessage("Nao foi possivel criar o plano: ${error.message}").setPositiveButton("OK", null).show()
                })
            }
        }
    }

    private fun showPreview(original: Project, profile: ReferenceStyleProfile, plan: ReferenceStyleEditPlan, edited: Project) {
        preview(edited)
        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(8)) }
        body.addView(activity.sectionTitle("Preview do estilo", plan.base.summary()))
        if (plan.unsupportedEffects.isNotEmpty()) body.addView(activity.label("Nao suportado: ${plan.unsupportedEffects.joinToString { it.name.lowercase() }}", 11f, 0xFFFFC66D.toInt()))
        val compare = activity.row()
        compare.addView(activity.action("Original") { preview(original) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f))
        compare.addView(activity.action("Reference Style", true) { preview(edited) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f))
        body.addView(compare)
        lateinit var sheet: Dialog
        body.addView(activity.action("Aplicar", true) { sheet.dismiss(); preview(original); apply(edited) }, LinearLayout.LayoutParams(-1, activity.dp(52)))
        body.addView(activity.action("Cancelar") { sheet.dismiss(); preview(original) })
        sheet = activity.sheet(profile.name, body); previewDialog = sheet
        sheet.setOnDismissListener { previewDialog = null; if (!closing && !activity.isDestroyed) preview(original) }
    }

    fun close() {
        closing = true; cancellation?.cancel(); dialog?.dismiss(); previewDialog?.dismiss(); worker.shutdownNow()
    }
}
