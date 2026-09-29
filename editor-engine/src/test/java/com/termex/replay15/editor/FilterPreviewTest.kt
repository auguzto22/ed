package com.termex.replay15.editor

import android.graphics.Bitmap
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.VideoFilter
import com.termex.replay15.editor.preview.FilterThumbnailCache
import com.termex.replay15.editor.preview.FilterThumbnailCacheKey
import com.termex.replay15.editor.render.FilterColorMath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FilterPreviewTest {

    private fun sampleClip(
        id: String = "clip_1",
        uri: String = "file:///test.mp4",
        sourceUs: Long = 10_000_000L,
        rotation: Int = 0,
        filter: Int = 0,
    ) = VideoClip(
        id = id,
        uri = uri,
        name = "Test Clip",
        sourceUs = sourceUs,
        width = 1920,
        height = 1080,
        rotation = rotation,
        filter = filter,
    )

    @Test
    fun testOriginalFilterPreservesColorsExactly() {
        val r = 0.42f
        val g = 0.73f
        val b = 0.15f
        val filtered = FilterColorMath.applyFilter(r, g, b, VideoFilter.ORIGINAL, 1.0f)
        assertEquals(r, filtered[0], 0.0001f)
        assertEquals(g, filtered[1], 0.0001f)
        assertEquals(b, filtered[2], 0.0001f)
    }

    @Test
    fun testZeroStrengthPreservesColors() {
        val r = 0.5f
        val g = 0.6f
        val b = 0.7f
        val filtered = FilterColorMath.applyFilter(r, g, b, VideoFilter.CINEMA, 0.0f)
        assertEquals(r, filtered[0], 0.0001f)
        assertEquals(g, filtered[1], 0.0001f)
        assertEquals(b, filtered[2], 0.0001f)
    }

    @Test
    fun testMonoFilterProducesGrayscaleWithBt709Luma() {
        val r = 0.8f
        val g = 0.2f
        val b = 0.5f
        val expectedLuma = r * 0.2126f + g * 0.7152f + b * 0.0722f
        val filtered = FilterColorMath.applyFilter(r, g, b, VideoFilter.MONO, 1.0f)

        // For MONO, all 3 channels must be equal to grayscale luma
        assertEquals(filtered[0], filtered[1], 0.001f)
        assertEquals(filtered[1], filtered[2], 0.001f)
        assertEquals(expectedLuma, filtered[0], 0.01f)
    }

    @Test
    fun testNegativeFilterInvertsColors() {
        val r = 0.2f
        val g = 0.7f
        val b = 0.9f
        val filtered = FilterColorMath.applyFilter(r, g, b, VideoFilter.NEGATIVE, 1.0f)
        assertEquals(0.8f, filtered[0], 0.001f)
        assertEquals(0.3f, filtered[1], 0.001f)
        assertEquals(0.1f, filtered[2], 0.001f)
    }

    @Test
    fun testCacheKeyDifferentiation() {
        val clipA = sampleClip(id = "clip_a", uri = "file:///a.mp4")
        val clipB = sampleClip(id = "clip_b", uri = "file:///b.mp4")

        val keyA = FilterThumbnailCache.makeKey(clipA, 1_000_000L, VideoFilter.CINEMA)
        val keyB = FilterThumbnailCache.makeKey(clipB, 1_000_000L, VideoFilter.CINEMA)
        val keyAFilterDiff = FilterThumbnailCache.makeKey(clipA, 1_000_000L, VideoFilter.WARM)
        val keyATimeDiff = FilterThumbnailCache.makeKey(clipA, 2_000_000L, VideoFilter.CINEMA)

        assertNotEquals(keyA, keyB)
        assertNotEquals(keyA, keyAFilterDiff)
        assertNotEquals(keyA, keyATimeDiff)
    }

    @Test
    fun testCacheKeyTimestampBucketing() {
        val clip = sampleClip()
        // Timestamps within the same 250ms bucket should produce the exact same key
        val key1 = FilterThumbnailCache.makeKey(clip, 100_000L, VideoFilter.VIBRANT) // 0.1s -> bucket 0
        val key2 = FilterThumbnailCache.makeKey(clip, 200_000L, VideoFilter.VIBRANT) // 0.2s -> bucket 0
        val key3 = FilterThumbnailCache.makeKey(clip, 600_000L, VideoFilter.VIBRANT) // 0.6s -> bucket 500_000

        assertEquals(key1, key2)
        assertNotEquals(key1, key3)
    }

    @Test
    fun testStaleSessionRejectionLogic() {
        // Simulates two concurrent sessions
        var activeSessionId = "session_A"
        var activeClipId = "clip_1"

        var publishedCount = 0
        val publishCallback: (String, String) -> Unit = { session, clip ->
            if (session == activeSessionId && clip == activeClipId) {
                publishedCount++
            }
        }

        // Clip 1 generation completes while session A is active
        publishCallback("session_A", "clip_1")
        assertEquals(1, publishedCount)

        // User switches to Clip 2 (Session B starts)
        activeSessionId = "session_B"
        activeClipId = "clip_2"

        // Delayed result from old session A finishes
        publishCallback("session_A", "clip_1")
        // Must be rejected!
        assertEquals(1, publishedCount)

        // Session B finishes
        publishCallback("session_B", "clip_2")
        assertEquals(2, publishedCount)
    }

    @Test
    fun testAllPresetDefinitionsHaveFiniteParameters() {
        for (filter in VideoFilter.entries) {
            val params = FilterColorMath.parametersFor(filter)
            assertTrue("brightness finite for $filter", params.brightness.isFinite())
            assertTrue("contrast finite for $filter", params.contrast.isFinite())
            assertTrue("saturation finite for $filter", params.saturation.isFinite())
            assertTrue("lightness finite for $filter", params.lightness.isFinite())
            assertTrue("hueRadians finite for $filter", params.hueRadians.isFinite())
            assertTrue("red finite for $filter", params.red.isFinite())
            assertTrue("green finite for $filter", params.green.isFinite())
            assertTrue("blue finite for $filter", params.blue.isFinite())
            assertTrue("mode finite for $filter", params.mode.isFinite())
        }
    }

    @Test
    fun testColorOutputRemainsWithinNormalizedBounds() {
        for (filter in VideoFilter.entries) {
            val out = FilterColorMath.applyFilter(0.5f, 0.5f, 0.5f, filter, 1.0f)
            assertTrue("R in 0..1 for $filter: ${out[0]}", out[0] in 0f..1f)
            assertTrue("G in 0..1 for $filter: ${out[1]}", out[1] in 0f..1f)
            assertTrue("B in 0..1 for $filter: ${out[2]}", out[2] in 0f..1f)
        }
    }
}
