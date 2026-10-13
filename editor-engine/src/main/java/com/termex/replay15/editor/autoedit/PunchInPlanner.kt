package com.termex.replay15.editor.autoedit

/**
 * Talking-head punch-ins: the framing jumps closer at the start of a phrase, holds, and jumps
 * back at the next phrase, the way podcast and short-form edits hide a continuous take.
 *
 * The old zooms were 5–10% pulses that eased back out within a quarter second, centred on the
 * frame (in a selfie that is the mouth or chin), placed on audio/motion peaks — on a handheld
 * clip motion peaks are everywhere. They rendered, but nobody could see them.
 */
object PunchInPlanner {
    /** A phrase starts after a pause of at least this long, or after . ! ? : */
    private const val PHRASE_GAP_US = 250_000L
    private const val MIN_LEAD_US = 400_000L
    private const val MIN_HOLD_US = 700_000L
    private const val TAIL_US = 300_000L

    /** Clip-local start time of every phrase in [words]. */
    fun phraseStarts(words: List<TimedWord>): List<Long> {
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf(words.first().startUs)
        words.zipWithNext().forEach { (previous, word) ->
            val sentenceEnd = previous.text.trimEnd().lastOrNull() in listOf('.', '!', '?', ':')
            if (word.startUs - previous.endUs >= PHRASE_GAP_US || sentenceEnd) result += word.startUs
        }
        return result.distinct().sorted()
    }

    /**
     * Alternating punch-ins on [targets] (clip-local), at least [minIntervalUs] apart: zoom in on one
     * target, back to normal on the next. Targets inside [excluded] ranges (removed pauses) are skipped.
     */
    fun plan(
        analysis: ClipAnalysis,
        targets: List<Long>,
        minIntervalUs: Long,
        scale: Float,
        excluded: List<LongRange> = emptyList(),
        reason: String = "inicio de frase",
    ): List<ZoomDecision> {
        val duration = analysis.durationUs
        val interval = minIntervalUs.coerceAtLeast(MIN_HOLD_US)
        val rhythm = mutableListOf<Long>()
        targets.sorted().forEach { time ->
            if (time < MIN_LEAD_US || time > duration - TAIL_US) return@forEach
            if (excluded.any { time in it }) return@forEach
            if (rhythm.isEmpty() || time - rhythm.last() >= interval) rhythm += time
        }
        val boundedScale = scale.coerceIn(1.06f, ZoomDecision.MAX_ZOOM_SCALE)
        val result = mutableListOf<ZoomDecision>()
        var index = 0
        while (index < rhythm.size) {
            val start = rhythm[index]
            // Back to the normal framing on the next phrase; the last one holds a typical length.
            val nextChange = rhythm.getOrNull(index + 1) ?: (start + interval)
            val end = minOf(nextChange, start + ZoomDecision.MAX_PUNCH_HOLD_US, duration - 1)
            if (end - start >= MIN_HOLD_US) {
                val face = analysis.faceAt(start)
                result += ZoomDecision(
                    timeUs = start, durationUs = end - start, scale = boundedScale,
                    confidence = if (face != null) .9f else .7f,
                    reason = if (face != null) "$reason, no rosto" else reason,
                    hold = true,
                    focusX = face?.centerX ?: .5f,
                    // Without a face, keep the upper-middle of the frame, where a speaker's face usually is.
                    focusY = face?.centerY ?: .4f,
                )
            }
            index += 2
        }
        return result.take(MAX_PUNCH_INS)
    }

    /** Interval between punch-ins for a talking clip, by intensity. */
    fun intervalFor(intensity: AutoEditIntensity): Long = when (intensity) {
        AutoEditIntensity.SUBTLE -> 4_000_000L
        AutoEditIntensity.BALANCED -> 2_800_000L
        AutoEditIntensity.STRONG -> 1_900_000L
    }

    /** Talking-head zoom: 15% (subtle 11%, strong 20%). */
    fun scaleFor(intensity: AutoEditIntensity): Float = when (intensity) {
        AutoEditIntensity.SUBTLE -> 1.11f
        AutoEditIntensity.BALANCED -> 1.15f
        AutoEditIntensity.STRONG -> 1.2f
    }

    private const val MAX_PUNCH_INS = 40
}
