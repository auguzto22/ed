package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.widget.CheckBox
import android.widget.LinearLayout
import com.termex.replay15.editor.domain.BackgroundMode
import com.termex.replay15.editor.domain.BackgroundPrecision
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.BackgroundRemovalProvider
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.SegmentationQuality
import com.termex.replay15.editor.domain.allVideos
import com.termex.replay15.editor.domain.mapVideo

/** UI for the persisted segmentation effect. Preview changes are intentionally live. */
class BackgroundRemovalTools(
    private val activity: Activity,
    private val project: () -> Project,
    private val apply: (Project) -> Unit,
    private val previewApply: (Project) -> Unit,
) : AutoCloseable {
    private var closed = false

    fun open(clipId: String) {
        if (closed) return
        val clip = project().allVideos.firstOrNull { it.id == clipId } ?: return
        with(activity) {
            var effect = clip.backgroundRemoval
            lateinit var dialog: Dialog
            val body = column()
            body.addView(sectionTitle("Remover Fundo", "Segmentação por IA com máscara real e transparência no preview."))
            val enabled = CheckBox(this).apply {
                text = "Ativar remoção de fundo"
                isChecked = effect.enabled
            }
            body.addView(enabled)
            fun updated() = project().mapVideo(clipId) { it.copy(backgroundRemoval = effect) }
            fun preview() = previewApply(updated())
            enabled.setOnCheckedChangeListener { _, checked -> effect = effect.copy(enabled = checked); preview() }

            // There is no "Motor de IA" choice any more: every provider runs the same on-device
            // engine the export uses (SegmentationEngineFactory), so the old buttons changed
            // nothing in the exported video.
            body.addView(label("Qualidade da máscara", 13f, EditorStyle.MUTED))
            val qualities = row()
            fun refreshQualities() {
                choices(qualities, SegmentationQuality.entries, { qualityLabel(it) }, { effect.quality == it }) {
                    effect = effect.copy(quality = it); preview(); refreshQualities()
                }
            }
            refreshQualities()
            body.addView(qualities)

            // Linear 0..100 % over the usable threshold range: 100 % is the tightest cut that still
            // keeps the subject (see BackgroundPrecision), not "erase everything".
            val precision = slider(body, "Precisão (%)", BackgroundPrecision.percentFor(effect.threshold), 100) {
                effect = effect.copy(threshold = BackgroundPrecision.thresholdFor(it)); preview()
            }
            val feather = slider(body, "Suavizar bordas (%)", (effect.feather * 1000).toInt(), 500) {
                effect = effect.copy(feather = it / 1000f); preview()
            }
            val smoothing = slider(body, "Suavização temporal/espacial (%)", (effect.edgeSmoothing * 100).toInt(), 100) {
                effect = effect.copy(edgeSmoothing = it / 100f); preview()
            }

            body.addView(label("Fundo", 13f, EditorStyle.MUTED))
            val modes = row()
            modes.addView(action("Transparente", effect.mode == BackgroundMode.REMOVE) {
                effect = effect.copy(mode = BackgroundMode.REMOVE); preview()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            // These modes are represented in the persisted contract, but are not enabled until
            // their extra source texture/compositor is implemented; no misleading fake preview.
            modes.addView(label("Cor / imagem / vídeo / desfoque\n(disponível na próxima etapa)", 11f, EditorStyle.MUTED),
                LinearLayout.LayoutParams(0, dp(48), 1f))
            body.addView(modes)

            body.addView(action("Restaurar", false) {
                effect = BackgroundRemovalEffect()
                // The controls used to keep their old values: the box stayed ticked over a
                // disabled effect and the next slider touch mixed old and default settings.
                enabled.isChecked = effect.enabled
                precision.progress = BackgroundPrecision.percentFor(effect.threshold)
                feather.progress = (effect.feather * 1000).toInt()
                smoothing.progress = (effect.edgeSmoothing * 100).toInt()
                refreshQualities()
                previewApply(updated())
            })
            body.addView(action("Aplicar", true) { apply(updated()); dialog.dismiss() })
            dialog = sheet("Remover Fundo", body)
            dialog.setOnDismissListener { previewApply(project()) }
        }
    }

    fun openSticker(stickerId: String) {
        if (closed) return
        val sticker = project().stickers.firstOrNull { it.id == stickerId } ?: return
        with(activity) {
            var effect = sticker.backgroundRemoval
            lateinit var dialog: Dialog
            val body = column()
            body.addView(sectionTitle("Remover Fundo", "O Gemini recorta o assunto da foto e deixa todo o resto transparente."))
            val enabled = CheckBox(this).apply { text = "Ativar remoção de fundo"; isChecked = effect.enabled }
            body.addView(enabled)
            fun updated() = project().copy(stickers = project().stickers.map {
                if (it.id == stickerId) it.copy(backgroundRemoval = effect) else it
            })
            fun preview() = previewApply(updated())
            enabled.setOnCheckedChangeListener { _, checked ->
                effect = effect.copy(enabled = checked, provider = BackgroundRemovalProvider.GEMINI); preview()
            }
            body.addView(label("Precisão do recorte", 13f, EditorStyle.MUTED))
            val qualities = row()
            SegmentationQuality.entries.forEach { quality ->
                qualities.addView(action(qualityLabel(quality), effect.quality == quality) {
                    effect = effect.copy(quality = quality); preview()
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            body.addView(qualities)
            slider(body, "Suavizar bordas (%)", (effect.feather * 1000).toInt(), 500) {
                effect = effect.copy(feather = it / 1000f); preview()
            }
            body.addView(action("Restaurar") { effect = BackgroundRemovalEffect(); preview() })
            body.addView(action("Aplicar", true) { apply(updated()); dialog.dismiss() })
            dialog = sheet("Remover Fundo", body)
            dialog.setOnDismissListener { previewApply(project()) }
        }
    }

    private fun providerLabel(provider: BackgroundRemovalProvider): String = when (provider) {
        BackgroundRemovalProvider.AUTO -> "Auto (Gemini/Local)"
        BackgroundRemovalProvider.GEMINI -> "Gemini API"
        BackgroundRemovalProvider.MLKIT -> "ML Kit (Local)"
    }

    private fun qualityLabel(quality: SegmentationQuality): String = when (quality) {
        SegmentationQuality.FAST -> "Rápida"
        SegmentationQuality.BALANCED -> "Normal"
        SegmentationQuality.HIGH -> "Alta"
    }

    /**
     * Rebuilds a row of mutually exclusive buttons. The buttons used to be created once, so the
     * highlighted option never moved when the user picked another one.
     */
    private fun <T> Activity.choices(
        target: LinearLayout,
        options: List<T>,
        title: (T) -> String,
        selected: (T) -> Boolean,
        pick: (T) -> Unit,
    ) {
        target.removeAllViews()
        options.forEach { option ->
            target.addView(action(title(option), selected(option)) { if (!selected(option)) pick(option) },
                LinearLayout.LayoutParams(0, dp(48), 1f))
        }
    }

    override fun close() { closed = true }
}
