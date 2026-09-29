package com.termex.replay15.editor.captions

import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.sqrt

enum class SpeechGapKind { LEADING, INTERNAL, TRAILING }
data class SpeechGap(
    val startMs: Long, val endMs: Long, val speechProbability: Float,
    val previousWord: CaptionWord?, val nextWord: CaptionWord?, val kind: SpeechGapKind
)
data class CaptionSegmentQuality(
    val transcriptionConfidence: Float?, val alignmentConfidence: Float?,
    val speechCoverage: Float, val unresolvedSpeechGaps: Int
)
data class MissingWordConfig(
    val minimumGapMs: Long = 120,
    val minimumSpeechMs: Long = 100,
    val speechGapThreshold: Float = .55f,
    val wordBoundaryToleranceMs: Long = 20,
    val contextShortMs: Long = 3_000,
    val contextLongMs: Long = 5_000,
    val maxRetries: Int = 2
)

/** Unpadded 10 ms acoustic activity. The energy gate is evidence of sound, not proof of speech. */
class VadTimeline(val frameMs: Long, val probabilities: List<Float>) {
    val durationMs: Long get() = frameMs * probabilities.size
    fun speechRegions(minimumSpeechMs: Long = 100, paddingMs: Long = 300): List<SpeechRegion> {
        val spans = activeSpans(0, durationMs, maxSilenceMs = 250)
            .filter { it.second - it.first >= minimumSpeechMs }
        val regions = mutableListOf<SpeechRegion>()
        for ((start, end) in spans) {
            val padded = SpeechRegion((start - paddingMs).coerceAtLeast(0),
                (end + paddingMs).coerceAtMost(durationMs), 1f)
            val previous = regions.lastOrNull()
            if (previous != null && padded.startMs <= previous.endMs) {
                regions[regions.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, padded.endMs))
            } else regions += padded
        }
        return regions
    }
    fun averageSpeechProbability(startMs: Long, endMs: Long): Float {
        val frames = slice(startMs, endMs)
        return if (frames.isEmpty()) 0f else frames.average().toFloat()
    }
    fun activeDurationMs(startMs: Long, endMs: Long, threshold: Float = .5f): Long =
        slice(startMs, endMs).count { it >= threshold } * frameMs
    fun activeSpans(startMs: Long, endMs: Long, maxSilenceMs: Long = 70): List<Pair<Long, Long>> {
        val from = (startMs / frameMs).toInt().coerceIn(0, probabilities.size)
        val to = ((endMs + frameMs - 1) / frameMs).toInt().coerceIn(from, probabilities.size)
        val spans = mutableListOf<Pair<Long, Long>>()
        var first = -1; var last = -1
        for (frame in from..to) {
            val active = frame < to && probabilities[frame] >= .5f
            if (active) { if (first < 0) first = frame; last = frame }
            if (first >= 0 && (!active && (frame - last) * frameMs > maxSilenceMs || frame == to)) {
                spans += maxOf(startMs, first * frameMs) to minOf(endMs, (last + 1) * frameMs)
                first = -1; last = -1
            }
        }
        return spans
    }
    fun coverage(startMs: Long, endMs: Long, words: List<CaptionWord>, toleranceMs: Long = 20): Float {
        val from = (startMs / frameMs).toInt().coerceIn(0, probabilities.size)
        val to = ((endMs + frameMs - 1) / frameMs).toInt().coerceIn(from, probabilities.size)
        var total = 0f; var covered = 0f
        for (index in from until to) {
            val probability = probabilities[index]
            if (probability < .5f) continue
            total += probability
            val time = index * frameMs
            if (words.any { time >= it.startMs - toleranceMs && time < it.endMs + toleranceMs }) covered += probability
        }
        return if (total == 0f) 1f else (covered / total).coerceIn(0f, 1f)
    }
    private fun slice(startMs: Long, endMs: Long): List<Float> {
        val from = (startMs / frameMs).toInt().coerceIn(0, probabilities.size)
        val to = ((endMs + frameMs - 1) / frameMs).toInt().coerceIn(from, probabilities.size)
        return probabilities.subList(from, to)
    }
    companion object {
        fun fromPcm(file: File, sampleRate: Int = 16_000, sensitive: Boolean = false): VadTimeline {
            val frameSamples = sampleRate / 100
            val bytes = ByteArray(frameSamples * 2)
            val energy = mutableListOf<Double>()
            var previous = 0.0
            FileInputStream(file).use { input ->
                while (true) {
                    var read = 0
                    while (read < bytes.size) {
                        val n = input.read(bytes, read, bytes.size - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < bytes.size) break
                    var power = 0.0
                    for (i in 0 until frameSamples) {
                        val value = ((bytes[2 * i].toInt() and 255) or (bytes[2 * i + 1].toInt() shl 8)).toShort().toInt() / 32768.0
                        val filtered = value - previous * .97
                        previous = value
                        power += filtered * filtered
                    }
                    energy += sqrt(power / frameSamples)
                }
            }
            if (energy.isEmpty()) return VadTimeline(10, emptyList())
            val sorted = energy.sorted()
            val floor = sorted[(sorted.size * .2).toInt().coerceAtMost(sorted.lastIndex)]
            val threshold = max(if (sensitive) .003 else .008, floor * if (sensitive) 2.0 else 3.0)
            return VadTimeline(10, energy.map { if (it >= threshold) 1f else 0f })
        }
    }
}

