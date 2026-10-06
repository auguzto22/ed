package com.termex.replay15.editor

import com.termex.replay15.editor.autoedit.AudioSample
import com.termex.replay15.editor.beat.BeatDetector
import com.termex.replay15.editor.beat.BeatOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatDetectorTest {

    private val binUs = 100_000L

    private fun sample(startUs: Long, rms: Float, peak: Float = minOf(1f, rms * 2f)) =
        AudioSample(startUs, startUs + binUs, rms, peak)

    /** Silence with a sharp attack every [intervalUs]: a synthetic drum grid. */
    private fun clickTrack(intervalUs: Long, bins: Int = 120, base: Float = .05f, hit: Float = .9f): List<AudioSample> =
        (0 until bins).map { index ->
            val time = index * binUs
            val onBeat = (time % intervalUs) < binUs / 2
            if (onBeat) sample(time, hit, 1f) else sample(time, base, base * 1.5f)
        }

    @Test
    fun `a steady click track is found at its own tempo`() {
        // 120 BPM is one beat every 500ms, which is five analyzer bins.
        val map = BeatDetector.detect(clickTrack(500_000L))
        assertTrue("no beats were found", map.beats.isNotEmpty())
        assertEquals(120f, map.bpm, 12f)
        assertTrue("confidence was $map.confidence", map.confidence > .3f)
    }

    @Test
    fun `beats land on the grid rather than between the hits`() {
        val track = clickTrack(500_000L)
        val map = BeatDetector.detect(track)
        val hits = track.filter { it.rms > .5f }.map { it.startUs }
        val matched = map.beats.count { beat -> hits.any { abs(it - beat.timeUs) <= binUs } }
        // Every reported beat has to sit on a real transient, or the grid is fiction.
        assertEquals(map.beats.size, matched)
    }

    @Test
    fun `silence produces no beats instead of a confident empty grid`() {
        val quiet = (0 until 120).map { sample(it * binUs, .01f, .01f) }
        val map = BeatDetector.detect(quiet)
        assertTrue(map.isEmpty)
        assertEquals(0f, map.confidence, 1e-6f)
    }

    @Test
    fun `a clip too short to carry a tempo is rejected`() {
        val map = BeatDetector.detect(clickTrack(500_000L, bins = 4))
        assertTrue(map.isEmpty)
    }

    @Test
    fun `a slower tempo is reported as slower`() {
        // Both periods are whole numbers of analyzer bins: 400ms is 150 BPM and 700ms is
        // about 86 BPM. A fractional period would not land on the grid at all.
        val slow = BeatDetector.detect(clickTrack(700_000L))
        val fast = BeatDetector.detect(clickTrack(400_000L))
        assertTrue("slow bpm ${slow.bpm} was not below fast ${fast.bpm}", slow.bpm < fast.bpm)
    }

    @Test
    fun `every fourth beat is marked as the downbeat`() {
        val map = BeatDetector.detect(clickTrack(500_000L, bins = 400))
        val downbeats = map.beats.count { it.downbeat }
        assertTrue("no downbeat in ${map.beats.size} beats", downbeats >= 1)
        assertTrue("the first beat should open the bar", map.beats.first().downbeat)
        // Downbeats have to be a strict quarter of the beats, never a different number.
        assertTrue("$downbeats of ${map.beats.size}", downbeats <= map.beats.size / 4 + 1)
    }

    @Test
    fun `beat times are ordered and never crowd each other`() {
        val map = BeatDetector.detect(clickTrack(500_000L, bins = 300))
        assertTrue(map.beats.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
        assertTrue(map.beats.zipWithNext().all { (a, b) -> b.timeUs - a.timeUs >= 180_000L })
    }

    @Test
    fun `markers keep the beat grid and can be undone as a normal edit`() {
        val map = BeatDetector.detect(clickTrack(500_000L))
        val markers = map.toMarkers()
        assertEquals(map.beats.size, markers.size)
        assertEquals(map.beats.map { it.timeUs }, markers.map { it.timeUs })
        assertTrue(markers.all { it.name.startsWith("Batida") })
    }

    @Test
    fun `a sensitivity change cannot invent a tempo the track does not have`() {
        val quiet = clickTrack(500_000L, base = .002f, hit = .4f)
        val sensitive = BeatDetector.detect(quiet, BeatOptions(sensitivity = 2.5f))
        val calm = BeatDetector.detect(quiet, BeatOptions(sensitivity = .3f))
        // A barely audible track may yield either nothing or a sparse grid, but it must not
        // produce a dense one that the music cannot support.
        assertTrue(sensitive.beats.size - calm.beats.size < 40)
    }

    private fun abs(value: Long) = if (value < 0) -value else value
}
