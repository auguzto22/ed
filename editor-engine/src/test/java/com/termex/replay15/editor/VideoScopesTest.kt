package com.termex.replay15.editor

import com.termex.replay15.editor.scopes.ScopeAnalyzer
import com.termex.replay15.editor.scopes.ScopeFrame
import com.termex.replay15.editor.ui.ScopeReadout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScopesTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun solid(color: Int, width: Int = 64, height: Int = 64) =
        ScopeAnalyzer.analyze(FloatArray(width * height).let { IntArray(width * height) { color } }, width, height)

    private fun frame(width: Int, height: Int, color: (Int) -> Int) =
        ScopeAnalyzer.analyze(IntArray(width * height) { color(it) }, width, height)

    @Test
    fun `a flat grey frame has one level and no spread`() {
        val grey = argb(128, 128, 128)
        val scopes = frame(64, 64) { grey }
        assertEquals(128, scopes.lumaAverage)
        assertEquals(0f, scopes.tonalSpread(), .01f)
        assertTrue(scopes.waveform.all { kotlin.math.abs(it - 128f / 255f) < .01f })
        assertEquals(0f, scopes.saturation, .01f)
        assertEquals(0f, scopes.clippedHighlights, .001f)
        assertEquals(0f, scopes.clippedShadows, .001f)
    }

    @Test
    fun `blown highlights are counted, not averaged away`() {
        // Half the frame pure white: the average stays mid-range but the clipping is the story.
        val scopes = frame(64, 64) { if (it % 2 == 0) argb(255, 255, 255) else argb(0, 0, 0) }
        assertTrue("expected about half the frame clipped, got ${scopes.clippedHighlights}", scopes.clippedHighlights in .45f.. .55f)
        assertTrue("expected about half the frame in black, got ${scopes.clippedShadows}", scopes.clippedShadows in .45f.. .55f)
    }

    @Test
    fun `the waveform separates a left-dark frame from a right-bright one`() {
        // Grey, not a primary: a pure red of 220 measures about 46 in luma, which would make the
        // bright half look dark and hide the very thing the waveform is meant to show.
        val scopes = frame(128, 32) { argb(if (it % 128 < 64) 20 else 220, if (it % 128 < 64) 20 else 220, if (it % 128 < 64) 20 else 220) }
        val left = scopes.waveform.take(64).average().toFloat()
        val right = scopes.waveform.drop(64).average().toFloat()
        assertTrue("left half should be dark, got $left", left < .2f)
        assertTrue("right half should be bright, got $right", right > .7f)
    }

    @Test
    fun `the histogram puts every pixel of a flat frame in one bin`() {
        val scopes = solid(argb(200, 200, 200))
        val occupied = scopes.histogram.count { it > 0f }
        assertEquals(1, occupied)
    }

    @Test
    fun `a monochrome frame leaves the vectorscope empty`() {
        val scopes = solid(argb(90, 90, 90))
        assertTrue(scopes.vectorscope.all { it == 0f })
    }

    @Test
    fun `a saturated red frame places energy in the vectorscope`() {
        val scopes = solid(argb(255, 0, 0))
        val total = scopes.vectorscope.indices.filter { it % 2 == 0 }.sumOf { scopes.vectorscope[it].toDouble() }
        assertTrue("a pure red frame must trace somewhere, got $total", total > .9f)
        assertTrue(scopes.saturation > .95f)
    }

    @Test
    fun `the parade reports the channel that is actually present`() {
        val red = solid(argb(255, 0, 0))
        // The parade is normalised by pixel count, so every channel sums to one and the level
        // index carries the meaning: red is all in the top bin, blue is all in the bottom one.
        assertEquals(1f, red.paradeRed.last(), .001f)
        assertEquals(1f, red.paradeBlue.first(), .001f)
        assertEquals(255f, red.redLevel, .5f)
        assertEquals(0f, red.blueLevel, .5f)
    }

    @Test
    fun `a clipped frame is reported as blown, a crushed one as burnt, a flat one as low contrast`() {
        assertTrue(ScopeReadout.headline(frame(64, 64) { argb(255, 255, 255) }).contains("Estourado"))
        // "Crushed" means pixels at or under the shadow clip point, not merely a dark frame:
        // a uniform 20 is dark but perfectly exposed within its own narrow band.
        assertTrue(ScopeReadout.headline(frame(64, 64) { argb(2, 2, 2) }).contains("queimado"))
        assertTrue(ScopeReadout.headline(frame(64, 64) { argb(100, 100, 100) }).contains("Contraste baixo"))
    }

    @Test
    fun `a well-exposed frame is called healthy`() {
        // Near-black through near-white side by side: full spread, and both extremes stay inside
        // the clip points, so no burn should be reported.
        val scopes = frame(64, 64) { argb(if (it % 2 == 0) 24 else 231, if (it % 2 == 0) 24 else 231, if (it % 2 == 0) 24 else 231) }
        assertTrue(ScopeReadout.headline(scopes).contains("saudavel"))
    }

    @Test
    fun `a washed out frame is called out as lacking colour`() {
        assertTrue(ScopeReadout.saturationNote(frame(64, 64) { argb(120, 122, 124) }).contains("lavada"))
    }

    @Test
    fun `a strong colour cast is named`() {
        val tinted = solid(argb(255, 20, 20))
        val note = ScopeReadout.channelNote(tinted)
        assertTrue("expected a red cast, got $note", note != null && note.contains("vermelho"))
    }

    @Test
    fun `a balanced frame reports no cast`() {
        assertNull(ScopeReadout.channelNote(solid(argb(200, 200, 200))))
    }

    @Test
    fun `analysis is deterministic`() {
        val pixels = IntArray(256) { argb((it * 7) % 256, (it * 3) % 256, (it * 11) % 256) }
        assertEquals(ScopeAnalyzer.analyze(pixels, 16, 16), ScopeAnalyzer.analyze(pixels, 16, 16))
    }

    @Test
    fun `analysis rejects a buffer that cannot hold the declared size`() {
        try {
            ScopeAnalyzer.analyze(IntArray(4), 64, 64)
            throw AssertionError("expected a rejected buffer")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message != null)
        }
    }
}
