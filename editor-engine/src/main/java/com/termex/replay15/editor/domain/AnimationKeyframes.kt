package com.termex.replay15.editor.domain

import com.termex.replay15.editor.transform.AnimationEvaluator
import com.termex.replay15.editor.transform.TransformEvaluator
import com.termex.replay15.editor.transform.TransformState

/**
 * "Converter em keyframes": troca um balão por keyframes manuais equivalentes no alvo, para quem
 * quer continuar o movimento à mão. O resultado reproduz a animação amostrada (12 passos lineares),
 * com uma chave neutra logo antes e logo depois da janela para que o alvo não fique "preso" no
 * último valor depois do fim, como o balão também não deixava.
 */
object AnimationKeyframes {
    const val SAMPLES = 12
    private const val MAX_KEYFRAMES = 200

    /** O projeto sem o balão e com os keyframes no alvo, ou null se não couber / o alvo sumiu. */
    fun convert(project: Project, animationId: String): Project? {
        val animation = project.animationClips.firstOrNull { it.id == animationId } ?: return null
        val target = AnimationEditing.target(project, animation.targetId) ?: return null
        val without = project.copy(animationClips = project.animationClips.filterNot { it.id == animationId })
        project.allVideos.firstOrNull { it.id == animation.targetId }?.let { video ->
            val keys = videoKeys(video, animation, target.durationUs) ?: return null
            return without.mapVideo(video.id) { it.copy(keyframes = keys) }
        }
        project.texts.firstOrNull { it.id == animation.targetId }?.let { text ->
            val keys = layerKeys(text.baseTransform, text.transformKeyframes, target, animation) ?: return null
            return without.copy(texts = without.texts.map { if (it.id == text.id) it.copy(transformKeyframes = keys) else it })
        }
        project.stickers.firstOrNull { it.id == animation.targetId }?.let { sticker ->
            val keys = layerKeys(sticker.baseTransform, sticker.transformKeyframes, target, animation) ?: return null
            return without.copy(stickers = without.stickers.map { if (it.id == sticker.id) it.copy(transformKeyframes = keys) else it })
        }
        return null
    }

    private fun sampleTimes(animation: AnimationClip): List<Long> =
        (0..SAMPLES).map { i ->
            if (i == SAMPLES) animation.endUs - 1L else animation.startUs + animation.durationUs * i / SAMPLES
        }.distinct()

    private fun videoKeys(video: VideoClip, animation: AnimationClip, targetDurationUs: Long): List<TransformKeyframe>? {
        val timeMap = video.timeMap
        fun sourceOf(localUs: Long) = timeMap.sourceAt(localUs.coerceIn(0L, video.durationUs))
        val startSource = sourceOf(animation.startUs)
        val endSource = sourceOf(animation.endUs)
        val low = minOf(startSource, endSource)
        val high = maxOf(startSource, endSource)
        val kept = video.keyframes.filter { it.sourceUs < low - 1L || it.sourceUs > high + 1L }
        val generated = ArrayList<TransformKeyframe>()
        if (animation.startUs >= 1L) {
            val before = sourceOf(animation.startUs - 1L)
            generated += video.transformAt(before).copy(sourceUs = before, easing = Easing.LINEAR)
        }
        sampleTimes(animation).forEach { t ->
            val source = sourceOf(t)
            val base = video.transformAt(source).copy(sourceUs = source)
            generated += AnimationEvaluator.apply(base, AnimationEvaluator.delta(animation, t, targetDurationUs)).copy(easing = Easing.LINEAR)
        }
        if (animation.endUs < video.durationUs) {
            generated += video.transformAt(endSource).copy(sourceUs = endSource, easing = Easing.SMOOTH)
        }
        val all = (kept + generated).distinctBy { it.sourceUs }.sortedBy { it.sourceUs }
        return all.takeIf { it.size <= MAX_KEYFRAMES }
    }

    /** Texto e sticker guardam keyframes em tempo ABSOLUTO do projeto. */
    private fun layerKeys(
        base: TransformState, existing: List<com.termex.replay15.editor.transform.TransformKeyframe>,
        target: AnimationEditing.Target, animation: AnimationClip,
    ): List<com.termex.replay15.editor.transform.TransformKeyframe>? {
        val targetStartUs = target.startUs
        fun manual(localUs: Long) = TransformEvaluator.evaluate(base, existing, localUs, targetStartUs)
        fun key(localUs: Long, state: TransformState, easing: Easing) =
            com.termex.replay15.editor.transform.TransformKeyframe(targetStartUs + localUs, state, easing)
        val low = targetStartUs + animation.startUs - 1L
        val high = targetStartUs + animation.endUs
        val kept = existing.filter { it.timeUs < low || it.timeUs > high }
        val generated = ArrayList<com.termex.replay15.editor.transform.TransformKeyframe>()
        if (animation.startUs >= 1L) generated += key(animation.startUs - 1L, manual(animation.startUs - 1L), Easing.LINEAR)
        sampleTimes(animation).forEach { t ->
            generated += key(t, AnimationEvaluator.apply(manual(t), AnimationEvaluator.delta(animation, t, target.durationUs)), Easing.LINEAR)
        }
        generated += key(animation.endUs, manual(animation.endUs), Easing.SMOOTH)
        val all = (kept + generated).distinctBy { it.timeUs }.sortedBy { it.timeUs }
        return all.takeIf { it.size <= MAX_KEYFRAMES }
    }
}
