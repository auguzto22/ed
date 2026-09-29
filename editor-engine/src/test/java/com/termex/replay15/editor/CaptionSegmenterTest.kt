package com.termex.replay15.editor

import com.termex.replay15.editor.captions.CaptionSegmenter
import com.termex.replay15.editor.captions.CaptionSegmenterConfig
import com.termex.replay15.editor.captions.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionSegmenterTest {
    private fun words(vararg values: String): List<WordTimestamp> = values.mapIndexed { index, value ->
        WordTimestamp(value, index * 250_000L, index * 250_000L + 100_000L)
    }

    @Test fun respectsFourWordLimitAndMeasuredBounds() {
        val result = CaptionSegmenter(CaptionSegmenterConfig(maxWords = 4)).segment(words("Fala", "galera", "hoje", "eu", "vou"))
        assertEquals(2, result.size)
        assertEquals("Fala galera hoje eu", result.first().text)
        assertEquals(0L, result.first().startUs)
        assertEquals(850_000L, result.first().endUs)
    }

    @Test fun punctuationAndLongPauseStartNewCaptions() {
        val result = CaptionSegmenter().segment(listOf(
            WordTimestamp("Fala", 0, 100_000), WordTimestamp("galera.", 120_000, 300_000),
            WordTimestamp("Hoje", 1_200_000, 1_400_000), WordTimestamp("tem", 1_450_000, 1_600_000),
        ))
        assertEquals(2, result.size)
        assertEquals("Fala galera.", result[0].text)
        assertEquals(1_200_000L, result[1].startUs)
    }

    @Test fun keepsWordTimestampsWhenVisualLineBreaksAreAdded() {
        val result = CaptionSegmenter(CaptionSegmenterConfig(maxWords = 6, maxLines = 2, maxCharsPerLine = 20))
            .segment(words("Mano", "esse", "negócio", "tá", "muito", "bugado"))
        assertTrue(result.single().text.contains('\n'))
        assertEquals(6, result.single().wordCues.size)
        assertEquals(result.single().startUs, result.single().wordCues.first().startUs)
    }
}
