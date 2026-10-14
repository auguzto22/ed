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
import com.termex.replay15.editor.domain.CanvasFill
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.transformAt
import com.termex.replay15.editor.media.SequentialFrameReader
import com.termex.replay15.editor.tracking.LayerGeometry
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
        /** Applies a change to the clip *as it is in the project now* (never a stale copy). */
        onUpdateClip: ((VideoClip) -> VideoClip) -> Unit,
        onOpenMask: () -> Unit,
        canvasWidth: Int = clip.width,
        canvasHeight: Int = clip.height,
        canvasFill: CanvasFill = CanvasFill.FIT,
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
        body.addView(action("Iniciar rastreamento", accent = true) {
            val fromStart = rangeSpinner.selectedItemPosition == 1
            val startUs = if (fromStart) clip.inUs else currentSourceUs
            val endUs = clip.outUs
            val fps = if (rateSpinner.selectedItemPosition == 1) 15 else 10
            val stepUs = 1_000_000L / fps

            // The selection is made on the preview canvas; the tracker works on decoded frames.
            val transform = clip.transformAt(startUs)
            val geometry = LayerGeometry(
                sourceWidth = clip.width, sourceHeight = clip.height, crop = clip.crop,
                fixedRotation = clip.rotation, flip = clip.flip, zoom = transform.zoom,
                x = transform.x, y = transform.y, rotation = transform.rotation,
                canvasWidth = canvasWidth, canvasHeight = canvasHeight, fill = canvasFill,
            )
            val selectedRegion = geometry.regionToSource(overlay.region)

            startTrackingProcess(
                activity = activity,
                clip = clip,
                startUs = startUs,
                endUs = endUs,
                stepUs = stepUs,
                region = selectedRegion,
            ) { frameTrack ->
                // Frames and mask share the same y-down source space.
                val newTrack = LayerGeometry.toMaskSpace(frameTrack)
                // Add new track to clip (up to 8 tracks)
                onUpdateClip { current ->
                    current.copy(trackingTracks = (current.trackingTracks.filterNot { it.id == newTrack.id } + newTrack).takeLast(8))
                }

                // Prompt user to link with mask
                AlertDialog.Builder(activity)
                    .setTitle("Rastreamento Concluído")
                    .setMessage("Gerados ${newTrack.points.size} pontos de rastreamento com sucesso.\n\nDeseja vincular a máscara do vídeo a este rastreio agora?")
                    .setPositiveButton("Vincular Máscara") { _, _ ->
                        onUpdateClip { current -> current.copy(mask = linkedMask(current.mask, newTrack)) }
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
                        onUpdateClip { current -> current.copy(mask = linkedMask(current.mask, track)) }
                        Toast.makeText(activity, "Máscara vinculada ao Track ${index + 1}", Toast.LENGTH_SHORT).show()
                        mainDialog.dismiss()
                        onOpenMask()
                    }, LinearLayout.LayoutParams(dp(80), dp(40)).apply { marginEnd = dp(6) })
                }
                trackRow.addView(action("Excluir") {
                    val remaining = clip.trackingTracks.filterNot { it.id == track.id }
                    val unlinkedMask = if (clip.mask?.trackingTrackId == track.id) clip.mask.copy(trackingTrackId = null) else clip.mask
                    onUpdateClip { current ->
                        current.copy(trackingTracks = current.trackingTracks.filterNot { it.id == track.id },
                            mask = current.mask?.let { if (it.trackingTrackId == track.id) it.copy(trackingTrackId = null) else it })
                    }
                    mainDialog.dismiss()
                    show(activity, clip.copy(trackingTracks = remaining, mask = unlinkedMask), currentTimelineUs, overlay, onUpdateClip, onOpenMask,
                        canvasWidth, canvasHeight, canvasFill)
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

    /**
     * Mask linked to [track]. Without a mask yet, it starts as an ellipse around the tracked target
     * (the track's first frame), since the track now moves the mask relative to where it starts.
     */
    private fun linkedMask(existing: MaskState?, track: TrackingTrack): MaskState {
        val anchor = track.points.first()
        val base = existing ?: MaskState.default(com.termex.replay15.editor.domain.MaskType.ELLIPSE).copy(
            centerX = anchor.centerX,
            centerY = anchor.centerY,
            width = (anchor.width * 1.3f).coerceIn(.05f, 2f),
            height = (anchor.height * 1.3f).coerceIn(.05f, 2f),
        )
        return base.copy(trackingTrackId = track.id)
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
            val targetW = 320
            val targetH = 320
            var session: TemplateMotionTracker.Session? = null
            val tracker = TemplateMotionTracker(templateSize = 24, searchFraction = 0.22f, maxPoints = TrackingTrack.MAX_POINTS)
            var processed = 0
            fun push(timeUs: Long, bitmap: Bitmap) {
                if (isCancelled.get()) { bitmap.recycle(); throw InterruptedException("Rastreamento cancelado") }
                try {
                    val frame = TrackingFrame(timeUs, bitmap)
                    val current = session
                    if (current == null) session = tracker.Session(frame, region) else current.next(frame)
                } finally { bitmap.recycle() }
                processed++
                val currentIndex = processed
                val totalCount = times.size
                activity.runOnUiThread {
                    if (progressDialog.isShowing) {
                        progressBar.progress = currentIndex
                        val percent = (currentIndex * 100) / totalCount
                        progressText.text = "Analisando quadros... ($currentIndex / $totalCount - $percent%)"
                    }
                }
            }
            var retriever: MediaMetadataRetriever? = null
            try {
                // One sequential decode for the whole range (fit inside 320x320, display-oriented).
                val handled = SequentialFrameReader.read(activity, clip.uri, times, targetW, targetH,
                    { if (isCancelled.get()) throw InterruptedException("Rastreamento cancelado") }, ::push)
                if (handled < times.size && !isCancelled.get()) {
                    val fallback = MediaMetadataRetriever().also { retriever = it }
                    fallback.setDataSource(activity, Uri.parse(clip.uri))
                    for (timeUs in times.subList(handled, times.size)) {
                        if (isCancelled.get()) break
                        val bitmap = fallback.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, targetW, targetH)
                            ?: fallback.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetW, targetH)
                            ?: continue
                        push(timeUs, bitmap)
                    }
                }
                val track = session?.result()
                activity.runOnUiThread {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    if (!isCancelled.get() && track != null && track.points.isNotEmpty()) {
                        onComplete(track)
                    } else if (!isCancelled.get()) {
                        Toast.makeText(activity, "Não foi possível ler os quadros deste clipe", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (cancelled: InterruptedException) {
                activity.runOnUiThread { if (progressDialog.isShowing) progressDialog.dismiss() }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    if (!isCancelled.get()) {
                        Toast.makeText(activity, "Erro no rastreamento: ${e.message ?: "falha na extração"}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                runCatching { retriever?.release() }
            }
        }
    }
}
