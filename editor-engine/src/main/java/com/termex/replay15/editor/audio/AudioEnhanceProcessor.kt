package com.termex.replay15.editor.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The audio processing, as one streaming pass over sample blocks.
 *
 * It is stateful on purpose. The same instance serves the preview, which feeds it one small
 * block at a time, and the export, which feeds it larger ones, so the filters carry their
 * memory across calls and the result does not click at block borders. A pure per-block
 * implementation would tick audibly on every boundary.
 *
 * Everything is float in -1..1 and nothing allocates per block.
 */
class AudioEnhanceProcessor(
    private val settings: AudioEnhance,
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    init {
        require(sampleRate > 0 && channelCount > 0)
    }

    // Voice enhance: a one-pole high-pass to drop rumble, then a peaking section for presence.
    private val highPassStates = FloatArray(channelCount)
    private val presenceState = FloatArray(channelCount)

    // Gate: envelope follower plus a smoothed gain, so opening it does not click.
    private var gateGain = 1f

    // Compressor and limiter share one envelope so they do not fight each other.
    private var compressionGain = 1f
    private var normalizeGain = 1f

    private val threshold: Float get() = lerpDb(-24f, -8f, settings.compression)
    private val ratio: Float get() = 1f + settings.compression * 8f

    /**
     * Processes [frames] interleaved samples in place and returns the same frame count.
     * [samples] holds at least `frames * channelCount` values.
     */
    fun process(samples: FloatArray, frames: Int): Int {
        if (settings.isNeutral || frames <= 0) return frames

        for (frame in 0 until frames) {
            val base = frame * channelCount
            var framePeak = 0f
            for (channel in 0 until channelCount) {
                val value = samples[base + channel]
                framePeak = max(framePeak, abs(value))
            }
            // The follower has to be written back, or the level it reports never moves and
            // every stage downstream sees a constant that never crosses its threshold.
            levelEnvelopeFrame = follow(framePeak, levelEnvelopeFrame, ATTACK, RELEASE)
            framePeak = levelEnvelopeFrame

            var gain = 1f
            if (settings.noiseReduction > 0f) {
                val noiseFloor = framePeak.coerceAtMost(NOISE_CEILING)
                val opened = smoothStep(noiseFloor, NOISE_CEILING, framePeak)
                val target = 1f - settings.noiseReduction * (1f - opened)
                gateGain = smooth(gateGain, target, GATE_SMOOTHING)
                gain *= gateGain
            }
            if (settings.compression > 0f) {
                // Levelling, not only downward. A compressor that only ever attenuates would
                // leave the quiet passages where they were, which is the half of the job the
                // slider is sold on: even out the take, not just tame the peaks.
                val referenceDb = threshold
                val deviation = db(framePeak) - referenceDb
                val correctionDb = if (deviation > 0f) {
                    -deviation * (1f - 1f / ratio)
                } else {
                    // Boosting is capped; a true limiter would let silence explode into noise.
                    -deviation.coerceAtLeast(-MAX_BOOST_DB)
                }
                compressionGain = smooth(compressionGain, dbToGain(correctionDb), COMPRESSOR_SMOOTHING)
                gain *= compressionGain
            }
            if (settings.normalize > 0f) {
                // A true peak normalize needs the whole file, which streaming does not have.
                // Steering the level toward the target is the honest single-pass equivalent.
                val target = dbToGain(lerpDb(-12f, -1f, settings.normalize))
                val ratio = (target / max(framePeak, SILENCE)).coerceIn(.25f, MAX_NORMALIZE_GAIN)
                normalizeGain = smooth(normalizeGain, ratio, NORMALIZE_SMOOTHING)
                gain *= normalizeGain
            }

            for (channel in 0 until channelCount) {
                var value = samples[base + channel] * gain
                if (settings.voiceEnhance > 0f) value = voice(value, channel)
                samples[base + channel] = value.coerceIn(-1f, 1f)
            }
        }
        return frames
    }

    private var levelEnvelopeFrame = 0f

    /** High-pass then presence lift, which is what makes dialogue cut through a mix. */
    private fun voice(value: Float, channel: Int): Float {
        val amount = settings.voiceEnhance
        // Remove rumble: everything below the low cut is mud that only masks the voice.
        val cutCoefficient = 1f - exp(-TWO_PI * lerp(18_000f, 120f, amount) / sampleRate)
        val previous = highPassStates[channel]
        highPassStates[channel] = value + cutCoefficient * (previous - value)
        val aboveCut = highPassStates[channel] - previous

        // The band between the low cut and the presence centre is what carries intelligibility.
        val presenceCoefficient = 1f - exp(-TWO_PI * PRESENCE_HZ / sampleRate)
        val belowPresence = presenceState[channel] + presenceCoefficient * (aboveCut - presenceState[channel])
        presenceState[channel] = belowPresence
        val presenceBand = aboveCut - belowPresence

        return (aboveCut + presenceBand * PRESENCE_BOOST * amount).coerceIn(-1f, 1f)
    }

    /** One-pole attack/release follower, so the gain does not react to a single spike. */
    private fun follow(value: Float, current: Float, attack: Float, release: Float): Float {
        val coefficient = if (value > current) attack else release
        return current + coefficient * (value - current)
    }

    private fun smooth(current: Float, target: Float, rate: Float): Float =
        current + rate * (target - current)

    fun reset() {
        highPassStates.fill(0f)
        presenceState.fill(0f)
        gateGain = 1f
        levelEnvelopeFrame = 0f
        compressionGain = 1f
        normalizeGain = 1f
    }

    private companion object {
        const val ATTACK = .5f
        const val RELEASE = .08f
        const val GATE_SMOOTHING = .3f
        const val COMPRESSOR_SMOOTHING = .2f
        const val NORMALIZE_SMOOTHING = .08f
        const val SILENCE = 1e-4f
        const val MAX_NORMALIZE_GAIN = 4f
        const val MAX_BOOST_DB = 9f
        const val NOISE_CEILING = .08f
        const val PRESENCE_HZ = 3_000f
        const val PRESENCE_BOOST = .8f
        const val TWO_PI = (2.0 * Math.PI).toFloat()

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun db(value: Float): Float = if (value <= 1e-6f) -120f else 20f * ln(value)
        fun dbToGain(value: Float): Float = 10f.pow(value / 20f)
        fun lerpDb(from: Float, to: Float, t: Float) = lerp(from, to, t)

        fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
            if (edge1 <= edge0) return if (value >= edge1) 1f else 0f
            val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}

/** True peak of interleaved samples, used by the export path where a full scan is possible. */
fun peakOf(samples: FloatArray, frames: Int, channelCount: Int): Float {
    var peak = 0f
    val limit = min(frames * channelCount, samples.size)
    for (i in 0 until limit) peak = max(peak, abs(samples[i]))
    return peak
}

/** RMS of interleaved samples, the level a compressor actually cares about. */
fun rmsOf(samples: FloatArray, frames: Int, channelCount: Int): Float {
    val limit = min(frames * channelCount, samples.size)
    if (limit <= 0) return 0f
    var sum = 0f
    for (i in 0 until limit) sum += samples[i] * samples[i]
    return sqrt(sum / limit)
}
