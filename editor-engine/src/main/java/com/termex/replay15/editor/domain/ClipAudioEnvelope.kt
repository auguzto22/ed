package com.termex.replay15.editor.domain

import com.termex.replay15.editor.preview.engine.audio.AudioTimelineMapper

/**
 * Gain of a main-track clip's own sound at a project time. Preview and export both use it, so a
 * transition sounds the same while editing and in the exported file.
 *
 * A transition owns its side of the clip: there the clip's own fade is replaced by an equal-power
 * cross-fade over the same window the picture uses, so loudness never dips at the cut.
 */
class ClipAudioEnvelope(
    private val clip: VideoClip,
    private val clipStartUs: Long,
    private val entering: TransitionWindow?,
    private val leaving: TransitionWindow?,
) {
    private val durationUs = clip.durationUs
    private val fadeInUs = if (entering != null) 0L else clip.audioFadeInUs
    private val fadeOutUs = if (leaving != null) 0L else clip.audioFadeOutUs

    fun gainAt(projectTimeUs: Long): Float {
        val local = (projectTimeUs - clipStartUs).coerceIn(0L, durationUs)
        val base = AudioTimelineMapper.calculateGain(clip.volume, local, durationUs, fadeInUs, fadeOutUs)
        val fadeIn = entering?.takeIf { it.contains(projectTimeUs) }
            ?.let { com.termex.replay15.editor.transitions.NativeTransitionBridge.nativeAudioGain(it.linearProgress(projectTimeUs), true) } ?: 1f
        val fadeOut = leaving?.takeIf { it.contains(projectTimeUs) }
            ?.let { com.termex.replay15.editor.transitions.NativeTransitionBridge.nativeAudioGain(it.linearProgress(projectTimeUs), false) } ?: 1f
        return (base * fadeIn * fadeOut).coerceIn(0f, 4f)
    }

    /**
     * End (exclusive, project time) of the stretch starting at [projectTimeUs] over which the gain
     * stays exactly 1, or null when the gain there is not 1. Inside a fade a single instant can
     * round to 1; only that instant is reported.
     */
    fun unityUntilUs(projectTimeUs: Long): Long? {
        if (gainAt(projectTimeUs) != 1f) return null
        val maxFade = durationUs / 2
        val flatFrom = maxOf(clipStartUs + fadeInUs.coerceIn(0L, maxFade), entering?.endUs ?: Long.MIN_VALUE)
        val flatUntil = minOf(clipStartUs + durationUs - fadeOutUs.coerceIn(0L, maxFade), leaving?.startUs ?: Long.MAX_VALUE)
        return if (projectTimeUs >= flatFrom && projectTimeUs < flatUntil) flatUntil else projectTimeUs + 1L
    }
}
