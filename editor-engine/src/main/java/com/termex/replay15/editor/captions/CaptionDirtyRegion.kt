package com.termex.replay15.editor.captions

enum class DirtyReason { MISSING_SPEECH, LOW_CONFIDENCE, ALIGNMENT_FAILURE, TRANSCRIPTION_CONFLICT, CHUNK_BOUNDARY, LOW_SPEECH_COVERAGE, INCOHERENT }

data class CaptionDirtyRegion(val startMs: Long, val endMs: Long, val reasons: Set<DirtyReason>, val attemptCount: Int = 0)

data class DirtyRegionConfig(
    val mergeDistanceMs: Long = 700,
    val contextMs: Long = 5_000,
    val maxRecoveryAttempts: Int = 2,
    val highConfidence: Float = .75f,
    val goodCoverage: Float = .65f
)

fun mergeNearbyDirtyRegions(regions: List<CaptionDirtyRegion>, distanceMs: Long = 700): List<CaptionDirtyRegion> {
    require(distanceMs >= 0)
    val sorted = regions.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
    val merged = mutableListOf<CaptionDirtyRegion>()
    for (region in sorted) {
        val previous = merged.lastOrNull()
        if (previous != null && region.startMs <= previous.endMs + distanceMs) {
            merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, region.endMs),
                reasons = previous.reasons + region.reasons, attemptCount = maxOf(previous.attemptCount, region.attemptCount))
        } else merged += region
    }
    return merged
}

class CaptionDirtyRegionDetector(private val config: DirtyRegionConfig = DirtyRegionConfig()) {
    fun detect(region: SpeechRegion, words: List<CaptionWord>, gaps: List<SpeechGap>, quality: CaptionSegmentQuality): List<CaptionDirtyRegion> {
        val dirty = mutableListOf<CaptionDirtyRegion>()
        for (gap in gaps) dirty += CaptionDirtyRegion(gap.startMs, gap.endMs, setOf(DirtyReason.MISSING_SPEECH))
        for (word in words) {
            val reasons = buildSet {
                if ((word.confidence ?: 0f) < config.highConfidence) add(DirtyReason.LOW_CONFIDENCE)
                if (word.alignmentConfidence != null && word.alignmentConfidence < .7f || word.endMs <= word.startMs)
                    add(DirtyReason.ALIGNMENT_FAILURE)
                if (word.verificationState == VerificationState.CONFLICT) add(DirtyReason.TRANSCRIPTION_CONFLICT)
            }
            if (reasons.isNotEmpty()) dirty += CaptionDirtyRegion(word.startMs, maxOf(word.startMs + 1, word.endMs), reasons)
        }
        if (quality.speechCoverage < config.goodCoverage && dirty.isEmpty()) {
            dirty += CaptionDirtyRegion(region.startMs, region.endMs, setOf(DirtyReason.LOW_SPEECH_COVERAGE))
        }
        return dirty
    }
}
