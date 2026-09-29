package com.termex.replay15.editor

import com.termex.replay15.editor.captions.TimedWordChunk
import com.termex.replay15.editor.captions.TranscriptDeduplicator
import com.termex.replay15.editor.captions.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptDeduplicatorTest {
    @Test fun removesOnlyOverlapPrefix() {
        val first = TimedWordChunk(0, 10_000_000, listOf(
            WordTimestamp("então", 8_000_000, 8_300_000),
            WordTimestamp("agora", 8_400_000, 8_700_000),
            WordTimestamp("vamos", 8_800_000, 9_100_000),
        ))
        val second = TimedWordChunk(8_500_000, 18_500_000, listOf(
            WordTimestamp("agora", 8_500_000, 8_800_000),
            WordTimestamp("vamos", 8_900_000, 9_200_000),
            WordTimestamp("começar", 9_300_000, 9_700_000),
        ))
        val merged = TranscriptDeduplicator().merge(listOf(first, second))
        assertEquals(listOf("então", "agora", "vamos", "começar"), merged.map { it.word })
    }

    @Test fun preservesLegitimateRepeatedWordsOutsideOverlap() {
        val merged = TranscriptDeduplicator().merge(listOf(
            TimedWordChunk(0, 2_000_000, listOf(WordTimestamp("vai", 0, 200_000))),
            TimedWordChunk(2_000_000, 4_000_000, listOf(WordTimestamp("vai", 2_100_000, 2_300_000))),
        ))
        assertEquals(listOf("vai", "vai"), merged.map { it.word })
    }
}
