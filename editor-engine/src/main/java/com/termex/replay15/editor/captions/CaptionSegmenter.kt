package com.termex.replay15.editor.captions

import java.util.Locale
import java.util.UUID

data class CaptionSegmenterConfig(
    val maxWords: Int = 4,
    val maxLines: Int = 2,
    val maxGapUs: Long = 650_000L,
    val maxDurationUs: Long = 4_500_000L,
    val maxCharsPerLine: Int = 34,
) {
    init {
        require(maxWords in 1..12 && maxLines in 1..2)
        require(maxGapUs > 0L && maxDurationUs > 0L && maxCharsPerLine >= 8)
    }
}

/** Groups measured words only; it never fabricates or moves a timestamp. */
class CaptionSegmenter(private val config: CaptionSegmenterConfig = CaptionSegmenterConfig()) {
    fun segment(words: List<WordTimestamp>): List<TimedCaptionSegment> {
        val ordered = words.filter { it.word.isNotBlank() && it.endUs > it.startUs }.sortedBy { it.startUs }
        if (ordered.isEmpty()) return emptyList()
        val result = mutableListOf<TimedCaptionSegment>()
        val current = mutableListOf<WordTimestamp>()

        fun flush() {
            if (current.isEmpty()) return
            result += buildSegment(current.toList())
            current.clear()
        }

        for (word in ordered) {
            val previous = current.lastOrNull()
            val candidate = if (previous == null) current + word else current + word
            val forceBoundary = previous != null && (
                endsSentence(previous.word) ||
                    word.startUs - previous.endUs > config.maxGapUs ||
                    candidate.size > config.maxWords ||
                    word.endUs - candidate.first().startUs > config.maxDurationUs ||
                    lineCount(candidate) > config.maxLines
                )
            if (forceBoundary) flush()
            current += word
        }
        flush()
        return result
    }

    private fun buildSegment(words: List<WordTimestamp>): TimedCaptionSegment {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val next = if (line.isBlank()) word.word else "$line ${word.word}"
            if (line.isNotBlank() && next.length > config.maxCharsPerLine && lines.size < config.maxLines - 1) {
                lines += line
                line = word.word
            } else line = next
        }
        if (line.isNotBlank()) lines += line
        return TimedCaptionSegment(
            id = UUID.randomUUID().toString(),
            text = lines.take(config.maxLines).joinToString("\n"),
            startUs = words.first().startUs,
            endUs = words.last().endUs,
            wordCues = words,
        )
    }

    private fun lineCount(words: List<WordTimestamp>): Int {
        var lines = 1
        var length = 0
        for (word in words) {
            val added = if (length == 0) word.word.length else word.word.length + 1
            if (length > 0 && length + added > config.maxCharsPerLine) {
                lines++
                length = word.word.length
            } else length += added
        }
        return lines
    }

    private fun endsSentence(word: String): Boolean {
        val last = word.trimEnd().lastOrNull() ?: return false
        return last in ".!?;:" || word.trimEnd().endsWith("…")
    }
}

private fun String.normalizedCaptionWord(): String = trim()
    .lowercase(Locale.forLanguageTag("pt-BR"))
    .replace(Regex("[^\\p{L}\\p{N}]+"), "")

data class TimedWordChunk(val chunkStartUs: Long, val chunkEndUs: Long, val words: List<WordTimestamp>)

/** Removes only prefix words repeated inside an explicitly declared chunk overlap. */
class TranscriptDeduplicator(private val overlapUs: Long = 1_500_000L) {
    init { require(overlapUs >= 0L) }

    fun merge(chunks: List<TimedWordChunk>): List<WordTimestamp> {
        val output = mutableListOf<WordTimestamp>()
        for (chunk in chunks.sortedBy { it.chunkStartUs }) {
            val incoming = chunk.words.map { word ->
                if (word.startUs < chunk.chunkStartUs) word.copy(
                    startUs = chunk.chunkStartUs,
                    endUs = maxOf(chunk.chunkStartUs + 1L, word.endUs),
                ) else word
            }.sortedBy { it.startUs }
            if (output.isEmpty()) {
                output += incoming
                continue
            }
            val overlapStart = chunk.chunkStartUs
            val maxPrefix = incoming.indexOfFirst { it.startUs >= overlapStart + overlapUs }
                .let { if (it < 0) incoming.size else it }
            var duplicateCount = 0
            for (count in minOf(maxPrefix, output.size, 12) downTo 1) {
                val suffix = output.takeLast(count)
                val prefix = incoming.take(count)
                if (suffix.zip(prefix).all { (old, next) ->
                        old.word.normalizedCaptionWord() == next.word.normalizedCaptionWord() &&
                            old.endUs >= overlapStart - overlapUs &&
                            next.startUs < overlapStart + overlapUs
                    }) {
                    duplicateCount = count
                    break
                }
            }
            output += incoming.drop(duplicateCount)
        }
        return output.sortedBy { it.startUs }
    }
}
