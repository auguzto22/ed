package com.termex.replay15.editor.reference

import kotlin.math.abs
import kotlin.math.hypot

object ReferenceVisualDetectors {
    /** Adaptive scene detector: robust local baseline plus continuity/flash checks. */
    fun sceneEvents(frames: List<FrameFeatures>, deltas: List<FrameDelta>): List<ReferenceEvent> {
        if (deltas.size < 2) return emptyList()
        val result = mutableListOf<ReferenceEvent>()
        // Multi-frame transitions are detected before single discontinuities so they are not
        // misreported as a row of cuts.
        for (index in 1 until frames.size - 3) {
            val window = frames.subList(index - 1, index + 3)
            val luminanceSteps = window.zipWithNext().map { (a, b) -> b.meanLuma - a.meanLuma }
            val monotonic = luminanceSteps.all { it >= .025f } || luminanceSteps.all { it <= -.025f }
            val range = abs(window.last().meanLuma - window.first().meanLuma)
            if (monotonic && range >= .22f && (window.first().meanLuma < .14f || window.last().meanLuma < .14f || window.last().meanLuma > .86f)) {
                result += ReferenceEvent(ReferenceEventType.FADE, window.first().timeUs, window[2].timeUs, window.last().timeUs,
                    range.coerceIn(0f, 1f), .72f, structuralDescription = "monotonic luminance transition")
                continue
            }
            val changes = deltas.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(deltas.size))
            if (changes.size == 3 && changes.all { it.difference in .12f.. .48f && it.continuity < .78f } && changes.sumOf { it.histogramDifference.toDouble() } > .5) {
                result += ReferenceEvent(ReferenceEventType.CROSSFADE, window.first().timeUs, window[2].timeUs, window.last().timeUs,
                    changes.map { it.difference }.average().toFloat(), .58f, structuralDescription = "distributed multi-frame scene blend")
            }
        }
        deltas.forEachIndexed { index, delta ->
            val local = deltas.subList((index - 8).coerceAtLeast(0), (index + 9).coerceAtMost(deltas.size))
                .filterIndexed { localIndex, _ -> localIndex != (index - (index - 8).coerceAtLeast(0)) }.map { it.difference }
            val median = local.median(); val mad = local.map { abs(it - median) }.median().coerceAtLeast(.015f)
            val adaptive = (median + 3.2f * mad).coerceIn(.22f, .72f)
            if (delta.difference < adaptive) return@forEachIndexed
            val before = frames.getOrNull(index); val current = frames.getOrNull(index + 1); val after = frames.getOrNull(index + 2)
            if (before == null || current == null) return@forEachIndexed
            val flashShape = after != null && current.meanLuma > before.meanLuma + .24f &&
                abs(after.meanLuma - before.meanLuma) < .14f && similarity(before, after) > .62f
            if (flashShape) return@forEachIndexed
            val confidence = ((delta.difference - adaptive) / (1f - adaptive) * .55f + .43f - delta.continuity * .18f).coerceIn(.35f, .98f)
            if (delta.continuity > .82f && delta.histogramDifference < .22f) return@forEachIndexed
            val type = if (delta.difference > .5f && delta.continuity < .62f) ReferenceEventType.HARD_CUT else ReferenceEventType.MOTION_TRANSITION
            result += ReferenceEvent(type, before.timeUs, delta.timeUs, current.timeUs.coerceAtLeast(before.timeUs + 1), delta.difference, confidence)
        }
        val transitionTypes = setOf(ReferenceEventType.HARD_CUT, ReferenceEventType.FADE, ReferenceEventType.CROSSFADE, ReferenceEventType.MOTION_TRANSITION)
        val chosen = mutableListOf<ReferenceEvent>()
        result.sortedByDescending { it.confidence }.forEach { event ->
            if (chosen.none { it.type in transitionTypes && event.type in transitionTypes && abs(it.peakUs - event.peakUs) < 250_000L }) chosen += event
        }
        return chosen.sortedBy { it.startUs }
    }

    fun flashes(frames: List<FrameFeatures>): List<ReferenceEvent> {
        if (frames.size < 3) return emptyList()
        val values = frames.map { it.meanLuma }; val baseline = values.median(); val deviations = values.map { abs(it - baseline) }; val mad = deviations.median().coerceAtLeast(.025f)
        val result = mutableListOf<ReferenceEvent>()
        for (i in 1 until frames.lastIndex) {
            val a = frames[i - 1]; val peak = frames[i]; val b = frames[i + 1]
            val rise = peak.meanLuma - (a.meanLuma + b.meanLuma) / 2f
            val returnSimilarity = similarity(a, b)
            if (rise > maxOf(.18f, mad * 3.5f) && abs(a.meanLuma - b.meanLuma) < .16f && returnSimilarity > .48f) {
                val contentPenalty = if (peak.saturation > a.saturation + .16f || returnSimilarity < .64f) .22f else 0f
                result += ReferenceEvent(ReferenceEventType.FLASH, a.timeUs, peak.timeUs, b.timeUs,
                    (rise / .65f).coerceIn(0f, 1f), (.55f + returnSimilarity * .38f - contentPenalty).coerceIn(.25f, .96f),
                    mapOf("intensity" to rise.coerceIn(0f, 1f), "attack" to ((peak.timeUs - a.timeUs) / 1_000_000f), "decay" to ((b.timeUs - peak.timeUs) / 1_000_000f)))
            }
        }
        return suppressNearby(result, 180_000L)
    }

    fun motion(deltas: List<FrameDelta>): List<ReferenceEvent> {
        if (deltas.size < 3) return emptyList()
        val result = mutableListOf<ReferenceEvent>()
        var index = 1
        while (index < deltas.lastIndex) {
            val d = deltas[index]
            val scaleDeviation = abs(d.scale - 1f)
            if (scaleDeviation >= .025f && d.continuity >= .52f) {
                var end = index
                var peak = d
                while (end + 1 < deltas.size && abs(deltas[end + 1].scale - 1f) >= .018f && deltas[end + 1].timeUs - d.timeUs <= 700_000L) {
                    end++; if (abs(deltas[end].scale - 1f) > abs(peak.scale - 1f)) peak = deltas[end]
                }
                val strength = (abs(peak.scale - 1f) / .16f).coerceIn(0f, 1f)
                result += ReferenceEvent(ReferenceEventType.ZOOM, deltas[index - 1].timeUs, peak.timeUs, deltas[end].timeUs.coerceAtLeast(d.timeUs + 1),
                    strength, (.48f + peak.continuity * .42f).coerceAtMost(.94f), mapOf("scale" to peak.scale, "curve" to .5f))
                index = end + 1; continue
            }
            val window = deltas.subList((index - 1).coerceAtLeast(0), (index + 4).coerceAtMost(deltas.size))
            val movement = window.map { hypot(it.translationX.toDouble(), it.translationY.toDouble()).toFloat() }
            val alternating = window.zipWithNext().count { (a, b) -> a.translationX * b.translationX < 0 || a.translationY * b.translationY < 0 }
            if (movement.maxOrNull()!! >= .018f && alternating >= 2 && window.last().timeUs - window.first().timeUs <= 650_000L) {
                val strength = (movement.sorted()[movement.size / 2] / .06f).coerceIn(0f, 1f)
                result += ReferenceEvent(ReferenceEventType.SHAKE, window.first().timeUs, d.timeUs, window.last().timeUs,
                    strength, (.5f + alternating * .1f).coerceAtMost(.9f), mapOf("translation" to movement.max(), "frequency" to alternating.toFloat(), "decay" to .55f))
                index += window.size; continue
            }
            index++
        }
        return suppressNearby(result, 120_000L)
    }

    fun blurAndRgb(frames: List<FrameFeatures>, deltas: List<FrameDelta>): List<ReferenceEvent> {
        if (frames.size < 3) return emptyList()
        val result = mutableListOf<ReferenceEvent>()
        val medianSharp = frames.map { it.sharpness }.median().coerceAtLeast(.01f)
        val medianRgb = frames.map { it.rgbSeparation }.median().coerceAtLeast(.01f)
        for (i in 1 until frames.lastIndex) {
            val f = frames[i]; val before = frames[i - 1]; val after = frames[i + 1]
            if (f.sharpness < medianSharp * .52f && before.sharpness > f.sharpness * 1.35f && after.sharpness > f.sharpness * 1.25f) {
                val continuity = deltas.getOrNull(i - 1)?.continuity ?: 0f
                val confidence = (.5f + continuity * .25f - if (deltas.getOrNull(i - 1)?.difference ?: 0f > .55f) .2f else 0f).coerceIn(.25f, .85f)
                result += ReferenceEvent(ReferenceEventType.BLUR, before.timeUs, f.timeUs, after.timeUs,
                    (1f - f.sharpness / medianSharp).coerceIn(0f, 1f), confidence, mapOf("strength" to (1f - f.sharpness / medianSharp).coerceIn(0f, 1f)), "temporary loss of spatial detail")
            }
            if (f.rgbSeparation > medianRgb * 2.15f && f.rgbSeparation > .09f && before.rgbSeparation < f.rgbSeparation * .72f && after.rgbSeparation < f.rgbSeparation * .8f) {
                result += ReferenceEvent(ReferenceEventType.RGB_SPLIT, before.timeUs, f.timeUs, after.timeUs,
                    (f.rgbSeparation / .45f).coerceIn(0f, 1f), .62f, mapOf("amount" to (f.rgbSeparation * 28f).coerceIn(1f, 30f)), "temporary relative RGB edge displacement")
            }
        }
        return suppressNearby(result, 160_000L)
    }

    private fun similarity(a: FrameFeatures, b: FrameFeatures): Float = (1f - a.histogram.indices.sumOf { abs(a.histogram[it] - b.histogram[it]).toDouble() }.toFloat() / 2f).coerceIn(0f, 1f)
    private fun suppressNearby(events: List<ReferenceEvent>, spacingUs: Long): List<ReferenceEvent> {
        val chosen = mutableListOf<ReferenceEvent>()
        events.sortedByDescending { it.confidence * (.5f + it.strength * .5f) }.forEach { candidate ->
            if (chosen.none { it.type == candidate.type && abs(it.peakUs - candidate.peakUs) < spacingUs }) chosen += candidate
        }
        return chosen.sortedBy { it.startUs }
    }
}

internal fun List<Float>.median(): Float {
    if (isEmpty()) return 0f
    val sorted = sorted(); val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
}
internal fun List<Long>.medianLong(): Long {
    if (isEmpty()) return 0L
    val sorted = sorted(); val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1] / 2 + sorted[middle] / 2
}
