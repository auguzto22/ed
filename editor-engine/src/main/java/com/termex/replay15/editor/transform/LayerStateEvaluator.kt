package com.termex.replay15.editor.transform

import com.termex.replay15.editor.domain.AnimationClip
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.TextClip

/** One half-open visibility rule and one transform evaluation path for visual overlays. */
object LayerStateEvaluator {
    fun isActive(projectTimeUs: Long, startUs: Long, endUs: Long): Boolean =
        endUs > startUs && projectTimeUs >= startUs && projectTimeUs < endUs

    fun isActive(text: TextClip, projectTimeUs: Long): Boolean {
        val timing = RealtimeProjectState.timing(text.id)
        return isActive(projectTimeUs, timing?.startUs ?: text.startUs, timing?.endUs ?: text.endUs)
    }

    fun isActive(sticker: StickerClip, projectTimeUs: Long): Boolean {
        val timing = RealtimeProjectState.timing(sticker.id)
        return isActive(projectTimeUs, timing?.startUs ?: sticker.startUs, timing?.endUs ?: sticker.endUs)
    }

    /**
     * [animations] são os balões do projeto: os ativos neste instante multiplicam a escala e somam
     * posição/rotação ao que os keyframes manuais já resolveram. Vazio = comportamento de sempre.
     */
    fun evaluate(
        text: TextClip, projectTimeUs: Long, realtime: Boolean = true,
        animations: List<AnimationClip> = emptyList(),
    ): EvaluatedLayerState =
        evaluate(text.id, text.startUs, text.endUs, text.baseTransform, text.transformKeyframes, projectTimeUs, realtime, animations)

    fun evaluate(
        sticker: StickerClip, projectTimeUs: Long, realtime: Boolean = true,
        animations: List<AnimationClip> = emptyList(),
    ): EvaluatedLayerState =
        evaluate(sticker.id, sticker.startUs, sticker.endUs, sticker.baseTransform, sticker.transformKeyframes, projectTimeUs, realtime, animations)

    private fun evaluate(
        id: String, storedStartUs: Long, storedEndUs: Long, base: TransformState,
        keyframes: List<TransformKeyframe>, projectTimeUs: Long, realtime: Boolean,
        animations: List<AnimationClip>,
    ): EvaluatedLayerState {
        val timing = if (realtime) RealtimeProjectState.timing(id) else null
        val startUs = timing?.startUs ?: storedStartUs
        val endUs = timing?.endUs ?: storedEndUs
        // Existing project files store absolute keyframe positions. Convert at the evaluation
        // boundary so interpolation always runs on time local to the original layer start.
        val localTimeUs = projectTimeUs - storedStartUs
        val transform = (if (realtime) RealtimeProjectState.transform(id) else null)
            ?: TransformEvaluator.evaluate(base, keyframes, localTimeUs, storedStartUs)
        val animated = if (animations.isEmpty()) transform
            else AnimationEvaluator.apply(transform, AnimationEvaluator.delta(animations, id, localTimeUs, storedEndUs - storedStartUs))
        return EvaluatedLayerState(isActive(projectTimeUs, startUs, endUs), animated)
    }
}

data class EvaluatedLayerState(val visible: Boolean, val transform: TransformState)
