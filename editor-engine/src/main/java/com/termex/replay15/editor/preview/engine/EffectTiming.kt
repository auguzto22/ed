package com.termex.replay15.editor.preview.engine

/**
 * uTime and uProgress for an effect shader, by the same rule PackageEffect uses on export, so an
 * effect animates the same way in the preview as in the exported video.
 *
 * - With its own range ([startUs]..[endUs]): time counts from the range start and progress is the
 *   fraction of the range.
 * - Without one: time counts from the start of the layer it is on ([layerTimeUs]) and progress is
 *   the fraction of that layer ([layerDurationUs]).
 *
 * [localUs] is in the same clock as the range: source time for a clip effect, project time for an
 * adjustment layer effect.
 */
object EffectTiming {
    data class Value(val timeSeconds: Float, val progress: Float)

    fun evaluate(localUs: Long, startUs: Long?, endUs: Long?, layerTimeUs: Long, layerDurationUs: Long): Value {
        val layerTime = layerTimeUs.coerceIn(0L, layerDurationUs.coerceAtLeast(1L))
        val effectTimeUs = if (startUs != null) (localUs - startUs).coerceAtLeast(0L) else layerTime
        val progress = if (startUs != null && endUs != null) {
            (effectTimeUs.toFloat() / (endUs - startUs).coerceAtLeast(1L)).coerceIn(0f, 1f)
        } else layerTime.toFloat() / layerDurationUs.coerceAtLeast(1L)
        return Value(effectTimeUs / 1_000_000f, progress)
    }
}
