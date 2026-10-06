package com.termex.replay15.editor.beat

import com.termex.replay15.editor.autoedit.AudioSample
import com.termex.replay15.editor.domain.TimelineMarker
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How eagerly beats are reported. */
data class BeatOptions(
    /** Lower values demand a sharper energy rise before counting something as a beat. */
    val sensitivity: Float = 1f,
    val minBpm: Float = 70f,
    val maxBpm: Float = 180f,
    /** Beats closer together than this are one hit heard twice. */
    val minIntervalUs: Long = 180_000L,
) {
    init {
        require(sensitivity in .2f..3f)
        require(minBpm in 40f..200f && maxBpm in minBpm..240f)
        require(minIntervalUs >= 50_000L)
    }
}

data class Beat(val timeUs: Long, val strength: Float, val downbeat: Boolean) {
    init {
        require(timeUs >= 0L)
        require(strength.isFinite() && strength >= 0f)
    }
}

/**
 * A track's rhythmic grid.
 *
 * [confidence] is how well the onsets actually line up with the chosen tempo, so the editor
 * can tell the user when a clip simply has no beat instead of drawing a confident grid that
 * is actually noise.
 */
data class BeatMap(
    val bpm: Float,
    val beats: List<Beat>,
    val confidence: Float,
) {
    init {
        require(bpm.isFinite() && bpm > 0f)
        require(confidence in 0f..1f)
    }

    val isEmpty: Boolean get() = beats.isEmpty()

    fun toMarkers(prefix: String = "Batida", color: Int = 0xFFFF5C7A.toInt()): List<TimelineMarker> =
        beats.map { beat ->
            TimelineMarker(
                timeUs = beat.timeUs,
                name = if (beat.downbeat) "$prefix forte" else prefix,
                color = color,
            )
        }
}

/**
 * Finds the beat from an energy envelope.
 *
 * It runs on the RMS and peak bins the audio analyzer already produced, so no second decode
 * of the media is needed and the whole detector stays a pure function that can be tested
 * without a device.
 *
 * The method is a rectified energy flux: only a rise in loudness can start a beat, which is
 * what separates a drum hit from the decay that follows it. The tempo then comes from the
 * periodicity of those onsets, and the final grid is phase-locked to whichever offset puts
 * the most onset energy on the line.
 */
object BeatDetector {

    fun detect(samples: List<AudioSample>, options: BeatOptions = BeatOptions()): BeatMap {
        if (samples.size < MIN_BINS) return BeatMap(120f, emptyList(), 0f)

        val onsets = onsetStrength(samples, options.sensitivity)
        val periodBins = bestPeriodBins(onsets, options) ?: return BeatMap(120f, emptyList(), 0f)

        val phase = bestPhase(onsets, periodBins)
        val beats = buildBeats(onsets, periodBins, phase, samples, options)
        if (beats.isEmpty()) return BeatMap(120f, emptyList(), 0f)

        // Confidence is how much of the grid actually lands on an onset; a rhythm that drifts
        // out of phase with the music should not be reported as if it were solid.
        val total = onsets.sum()
        val onGrid = beats.sumOf { it.strength.toDouble() }
        val confidence = if (total <= 0f) 0f else (onGrid / total).toFloat().coerceIn(0f, 1f)

        val bpm = 60_000_000f / (periodBins * BIN_US)
        return BeatMap(bpm.coerceIn(options.minBpm, options.maxBpm), beats, confidence)
    }

    /**
     * Rectified rise in energy per bin. A bin that is quieter than the one before it carries
     * no onset, which is what stops the tail of every hit from being counted again.
     */
    private fun onsetStrength(samples: List<AudioSample>, sensitivity: Float): FloatArray {
        val raw = FloatArray(samples.size)
        for (i in samples.indices) {
            val previous = if (i == 0) samples[i] else samples[i - 1]
            val current = samples[i]
            val rise = current.rms - previous.rms
            val transient = current.peak - previous.peak
            raw[i] = max(0f, rise) + max(0f, transient) * TRANSIENT_WEIGHT
        }
        val peak = raw.maxOrNull() ?: 0f
        if (peak <= 0f) return FloatArray(samples.size)
        val normalized = FloatArray(raw.size) { raw[it] / peak * sensitivity }
        // A three-tap smoother removes single-bin clicks that are not musical.
        return FloatArray(normalized.size) { index ->
            val from = max(0, index - 1)
            val to = min(normalized.lastIndex, index + 1)
            var sum = 0f
            for (i in from..to) sum += normalized[i]
            sum / (to - from + 1)
        }
    }

    /**
     * Picks the beat period by scoring how well the onsets repeat at that lag. A lag is only
     * a candidate if its period falls inside the tempo range the user can work with.
     */
    private fun bestPeriodBins(onsets: FloatArray, options: BeatOptions): Int? {
        val minPeriod = (60_000_000f / options.maxBpm / BIN_US).roundToInt().coerceAtLeast(1)
        val maxPeriod = (60_000_000f / options.minBpm / BIN_US).roundToInt()
        if (maxPeriod <= minPeriod || onsets.size < minPeriod * 2) return null

        var bestPeriod = 0
        var bestScore = 0f
        for (period in minPeriod..min(maxPeriod, onsets.size / 2)) {
            var score = 0f
            for (i in 0 until onsets.size - period) {
                // Weighting the first half of each period keeps the score from rewarding a
                // lag that only matches because it is short.
                val weight = 1f - 0.5f * ((i % period).toFloat() / period)
                score += onsets[i] * onsets[i + period] * weight
            }
            if (score > bestScore) {
                bestScore = score
                bestPeriod = period
            }
        }
        return bestPeriod.takeIf { it > 0 }
    }

    /** The grid offset that collects the most onset energy. */
    private fun bestPhase(onsets: FloatArray, period: Int): Int {
        var bestOffset = 0
        var bestScore = -1f
        for (offset in 0 until period) {
            var score = 0f
            var i = offset
            while (i < onsets.size) {
                score += onsets[i]
                i += period
            }
            if (score > bestScore) {
                bestScore = score
                bestOffset = offset
            }
        }
        return bestOffset
    }

    private fun buildBeats(
        onsets: FloatArray,
        period: Int,
        phase: Int,
        samples: List<AudioSample>,
        options: BeatOptions,
    ): List<Beat> {
        val minimumGapBins = (options.minIntervalUs / BIN_US).toInt().coerceAtLeast(1)
        val beats = mutableListOf<Beat>()
        var index = phase
        var ordinal = 0
        while (index < onsets.size) {
            // A grid slot with nothing in it is not a beat, and filling it in would put marks
            // on silence the user then has to delete.
            val strength = onsets[index]
            val tooSoon = beats.any { abs(index - it.timeUs / BIN_US) < minimumGapBins }
            if (strength > MIN_ONSET_STRENGTH && !tooSoon) {
                val timeUs = samples[index.coerceIn(0, samples.lastIndex)].startUs
                beats += Beat(timeUs, strength.coerceIn(0f, 1f), ordinal % BEATS_PER_BAR == 0)
                ordinal++
            }
            index += period
        }
        return beats
    }

    private const val BIN_US = 100_000L
    private const val MIN_BINS = 8
    private const val TRANSIENT_WEIGHT = .5f
    private const val MIN_ONSET_STRENGTH = .04f
    private const val BEATS_PER_BAR = 4
}
