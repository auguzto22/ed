package com.termex.replay15.editor

import com.termex.replay15.editor.captions.LongAudioChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongAudioChunkerTest {
    @Test fun chunksLongAudioWithBoundedOverlap() {
        val chunks = LongAudioChunker.plan(45_000_000L, maxChunkUs = 20_000_000L, overlapUs = 1_000_000L)
        assertEquals(3, chunks.size)
        assertTrue(chunks.zipWithNext().all { (a, b) -> a.endUs - b.startUs == 1_000_000L })
        assertEquals(45_000_000L, chunks.last().endUs)
    }
}
