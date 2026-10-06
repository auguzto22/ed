package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.termex.replay15.editor.domain.CanvasFill
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.mapVideo
import com.termex.replay15.editor.reframe.AutoReframeAnalysis
import com.termex.replay15.editor.reframe.AutoReframeAnalyzer
import com.termex.replay15.editor.reframe.AutoReframeCancelledException
import com.termex.replay15.editor.reframe.ReframeAspect
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Target selection, local subject analysis and review/apply flow for automatic reframing. */
class AutoReframePanel(
    private val activity: Activity,
    private val project: () -> Project,
    private val selectedIndex: () -> Int,
    private val preview: (Project) -> Unit,
    private val apply: (Project) -> Unit,
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var cancellation: AtomicBoolean? = null
    private var progressDialog: AlertDialog? = null
    private var suggestionDialog: Dialog? = null
    private var closing = false

    fun open() {
        val snapshot = project()
        val index = selectedIndex()
        val clip = snapshot.videos.getOrNull(index) ?: run {
            Toast.makeText(activity, "Selecione um clipe de video", Toast.LENGTH_SHORT).show()
            return
        }
        if (clip.image) {
            Toast.makeText(activity, "Auto Reframe requer um clipe de video", Toast.LENGTH_SHORT).show()
            return
        }

        val aspects = ReframeAspect.entries.filter { it != ReframeAspect.ORIGINAL }
        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(10)) }
        body.addView(activity.sectionTitle(
            "Auto Reframe",
            "Detecta rosto ou corpo localmente e anima o enquadramento deste clipe. A proporcao escolhida vale para o projeto inteiro.",
        ))
        body.addView(activity.label("Formato de destino", 12f, EditorStyle.MUTED))
        val aspectSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, aspects.map { it.label })
            setSelection(aspects.indexOfFirst { it.label == "9:16" }.coerceAtLeast(0))
        }
        body.addView(aspectSpinner, LinearLayout.LayoutParams(-1, activity.dp(48)))

        lateinit var dialog: Dialog
        body.addView(activity.action("Analisar clipe", true) {
            val target = aspects[aspectSpinner.selectedItemPosition]
            dialog.dismiss()
            analyze(snapshot, index, target)
        }, LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(12) })
        dialog = activity.sheet("Auto Reframe", body)
    }

    private fun analyze(snapshot: Project, index: Int, target: ReframeAspect) {
        val clip = snapshot.videos.getOrNull(index) ?: return
        cancellation?.set(true)
        val token = AtomicBoolean(false).also { cancellation = it }
        val progressText = activity.label("Preparando deteccao local...", 13f, EditorStyle.MUTED)
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        val progressBody = activity.column().apply {
            setPadding(activity.dp(20), activity.dp(16), activity.dp(20), activity.dp(8))
            addView(progressText)
            addView(progressBar, LinearLayout.LayoutParams(-1, activity.dp(24)).apply {
                topMargin = activity.dp(10)
            })
        }
        progressDialog = AlertDialog.Builder(activity)
            .setTitle("Analisando sujeito")
            .setView(progressBody)
            .setNegativeButton("Cancelar") { _, _ -> token.set(true) }
            .setOnCancelListener { token.set(true) }
            .show()

        worker.execute {
            val result = runCatching {
                AutoReframeAnalyzer(activity.applicationContext).analyze(
                    clip = clip,
                    target = target,
                    isCancelled = token::get,
                    onProgress = { completed, total ->
                        activity.runOnUiThread {
                            if (!activity.isDestroyed && progressDialog?.isShowing == true) {
                                progressBar.isIndeterminate = false
                                progressBar.max = total
                                progressBar.progress = completed
                                progressText.text = "Detectando rosto/corpo... $completed / $total quadros"
                            }
                        }
                    },
                )
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || closing) return@runOnUiThread
                progressDialog?.dismiss()
                progressDialog = null
                cancellation = null
                result.fold(
                    onSuccess = { analysis -> showSuggestion(snapshot, clip.id, analysis) },
                    onFailure = { error ->
                        if (error is AutoReframeCancelledException || token.get()) {
                            Toast.makeText(activity, "Auto Reframe cancelado", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(activity)
                                .setTitle("Auto Reframe")
                                .setMessage("Nao foi possivel analisar este clipe: ${error.message ?: "falha na deteccao"}")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    },
                )
            }
        }
    }

    private fun showSuggestion(snapshot: Project, clipId: String, analysis: AutoReframeAnalysis) {
        if (analysis.points.isEmpty() || analysis.subjectsDetected == 0) {
            Toast.makeText(activity, "Nenhum rosto ou corpo foi detectado; projeto preservado", Toast.LENGTH_LONG).show()
            return
        }
        val updatedClip = snapshot.mapVideo(clipId) { it.copy(keyframes = analysis.keyframes) }
        if (updatedClip == snapshot) {
            Toast.makeText(activity, "Nao foi possivel criar keyframes neste clipe", Toast.LENGTH_LONG).show()
            return
        }
        val reframed = updatedClip.copy(aspect = analysis.target.ratio, canvasFill = CanvasFill.FILL)
        preview(reframed)

        val body = activity.column().apply { setPadding(activity.dp(8), 0, activity.dp(8), activity.dp(8)) }
        body.addView(activity.sectionTitle(
            "Enquadramento pronto",
            "${analysis.target.label} · ${analysis.subjectsDetected} deteccoes · ${analysis.points.size} pontos suaves",
        ))
        body.addView(activity.label("O reenquadramento usa keyframes do clipe e pode ser desfeito como uma acao.", 12f, EditorStyle.MUTED))
        val compare = activity.row().apply { setPadding(0, activity.dp(8), 0, activity.dp(4)) }
        compare.addView(activity.action("Original") { preview(snapshot) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply {
            rightMargin = activity.dp(4)
        })
        compare.addView(activity.action("Reenquadrado", true) { preview(reframed) }, LinearLayout.LayoutParams(0, activity.dp(46), 1f).apply {
            leftMargin = activity.dp(4)
        })
        body.addView(compare)

        lateinit var dialog: Dialog
        var accepted = false
        body.addView(activity.action("Aplicar Auto Reframe", true) {
            accepted = true
            dialog.dismiss()
            preview(snapshot)
            apply(reframed)
        }, LinearLayout.LayoutParams(-1, activity.dp(52)).apply { topMargin = activity.dp(8) })
        body.addView(activity.action("Cancelar") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, activity.dp(46)))
        dialog = activity.sheet("Auto Reframe", body)
        suggestionDialog = dialog
        dialog.setOnDismissListener {
            suggestionDialog = null
            if (!accepted && !closing && !activity.isDestroyed) preview(snapshot)
        }
    }

    fun close() {
        closing = true
        cancellation?.set(true)
        progressDialog?.dismiss()
        progressDialog = null
        suggestionDialog?.dismiss()
        suggestionDialog = null
        worker.shutdown()
    }
}
