package com.termex.replay15.editor.ui

import android.app.Activity
import android.app.Dialog
import android.widget.CheckBox
import android.widget.LinearLayout
import com.termex.replay15.editor.domain.BackgroundMode
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

            body.addView(label("Motor de IA", 13f, EditorStyle.MUTED))
            val providers = row()
            BackgroundRemovalProvider.entries.forEach { provider ->
                providers.addView(action(providerLabel(provider), effect.provider == provider) {
                    effect = effect.copy(provider = provider); preview()
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            body.addView(providers)

            body.addView(label("Qualidade da máscara", 13f, EditorStyle.MUTED))
            val qualities = row()
            SegmentationQuality.entries.forEach { quality ->
                qualities.addView(action(qualityLabel(quality), effect.quality == quality) {
                    effect = effect.copy(quality = quality); preview()
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            body.addView(qualities)

            slider(body, "Precisão / limiar (%)", (effect.threshold * 100).toInt(), 100) {
                effect = effect.copy(threshold = it / 100f); preview()
            }
            slider(body, "Suavizar bordas (%)", (effect.feather * 1000).toInt(), 500) {
                effect = effect.copy(feather = it / 1000f); preview()
            }
            slider(body, "Suavização temporal/espacial (%)", (effect.edgeSmoothing * 100).toInt(), 100) {
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
            body.addView(sectionTitle("Remover Fundo", "Aplica a máscara de IA à foto sobreposta."))
            val enabled = CheckBox(this).apply { text = "Ativar remoção de fundo"; isChecked = effect.enabled }
            body.addView(enabled)
            fun updated() = project().copy(stickers = project().stickers.map {
                if (it.id == stickerId) it.copy(backgroundRemoval = effect) else it
            })
            fun preview() = previewApply(updated())
            enabled.setOnCheckedChangeListener { _, checked -> effect = effect.copy(enabled = checked); preview() }
            body.addView(label("Motor de IA", 13f, EditorStyle.MUTED))
            val providers = row()
            BackgroundRemovalProvider.entries.forEach { provider ->
                providers.addView(action(providerLabel(provider), effect.provider == provider) {
                    effect = effect.copy(provider = provider); preview()
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            body.addView(providers)

            body.addView(label("Qualidade da máscara", 13f, EditorStyle.MUTED))
            val qualities = row()
            SegmentationQuality.entries.forEach { quality ->
                qualities.addView(action(qualityLabel(quality), effect.quality == quality) {
                    effect = effect.copy(quality = quality); preview()
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            body.addView(qualities)
            slider(body, "Precisão / limiar (%)", (effect.threshold * 100).toInt(), 100) {
                effect = effect.copy(threshold = it / 100f); preview()
            }
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

    override fun close() { closed = true }
}
