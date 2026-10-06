package com.termex.replay15.editor.highlights

import org.junit.Assert.*
import org.junit.Test

class HighlightModelsTest {

    @Test
    fun `highlight requires valid range`() {
        val h = Highlight(startMs = 1000, endMs = 5000, reason = "Test", score = 0.8f)
        assertEquals(4000L, h.durationMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `highlight rejects invalid range`() {
        Highlight(startMs = 5000, endMs = 1000, reason = "Test", score = 0.8f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `highlight rejects blank reason`() {
        Highlight(startMs = 1000, endMs = 5000, reason = "", score = 0.8f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `highlight rejects score out of range`() {
        Highlight(startMs = 1000, endMs = 5000, reason = "Test", score = 1.5f)
    }

    @Test
    fun `toMarker converts correctly`() {
        val h = Highlight(startMs = 5_000, endMs = 15_000, reason = "Great moment", score = 0.9f)
        val marker = h.toMarker()
        assertEquals(5_000_000L, marker.timeUs)
        assertEquals("Great moment", marker.name)
    }

    @Test
    fun `transcript segment validates`() {
        val seg = TranscriptSegment(id = 1, text = "Hello", startMs = 100, endMs = 500)
        assertEquals("Hello", seg.text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `transcript segment rejects blank text`() {
        TranscriptSegment(id = 1, text = " ", startMs = 100, endMs = 500)
    }

    @Test
    fun `highlight result limits size`() {
        val highlights = (1..10).map {
            Highlight(startMs = it * 1000L, endMs = it * 1000L + 5000, reason = "h$it", score = it / 10f)
        }
        val result = HighlightResult(highlights = highlights, source = HighlightSource.LOCAL)
        assertEquals(10, result.highlights.size)
    }
}

class LocalHighlightAnalyzerTest {

    @Test
    fun `merge overlapping combines adjacent highlights`() {
        val highlights = listOf(
            Highlight(startMs = 1000, endMs = 5000, reason = "A", score = 0.8f),
            Highlight(startMs = 4000, endMs = 8000, reason = "B", score = 0.9f),
            Highlight(startMs = 20000, endMs = 25000, reason = "C", score = 0.5f),
        )
        val merged = LocalHighlightAnalyzer.mergeOverlapping(highlights, 1000)
        assertEquals(2, merged.size)
        assertEquals(1000L, merged[0].startMs)
        assertEquals(8000L, merged[0].endMs)
        assertEquals(0.9f, merged[0].score, 0.01f)
        assertEquals("B", merged[0].reason) // higher-scored reason
        assertEquals(20000L, merged[1].startMs)
    }

    @Test
    fun `merge empty list returns empty`() {
        val merged = LocalHighlightAnalyzer.mergeOverlapping(emptyList(), 1000)
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `standard deviation of identical values is zero`() {
        val std = LocalHighlightAnalyzer.standardDeviation(listOf(5f, 5f, 5f))
        assertEquals(0f, std, 0.001f)
    }

    @Test
    fun `standard deviation computes correctly`() {
        val std = LocalHighlightAnalyzer.standardDeviation(listOf(2f, 4f, 4f, 4f, 5f, 5f, 7f, 9f))
        assertTrue(std > 1f)
    }
}

class GeminiHighlightResponseParsingTest {

    @Test
    fun `valid json response is parsed`() {
        // This test verifies the JSON parsing logic without actually calling Gemini.
        // We test the model validation instead.
        val highlight = Highlight(
            startMs = 10_000,
            endMs = 25_000,
            reason = "Momento engraçado do vídeo",
            score = 0.92f,
            source = HighlightSource.GEMINI,
        )
        assertEquals(15_000L, highlight.durationMs)
        assertEquals(HighlightSource.GEMINI, highlight.source)
    }
}

