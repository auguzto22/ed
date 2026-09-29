package com.termex.replay15.editor.preview.engine.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Maps project timeline instants to source media timestamps, speeds, and gain envelopes.
 */
object AudioTimelineMapper {

    fun calculateFadeVolume(
        positionUs: Long,
        durationUs: Long,
        fadeInUs: Long,
        fadeOutUs: Long,
    ): Float {
        if (durationUs <= 0) return 0f
        var gain = 1f
        if (fadeInUs > 0 && positionUs < fadeInUs) {
            gain *= (positionUs.toFloat() / fadeInUs.toFloat()).coerceIn(0f, 1f)
        }
        val fadeOutStart = durationUs - fadeOutUs
        if (fadeOutUs > 0 && positionUs > fadeOutStart) {
            gain *= ((durationUs - positionUs).toFloat() / fadeOutUs.toFloat()).coerceIn(0f, 1f)
        }
        return gain.coerceIn(0f, 1f)
    }

    fun calculateGain(
        clip: com.termex.replay15.editor.domain.AudioClip,
        clipLocalTimeUs: Long,
    ): Float {
        val vol = clip.volumeAt(clipLocalTimeUs)
        return calculateGain(vol, clipLocalTimeUs, clip.durationUs, clip.fadeInUs, clip.fadeOutUs)
    }

    fun calculateGain(
        baseVolume: Float,
        clipLocalTimeUs: Long,
        clipDurationUs: Long,
        fadeInUs: Long,
        fadeOutUs: Long
    ): Float {
        if (clipDurationUs <= 0) return 0f
        val maxFade = clipDurationUs / 2
        val actualFadeIn = fadeInUs.coerceIn(0L, maxFade)
        val actualFadeOut = fadeOutUs.coerceIn(0L, maxFade)

        val enter = if (actualFadeIn == 0L) 1f else (clipLocalTimeUs.toFloat() / actualFadeIn).coerceIn(0f, 1f)
        val leave = if (actualFadeOut == 0L) 1f else ((clipDurationUs - clipLocalTimeUs).toFloat() / actualFadeOut).coerceIn(0f, 1f)
        val curve = sin(minOf(enter, leave) * PI / 2.0).toFloat()

        return (baseVolume * curve).coerceIn(0f, 4f)
    }

    fun projectToSourceTimeUs(
        projectTimeUs: Long,
        startUs: Long,
        inUs: Long,
        speed: Float
    ): Long {
        val offset = (projectTimeUs - startUs).coerceAtLeast(0L)
        return inUs + (offset * speed).toLong()
    }
}