class MissingWordDetector(private val config: MissingWordConfig = MissingWordConfig()) {
    fun findUnexplainedSpeech(region: SpeechRegion, words: List<CaptionWord>, vad: VadTimeline): List<SpeechGap> {
        val ordered = words.filter { it.endMs > region.startMs && it.startMs < region.endMs }.sortedBy { it.startMs }
        val boundaries = mutableListOf<Triple<Long, Long, Pair<CaptionWord?, CaptionWord?>>>()
        if (ordered.isEmpty()) boundaries += Triple(region.startMs, region.endMs, null to null)
        else {
            boundaries += Triple(region.startMs, ordered.first().startMs, null to ordered.first())
            ordered.zipWithNext().forEach { (before, after) -> boundaries += Triple(before.endMs, after.startMs, before to after) }
            boundaries += Triple(ordered.last().endMs, region.endMs, ordered.last() to null)
        }
        return boundaries.flatMap { (rawStart, rawEnd, neighbors) ->
            val start = (rawStart + config.wordBoundaryToleranceMs).coerceAtMost(rawEnd)
            val end = (rawEnd - config.wordBoundaryToleranceMs).coerceAtLeast(start)
            if (end - start < config.minimumGapMs) emptyList() else vad.activeSpans(start, end).mapNotNull { (spanStart, spanEnd) ->
                val active = vad.activeDurationMs(spanStart, spanEnd)
                val probability = vad.averageSpeechProbability(spanStart, spanEnd)
                if (active < config.minimumSpeechMs || probability < config.speechGapThreshold) null
                else SpeechGap(spanStart, spanEnd, probability, neighbors.first, neighbors.second,
                    when { neighbors.first == null -> SpeechGapKind.LEADING; neighbors.second == null -> SpeechGapKind.TRAILING; else -> SpeechGapKind.INTERNAL })
            }
        }.also { gaps -> gaps.forEach { CaptionDebugLog.d("CaptionMissingWord", "Speech gap detected start=${it.startMs} end=${it.endMs} speechProbability=${it.speechProbability}") } }
    }
    fun quality(region: SpeechRegion, words: List<CaptionWord>, vad: VadTimeline,
        knownGaps: List<SpeechGap>? = null): CaptionSegmentQuality =
        CaptionSegmentQuality(words.mapNotNull { it.confidence }.takeIf { it.size == words.size && it.isNotEmpty() }?.average()?.toFloat(),
            words.mapNotNull { it.alignmentConfidence }.takeIf { it.size == words.size && it.isNotEmpty() }?.average()?.toFloat(),
            vad.coverage(region.startMs, region.endMs, words, config.wordBoundaryToleranceMs),
            (knownGaps ?: findUnexplainedSpeech(region, words, vad)).size)
}

class CaptionMissingWordFinalVerifier(private val minimumCoverage: Float = .8f) {
    fun accepted(quality: CaptionSegmentQuality): Boolean =
        quality.unresolvedSpeechGaps == 0 && quality.speechCoverage >= minimumCoverage
}
