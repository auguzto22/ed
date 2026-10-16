package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.TrackingTrack
import com.termex.replay15.editor.tracking.LayerGeometry
import com.termex.replay15.editor.tracking.StickerTrackingBinder
import com.termex.replay15.editor.tracking.TrackingRegion
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rastreamento do sticker (jeito do CapCut): a mira aparece na prévia sobre o sticker, a pessoa
 * arrasta até o objeto que quer seguir e toca em "Iniciar rastreamento". O vídeo de baixo é
 * analisado com a porcentagem na tela e, no fim, o sticker ganha keyframes que acompanham o objeto.
 */
object StickerTrackingTools {

    /** O que o painel precisa saber do clipe de vídeo embaixo do sticker. */
    class VideoUnder(
        val uri: String,
        /** Tempo de origem onde a análise começa e termina. */
        val sourceStartUs: Long,
        val sourceEndUs: Long,
        /** Tempo de origem -> tempo da timeline (null fora do clipe). */
        val timelineAt: (Long) -> Long?,
        /** Geometria do clipe no canvas num tempo de origem. */
        val geometryAt: (Long) -> LayerGeometry,
    )

    fun show(
        activity: Activity,
        sticker: StickerClip,
        /** Posição do sticker no canvas (0..1) onde a mira começa. */
        startX: Float,
        startY: Float,
        video: VideoUnder,
        overlay: TrackingOverlayView,
        apply: (StickerClip) -> Unit,
    ) = with(activity) {
        overlay.region = TrackingRegion(startX.coerceIn(0f, 1f), startY.coerceIn(0f, 1f), .2f, .14f)
        overlay.visibility = View.VISIBLE
        overlay.isEnabled = true

        val body = column()
        lateinit var dialog: Dialog
        // Uma bandeira nova por análise: cancelar e começar de novo não ressuscita a anterior.
        var cancelled = AtomicBoolean(false)
        var running = false

        val hint = label("Selecione um objeto para rastrear: arraste a mira na prévia até ele.", 13f, EditorStyle.MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
        }
        body.addView(hint)
        var followScale = true
        val scaleToggle = android.widget.CheckBox(this).apply {
            text = "Acompanhar o tamanho do objeto"; isChecked = true
            setTextColor(EditorStyle.TEXT)
            setOnCheckedChangeListener { _, checked -> followScale = checked }
        }
        body.addView(scaleToggle)

        val status = label("", 15f).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)); visibility = View.GONE }
        body.addView(status)

        lateinit var startButton: android.widget.Button
        startButton = action("Iniciar rastreamento", accent = true) {
            if (running) {
                cancelled.set(true); return@action
            }
            val times = TrackingTools.sampleTimes(video.sourceStartUs, video.sourceEndUs, 1_000_000L / 15)
            if (times.size < 2) {
                Toast.makeText(this, "O sticker precisa ficar sobre o vídeo por mais tempo para rastrear.", Toast.LENGTH_LONG).show()
                return@action
            }
            val region = video.geometryAt(times.first()).regionToSource(overlay.region)
            running = true
            val token = AtomicBoolean(false)
            cancelled = token
            overlay.isEnabled = false
            scaleToggle.isEnabled = false
            startButton.text = "Cancelar"
            status.visibility = View.VISIBLE
            status.text = "Rastreamento... 0%"
            TrackingTools.runTracker(activity, video.uri, times, region, token,
                onProgress = { done, total -> if (token === cancelled) status.text = "Rastreamento... ${(done * 100) / total.coerceAtLeast(1)}%" },
                onDone = { track: TrackingTrack? ->
                    if (token !== cancelled || !dialog.isShowing) return@runTracker
                    running = false
                    val bound = track?.let { StickerTrackingBinder.bind(sticker, it, video.timelineAt, video.geometryAt, followScale) }
                    if (bound == null) {
                        resetIdle(status, startButton, overlay, scaleToggle)
                        Toast.makeText(this, "Não foi possível acompanhar esse objeto. Tente uma área com mais contraste.", Toast.LENGTH_LONG).show()
                    } else {
                        apply(bound)
                        Toast.makeText(this, "Rastreamento concluído", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                },
                onError = { message ->
                    if (token !== cancelled) return@runTracker
                    running = false
                    resetIdle(status, startButton, overlay, scaleToggle)
                    Toast.makeText(this, "Erro no rastreamento: $message", Toast.LENGTH_LONG).show()
                },
                onCancelled = {
                    if (token !== cancelled) return@runTracker
                    running = false
                    resetIdle(status, startButton, overlay, scaleToggle)
                },
            )
        }
        body.addView(startButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })

        if (sticker.transformKeyframes.isNotEmpty()) {
            body.addView(action("Remover movimento (apaga os keyframes do sticker)") {
                val at = sticker.transformAt(sticker.startUs)
                apply(sticker.copy(transformKeyframes = emptyList(), x = at.x, y = at.y, scale = at.scaleX.coerceIn(.05f, 20f),
                    rotation = at.rotation, opacity = at.opacity.coerceIn(.05f, 1f)))
                dialog.dismiss()
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        }

        dialog = sheet("Rastreamento", body, dim = 0f)
        // A mira na prévia continua tocável com a folha aberta.
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        dialog.setOnDismissListener {
            cancelled.set(true)
            overlay.visibility = View.GONE
            overlay.isEnabled = false
        }
    }

    private fun resetIdle(status: android.widget.TextView, button: android.widget.Button, overlay: TrackingOverlayView, toggle: View) {
        status.visibility = View.GONE
        button.text = "Iniciar rastreamento"
        overlay.isEnabled = true
        toggle.isEnabled = true
    }
}
