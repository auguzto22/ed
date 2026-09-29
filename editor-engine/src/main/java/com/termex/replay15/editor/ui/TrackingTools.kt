package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.TrackingTrack
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.tracking.TemplateMotionTracker
import com.termex.replay15.editor.tracking.TrackingFrame
import com.termex.replay15.editor.tracking.TrackingRegion
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object TrackingTools {

    private val trackingExecutor = Executors.newSingleThreadExecutor()

    fun show(
        activity: Activity,
        clip: VideoClip,
        currentTimelineUs: Long,
        overlay: TrackingOverlayView,
        onUpdateClip: (VideoClip) -> Unit,
        onOpenMask: () -> Unit,
    ): Unit = with(activity) {
        overlay.visibility = View.VISIBLE
        overlay.isEnabled = true

        val body = column()
        lateinit var mainDialog: Dialog

        val currentSourceUs = clip.timeMap.sourceAt(currentTimelineUs.coerceIn(0L, clip.durationUs))

        body.addView(sectionTitle(
            "Rastreamento de Movimento",
            "Mova a mira sobre o objeto ou rosto no vídeo para acompanhar seu movimento automaticamente."
        ))

        // Region sliders to give fine-grained control in addition to direct screen touch
        val posXSlider = slider(body, "Posição Horizontal (%)", (overlay.region.centerX * 100).toInt(), 95, 5) {
            overlay.region = overlay.region.copy(centerX = it / 100f)
        }
        val posYSlider = slider(body, "Posição Vertical (%)", (overlay.region.centerY * 100).toInt(), 95, 5) {
            overlay.region = overlay.region.copy(centerY = it / 100f)
        }
        val widthSlider = slider(body, "Largura do Alvo (%)", (overlay.region.width * 100).toInt(), 90, 5) {
            overlay.region = overlay.region.copy(width = it / 100f)
        }
        val heightSlider = slider(body, "Altura do Alvo (%)", (overlay.region.height * 100).toInt(), 90, 5) {
            overlay.region = overlay.region.copy(height = it / 100f)
        }

        // When user touches the overlay directly, keep sliders in sync
        overlay.onRegionChanged = { r ->
            posXSlider.progress = (r.centerX * 100).toInt()
            posYSlider.progress = (r.centerY * 100).toInt()
            widthSlider.progress = (r.width * 100).toInt()
            heightSlider.progress = (r.height * 100).toInt()
        }

        body.addView(label("Intervalo de rastreio", 13f, EditorStyle.MUTED))
        val rangeOptions = listOf("Do cursor até o final", "Clipe inteiro")
        val rangeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, rangeOptions)
            setSelection(0)
        }
        body.addView(rangeSpinner, LinearLayout.LayoutParams(-1, dp(52)))

        body.addView(label("Taxa de amostragem", 13f, EditorStyle.MUTED))
        val rateOptions = listOf("Padrão (10 fps - Rápido)", "Alta precisão (15 fps)")
        val rateSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@with, android.R.layout.simple_spinner_dropdown_item, rateOptions)
            setSelection(0)
        }
        body.addView(rateSpinner, LinearLayout.LayoutParams(-1, dp(52)))

        // Start tracking button
        body.addView(action("🎯 Iniciar Rastreamento", accent = true) {
            val fromStart = rangeSpinner.selectedItemPosition == 1
            val startUs = if (fromStart) clip.inUs else currentSourceUs
            val endUs = clip.outUs
            val fps = if (rateSpinner.selectedItemPosition == 1) 15 else 10
            val stepUs = 1_000_000L / fps

            val selectedRegion = overlay.region

            startTrackingProcess(
                activity = activity,
                clip = clip,
                startUs = startUs,
                endUs = endUs,
                stepUs = stepUs,
                region = selectedRegion,
            ) { newTrack ->
                // Add new track to clip (up to 8 tracks)
                val updatedTracks = (clip.trackingTracks.filterNot { it.id == newTrack.id } + newTrack).takeLast(8)
                val updatedClip = clip.copy(trackingTracks = updatedTracks)
                onUpdateClip(updatedClip)

                // Prompt user to link with mask
                AlertDialog.Builder(activity)
                    .setTitle("Rastreamento Concluído")
                    .setMessage("Gerados ${newTrack.points.size} pontos de rastreamento com sucesso.\n\nDeseja vincular a máscara do vídeo a este rastreio agora?")
                    .setPositiveButton("Vincular Máscara") { _, _ ->
                        val currentMask = updatedClip.mask ?: MaskState.default()
                        val linkedMask = currentMask.copy(trackingTrackId = newTrack.id)
                        onUpdateClip(updatedClip.copy(mask = linkedMask))
                        mainDialog.dismiss()
                        onOpenMask()
                    }
                    .setNegativeButton("Fechar") { _, _ ->
                        mainDialog.dismiss()
                    }
                    .show()
            }
        })

        // Existing tracks section
        if (clip.trackingTracks.isNotEmpty()) {
            body.addView(sectionTitle("Tracks salvos neste clipe", "Trilhas de movimento prontas para uso."))
            clip.trackingTracks.forEachIndexed { index, track ->
                val trackRow = row().apply { setPadding(0, dp(4), 0, dp(4)) }
                val isLinked = clip.mask?.trackingTrackId == track.id
                val statusText = if (isLinked) " (Vinculado)" else ""
                trackRow.addView(
                    label("Track ${index + 1}: ${track.points.size} pts$statusText", 12f, if (isLinked) EditorStyle.GREEN else EditorStyle.MUTED),
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                if (!isLinked) {
                    trackRow.addView(action("Vincular") {
                        val currentMask = clip.mask ?: MaskState.default()
                        val linkedMask = currentMask.copy(trackingTrackId = track.id)
                        onUpdateClip(clip.copy(mask = linkedMask))
                        Toast.makeText(activity, "Máscara vinculada ao Track ${index + 1}", Toast.LENGTH_SHORT).show()
                        mainDialog.dismiss()
                        onOpenMask()
                    }, LinearLayout.LayoutParams(dp(80), dp(40)).apply { marginEnd = dp(6) })
                }
                trackRow.addView(action("Excluir") {
                    val remaining = clip.trackingTracks.filterNot { it.id == track.id }
                    val unlinkedMask = if (clip.mask?.trackingTrackId == track.id) clip.mask.copy(trackingTrackId = null) else clip.mask
                    onUpdateClip(clip.copy(trackingTracks = remaining, mask = unlinkedMask))
                    mainDialog.dismiss()
                    show(activity, clip.copy(trackingTracks = remaining, mask = unlinkedMask), currentTimelineUs, overlay, onUpdateClip, onOpenMask)
                }, LinearLayout.LayoutParams(dp(70), dp(40)))
                body.addView(trackRow)
            }
        }

        mainDialog = sheet("Rastreamento", body)
        // Allow touches outside the sheet to manipulate the overlay on the preview
        mainDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        mainDialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        mainDialog.setOnDismissListener {
            overlay.visibility = View.GONE
            overlay.isEnabled = false
        }
    }

    private fun startTrackingProcess(
        activity: Activity,
        clip: VideoClip,
        startUs: Long,
        endUs: Long,
        stepUs: Long,
        region: TrackingRegion,
        onComplete: (TrackingTrack) -> Unit,
    ) {
        val safeStartUs = startUs.coerceAtLeast(0L)
        val safeEndUs = endUs.coerceAtLeast(safeStartUs + stepUs)

        val times = ArrayList<Long>()
        var cur = safeStartUs
        while (cur <= safeEndUs && times.size < TrackingTrack.MAX_POINTS) {
            times.add(cur)
            cur += stepUs
        }

        if (times.isEmpty()) {
            Toast.makeText(activity, "Intervalo de tempo inválido para rastreamento", Toast.LENGTH_SHORT).show()
            return
        }

        // Show progress dialog
        val progressDialog = Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setCancelable(false)
        }
        val progressLayout = activity.column().apply {
            setPadding(activity.dp(20), activity.dp(20), activity.dp(20), activity.dp(20))
            setBackgroundColor(EditorStyle.PANEL)
        }
        progressLayout.addView(activity.label("Rastreando movimento...", 16f).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        val progressText = activity.label("Analisando quadros... (0 / ${times.size})", 12f, EditorStyle.MUTED)
        progressLayout.addView(progressText)

        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = times.size
            progress = 0
            isIndeterminate = false
        }
        progressLayout.addView(progressBar, LinearLayout.LayoutParams(-1, activity.dp(24)).apply {
            topMargin = activity.dp(12)
            bottomMargin = activity.dp(16)
        })

        val isCancelled = AtomicBoolean(false)
        progressLayout.addView(activity.action("Cancelar") {
            isCancelled.set(true)
            progressDialog.dismiss()
        })

        progressDialog.setContentView(progressLayout)
        progressDialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.85).toInt(), -2)
        progressDialog.show()

        trackingExecutor.execute {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(activity, Uri.parse(clip.uri))
                val targetW = 320
                val aspect = if (clip.width > 0) clip.height.toFloat() / clip.width else 9f / 16f
                val targetH = (targetW * aspect).toInt().coerceIn(120, 480)

                val frames = object : Iterable<TrackingFrame> {
                    override fun iterator(): Iterator<TrackingFrame> = object : Iterator<TrackingFrame> {
                        private var index = 0

                        override fun hasNext(): Boolean = !isCancelled.get() && index < times.size

                        override fun next(): TrackingFrame {
                            val timeUs = times[index++]
                            var bitmap = retriever.getScaledFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                targetW,
                                targetH
                            )
                            if (bitmap == null) {
                                bitmap = retriever.getScaledFrameAtTime(
                                    timeUs,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    targetW,
                                    targetH
                                )
                            }
                            if (bitmap == null) {
                                bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            }

                            val currentIndex = index
                            val totalCount = times.size
                            activity.runOnUiThread {
                                if (progressDialog.isShowing) {
                                    progressBar.progress = currentIndex
                                    val percent = (currentIndex * 100) / totalCount
                                    progressText.text = "Analisando quadros... ($currentIndex / $totalCount - $percent%)"
                                }
                            }

                            return TrackingFrame(timeUs, bitmap)
                        }
                    }
                }

                val tracker = TemplateMotionTracker(
                    templateSize = 24,
                    searchFraction = 0.22f,
                    maxPoints = TrackingTrack.MAX_POINTS
                )

                val track = tracker.track(frames, region)

                activity.runOnUiThread {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    if (!isCancelled.get() && track.points.isNotEmpty()) {
                        onComplete(track)
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    if (!isCancelled.get()) {
                        Toast.makeText(activity, "Erro no rastreamento: ${e.message ?: "falha na extração"}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}
