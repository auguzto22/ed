package com.termex.replay15.editor.highlights

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects highlights locally without any network call.
 *
 * This analyzer uses:
 *  1. Audio energy spikes (loud moments)
 *  2. Speech density changes (bursts of fast speech)
 *  3. Silence detection (natural break points that bound interesting regions)
 *
 * The result is a list of non-overlapping regions ordered by score.
 * It works even without captions, though captions improve quality significantly.
 */
class LocalHighlightAnalyzer : HighlightAnalyzer {

    override suspend fun analyze(
        request: HighlightRequest,
        checkCancelled: () -> Unit,
    ): HighlightResult = withContext(Dispatchers.Default) {
        checkCancelled()
        val candidates = mutableListOf<Highlight>()

        // --- Audio energy analysis ---
        if (request.audioEnergy.isNotEmpty()) {
            candidates += analyzeAudioEnergy(request, checkCancelled)
        }

        // --- Speech density analysis ---
        if (request.transcriptSegments.isNotEmpty()) {
            candidates += analyzeSpeechDensity(request, checkCancelled)
        }

        checkCancelled()

        // Merge overlapping candidates and pick top results
        val merged = mergeOverlapping(candidates, request.minDurationMs)
        val sorted = merged.sortedByDescending { it.score }.take(request.maxHighlights)

        HighlightResult(
            highlights = sorted,
            source = HighlightSource.LOCAL,
            warnings = buildList {
                if (request.transcriptSegments.isEmpty()) add("Sem legendas; a análise usou apenas áudio.")
                if (request.audioEnergy.isEmpty()) add("Sem dados de áudio; a análise usou apenas texto.")
                if (sorted.isEmpty()) add("Nenhum momento de destaque identificado.")
            },
        )
    }

    private fun analyzeAudioEnergy(
        request: HighlightRequest,
        checkCancelled: () -> Unit,
    ): List<Highlight> {
        if (request.audioEnergy.size < 3) return emptyList()

        // Calculate overall statistics
        val rmsValues = request.audioEnergy.map { it.rms }
        val meanRms = rmsValues.average().toFloat()
        val stdRms = standardDeviation(rmsValues)
        val threshold = meanRms + stdRms * 0.8f

        // Find regions above threshold
        val regions = mutableListOf<Highlight>()
        var regionStart = -1L
        var regionPeak = 0f

        for (point in request.audioEnergy) {
            if (point.rms > threshold) {
                if (regionStart < 0) regionStart = point.timeMs
                regionPeak = maxOf(regionPeak, point.rms)
            } else if (regionStart >= 0) {
                val durationMs = point.timeMs - regionStart
                if (durationMs >= request.minDurationMs) {
                    // Extend region to nearest natural boundary
                    val extendedEnd = minOf(point.timeMs + 500, request.projectDurationMs)
                    val score = ((regionPeak - meanRms) / (stdRms * 2f + 0.001f)).coerceIn(0.3f, 1f)
                    regions += Highlight(
                        startMs = regionStart,
                        endMs = extendedEnd.coerceAtMost(regionStart + request.maxDurationMs),
                        reason = "Momento de alta energia no áudio",
                        score = score,
                        source = HighlightSource.LOCAL,
                    )
                }
                regionStart = -1L
                regionPeak = 0f
            }
        }

        checkCancelled()
        return regions
    }

    private fun analyzeSpeechDensity(
        request: HighlightRequest,
        checkCancelled: () -> Unit,
    ): List<Highlight> {
        val segments = request.transcriptSegments
        if (segments.size < 3) return emptyList()

        // Compute words-per-second in sliding windows
        val windowMs = 10_000L // 10-second windows
        val stepMs = 2_000L
        val densities = mutableListOf<Pair<Long, Float>>() // timeMs to wps

        var windowStart = segments.first().startMs
        while (windowStart + windowMs <= request.projectDurationMs) {
            checkCancelled()
            val windowEnd = windowStart + windowMs
            val inWindow = segments.filter { it.startMs < windowEnd && it.endMs > windowStart }
            val wordCount = inWindow.sumOf { it.text.split(Regex("\\s+")).size }
            val durationSec = windowMs / 1_000f
            densities += windowStart to (wordCount / durationSec)
            windowStart += stepMs
        }

        if (densities.isEmpty()) return emptyList()

        // Find peaks in speech density (rapid, engaged speech)
        val meanDensity = densities.map { it.second }.average().toFloat()
        val stdDensity = standardDeviation(densities.map { it.second })
        val densityThreshold = meanDensity + stdDensity * 0.5f

        val candidates = mutableListOf<Highlight>()
        var peakStart = -1L
        var peakScore = 0f

        for ((timeMs, density) in densities) {
            if (density > densityThreshold) {
                if (peakStart < 0) peakStart = timeMs
                peakScore = maxOf(peakScore, (density - meanDensity) / (stdDensity * 2f + 0.001f))
            } else if (peakStart >= 0) {
                val durationMs = timeMs - peakStart
                if (durationMs >= request.minDurationMs) {
                    candidates += Highlight(
                        startMs = peakStart,
                        endMs = minOf(timeMs + windowMs / 2, request.projectDurationMs)
                            .coerceAtMost(peakStart + request.maxDurationMs),
                        reason = "Trecho com fala rápida e intensa",
                        score = peakScore.coerceIn(0.2f, 0.9f),
                        source = HighlightSource.LOCAL,
                    )
                }
                peakStart = -1L
                peakScore = 0f
            }
        }

        // Also detect long silences as break points (silent gaps > 3s indicate topic changes)
        val gapHighlights = mutableListOf<Highlight>()
        segments.zipWithNext { a, b ->
            val gapMs = b.startMs - a.endMs
            if (gapMs > 3_000) {
                // The interesting moment is right after the pause (new topic)
                val start = b.startMs
                val end = minOf(start + request.maxDurationMs, request.projectDurationMs)
                if (end - start >= request.minDurationMs) {
                    gapHighlights += Highlight(
                        startMs = start,
                        endMs = end,
                        reason = "Possível mudança de assunto após pausa",
                        score = 0.4f,
                        source = HighlightSource.LOCAL,
                    )
                }
            }
        }

        checkCancelled()
        return candidates + gapHighlights
    }

    companion object {
        /** Merge overlapping highlight regions, keeping the highest score. */
        internal fun mergeOverlapping(highlights: List<Highlight>, minGapMs: Long): List<Highlight> {
            if (highlights.isEmpty()) return emptyList()
            val sorted = highlights.sortedBy { it.startMs }
            val merged = mutableListOf<Highlight>()
            var current = sorted.first()

            for (next in sorted.drop(1)) {
                if (next.startMs <= current.endMs + minGapMs) {
                    // Merge: extend end, keep best score and reason
                    val bestScore = maxOf(current.score, next.score)
                    val bestReason = if (next.score > current.score) next.reason else current.reason
                    current = current.copy(
                        endMs = maxOf(current.endMs, next.endMs),
                        score = bestScore,
                        reason = bestReason,
                    )
                } else {
                    merged += current
                    current = next
                }
            }
            merged += current
            return merged
        }

        internal fun standardDeviation(values: List<Float>): Float {
            if (values.size < 2) return 0f
            val mean = values.average()
            val variance = values.sumOf { v -> (v - mean) * (v - mean) } / (values.size - 1)
            return kotlin.math.sqrt(variance).toFloat()
        }
    }
}

