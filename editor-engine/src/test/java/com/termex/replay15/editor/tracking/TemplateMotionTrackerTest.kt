package com.termex.replay15.editor.tracking

import com.termex.replay15.editor.domain.TrackingPoint
import com.termex.replay15.editor.domain.TrackingTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TemplateMotionTrackerTest {

    @Test
    fun `tracking region validation succeeds for valid coordinates`() {
        val region = TrackingRegion(centerX = 0.5f, centerY = 0.5f, width = 0.25f, height = 0.25f)
        assertEquals(0.5f, region.centerX, 0.0001f)
        assertEquals(0.5f, region.centerY, 0.0001f)
        assertEquals(0.25f, region.width, 0.0001f)
        assertEquals(0.25f, region.height, 0.0001f)
    }

    @Test
    fun `tracking region throws on out-of-bounds inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackingRegion(centerX = -0.1f, centerY = 0.5f, width = 0.2f, height = 0.2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackingRegion(centerX = 0.5f, centerY = 1.2f, width = 0.2f, height = 0.2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackingRegion(centerX = 0.5f, centerY = 0.5f, width = 0.005f, height = 0.2f)
        }
    }

    @Test
    fun `template motion tracker parameter validation`() {
        assertNotNull(TemplateMotionTracker(templateSize = 24, searchFraction = 0.18f, maxPoints = 500))

        assertThrows(IllegalArgumentException::class.java) {
            TemplateMotionTracker(templateSize = 4) // < 8
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateMotionTracker(searchFraction = 0.01f) // < 0.02f
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateMotionTracker(maxPoints = 1) // < 2
        }
    }

    @Test
    fun `fails when frames are empty`() {
        val tracker = TemplateMotionTracker()
        assertThrows(IllegalArgumentException::class.java) {
            tracker.track(emptyList(), TrackingRegion(0.5f, 0.5f, 0.2f, 0.2f))
        }
    }

    @Test
    fun `tracking track evaluates position and nearest point`() {
        val points = listOf(
            TrackingPoint(timeUs = 0L, centerX = 0.2f, centerY = 0.3f, width = 0.1f, height = 0.1f, confidence = 1f),
            TrackingPoint(timeUs = 100_000L, centerX = 0.4f, centerY = 0.5f, width = 0.1f, height = 0.1f, confidence = 0.9f),
            TrackingPoint(timeUs = 200_000L, centerX = 0.6f, centerY = 0.7f, width = 0.1f, height = 0.1f, confidence = 0.8f),
        )
        val track = TrackingTrack(id = "track-test", points = points)

        // Exact points
        assertEquals(0.2f, track.at(0L).centerX, 0.001f)
        assertEquals(0.6f, track.at(200_000L).centerX, 0.001f)

        // Interpolation
        val mid = track.at(50_000L)
        assertEquals(0.3f, mid.centerX, 0.001f)
        assertEquals(0.4f, mid.centerY, 0.001f)

        // Nearest point
        assertEquals(0L, track.nearest(20_000L).timeUs)
        assertEquals(100_000L, track.nearest(80_000L).timeUs)
    }
}
