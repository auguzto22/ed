package com.termex.replay15.editor

import com.termex.replay15.editor.detection.DetectionObservation
import com.termex.replay15.editor.detection.normalizedObservation
import com.termex.replay15.editor.domain.TrackingTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DetectionNormalizationTest {

    @Test
    fun `pixel box becomes a centered normalized observation`() {
        val observation = normalizedObservation(
            timeUs = 1_000L,
            left = 200f, top = 100f, right = 600f, bottom = 300f,
            imageWidth = 1000, imageHeight = 1000,
            confidence = .8f,
        )
        assertNotNull(observation)
        assertEquals(.4f, observation!!.centerX, 1e-4f)
        assertEquals(.2f, observation.centerY, 1e-4f)
        assertEquals(.4f, observation.width, 1e-4f)
        assertEquals(.2f, observation.height, 1e-4f)
        assertEquals(.8f, observation.confidence, 1e-4f)
    }

    @Test
    fun `box is clamped to the frame it came from`() {
        // ML Kit may report a box that leaves the image; the normalized result must not
        // escape 0..1 or the mask would sample outside the texture.
        val observation = normalizedObservation(
            timeUs = 0L,
            left = -50f, top = -50f, right = 1200f, bottom = 1200f,
            imageWidth = 1000, imageHeight = 1000,
            confidence = 1f,
        )
        assertNotNull(observation)
        // The box now covers the whole frame, so its centre is the frame centre.
        assertEquals(.5f, observation!!.centerX, 1e-4f)
        assertEquals(.5f, observation.centerY, 1e-4f)
        assertEquals(1f, observation.width, 1e-4f)
        assertEquals(1f, observation.height, 1e-4f)
    }

    @Test
    fun `normalization is independent of the reported image size`() {
        val small = normalizedObservation(0L, 50f, 50f, 150f, 150f, 200, 200, 1f)!!
        val large = normalizedObservation(0L, 500f, 500f, 1500f, 1500f, 2000, 2000, 1f)!!
        assertEquals(small.centerX, large.centerX, 1e-4f)
        assertEquals(small.centerY, large.centerY, 1e-4f)
        assertEquals(small.width, large.width, 1e-4f)
        assertEquals(small.height, large.height, 1e-4f)
    }

    @Test
    fun `degenerate box is rejected rather than clamped to a dot`() {
        assertNull(normalizedObservation(0L, 100f, 100f, 100f, 100f, 1000, 1000, 1f))
        assertNull(normalizedObservation(0L, 0f, 0f, 10f, 10f, 0, 1000, 1f))
    }

    @Test
    fun `observation bridges into the existing tracker without loss`() {
        val observation = DetectionObservation(500L, .5f, .5f, .3f, .3f, .9f)
        val track = TrackingTrack("face-main", listOf(observation.toTrackingPoint()))
        // This is the property the rest of the editor relies on: a detected face drives the
        // very same TrackingTrack a template tracker produces, so masks need no new plumbing.
        assertEquals(.5f, track.at(500L).centerX, 1e-4f)
        assertEquals(.3f, track.at(500L).width, 1e-4f)
        assertEquals(.9f, track.at(500L).confidence, 1e-4f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `observation outside the normalized range is rejected`() {
        DetectionObservation(0L, 1.4f, .5f, .3f, .3f, .9f)
    }
}
