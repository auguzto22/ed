package com.termex.replay15.editor.captions

data class CaptionWindow(val startMs: Long, val endMs: Long)

data class InitialWindowConfig(
    val maxWindowMs: Long = 30_000,
    val targetWindowMs: Long = 20_000,
    val overlapMs: Long = 700,
    val maxSilenceInsideMs: Long = 1_000,
)

/** Includes VAD padding but keeps long silent spans out of the first ASR pass. */
fun planInitialCaptionWindows(speech: List<SpeechRegion>, config: InitialWindowConfig = InitialWindowConfig()): List<CaptionWindow> {
    require(config.maxWindowMs >= config.targetWindowMs && config.targetWindowMs > config.overlapMs && config.overlapMs >= 0)
    val groups = mutableListOf<CaptionWindow>()
    for (region in speech.filter { it.endMs > it.startMs }.sortedBy { it.startMs }) {
        val previous = groups.lastOrNull()
        if (previous != null && region.startMs - previous.endMs <= config.maxSilenceInsideMs &&
            region.endMs - previous.startMs <= config.maxWindowMs) {
            groups[groups.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, region.endMs))
        } else groups += CaptionWindow(region.startMs, region.endMs)
    }
    return groups.flatMap { group ->
        if (group.endMs - group.startMs <= config.maxWindowMs) listOf(group)
        else buildList {
            var start = group.startMs
            while (start < group.endMs) {
                val end = minOf(start + config.targetWindowMs, group.endMs)
                add(CaptionWindow(start, end))
                if (end == group.endMs) break
                // The overlap is part of the audio evidence. It is removed
                // later by timed-word deduplication, never by text replacement.
                start = (end - config.overlapMs).coerceAtLeast(start + 1)
            }
        }
    }
}
