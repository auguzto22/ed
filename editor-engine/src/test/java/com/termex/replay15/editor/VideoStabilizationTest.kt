package com.termex.replay15.editor

import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.stabilize.CameraMotion
import com.termex.replay15.editor.stabilize.LumaFrame
import com.termex.replay15.editor.stabilize.MotionEstimator
import com.termex.replay15.editor.stabilize.StabilizationPath
import com.termex.replay15.editor.stabilize.StabilizationProfile
import com.termex.replay15.editor.stabilize.VideoStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VideoStabilizationTest {

    private val size = 64

    /** Deterministic high-frequency pattern; block matching needs texture, not flat colour. */
    private fun pattern(): LumaFrame {
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            ((x * 37 + y * 17 + x * y) % 251)
        }
        return LumaFrame(size, size, pixels)
    }

    /** The same content displaced by (dx, dy) pixels, clamped at the borders. */
    private fun shifted(base: LumaFrame, dx: Int, dy: Int): LumaFrame {
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val sourceX = (x - dx).coerceIn(0, size - 1)
                val sourceY = (y - dy).coerceIn(0, size - 1)
                pixels[y * size + x] = base.pixels[sourceY * size + sourceX]
            }
        }
        return LumaFrame(size, size, pixels)
    }

    @Test
    fun `estimator recovers a known translation`() {
        val base = pattern()
        val motion = MotionEstimator(blockSize = 8, searchRadius = 6)
            .estimate(base, shifted(base, dx = 3, dy = -2), timeUs = SECOND)

        assertNotNull(motion)
        // The content slid right and down, so the camera panned left and up: the estimator
        // reports the negative of the content displacement.
        assertEquals(-3f / size, motion!!.dx, .02f)
        assertEquals(2f / size, motion.dy, .02f)
    }

    @Test
    fun `estimator reports no motion for two identical frames`() {
        val base = pattern()
        val motion = MotionEstimator().estimate(base, base, timeUs = 0L)
        assertNotNull(motion)
        assertEquals(0f, motion!!.dx, 1e-4f)
        assertEquals(0f, motion.dy, 1e-4f)
    }

    @Test
    fun `estimator rejects a scene cut`() {
        val base = pattern()
        // A flat frame shares no structure with the texture, so no block can match well.
        val cut = LumaFrame(size, size, IntArray(size * size) { 255 })
        assertNull(MotionEstimator().estimate(base, cut, 0L))
    }

    @Test
    fun `a real pan survives the cut guard`() {
        // The guard must not reject genuine motion, or stabilization would silently do nothing.
        val base = pattern()
        val motion = MotionEstimator().estimate(base, shifted(base, dx = 2, dy = 1), 0L)
        assertNotNull(motion)
    }

    @Test
    fun `deliberate pan is preserved`() {
        val motions = (0 until 20).map {
            CameraMotion(it * SECOND, .01f * it, 0f, 1f)
        }
        val corrections = StabilizationPath(StabilizationProfile.RECOMMENDED).correct(motions)
        // Raw and smoothed agree on a steady pan, so the correction stays near zero: the
        // user moved the camera on purpose and the video must not fight it.
        corrections.forEach {
            assertTrue("pan was cancelled: ${it.dx}", abs(it.dx) < .002f)
        }
    }

    @Test
    fun `shake is cancelled`() {
        val motions = (0 until 20).map { CameraMotion(it * SECOND, if (it % 2 == 0) .03f else -.03f, 0f, 1f) }
        val corrections = StabilizationPath(StabilizationProfile.RECOMMENDED).correct(motions)
        assertTrue("shake was not corrected", corrections.any { abs(it.dx) > .01f })
    }

    @Test
    fun `stronger profile smooths more than minimal`() {
        val motions = (0 until 40).map { CameraMotion(it * SECOND, if (it % 2 == 0) .04f else -.04f, 0f, 1f) }
        val minimal = StabilizationPath(StabilizationProfile.MINIMAL).correct(motions)
        val strong = StabilizationPath(StabilizationProfile.STRONG).correct(motions)
        val minimalEnergy = minimal.sumOf { (it.dx * it.dx).toDouble() }
        val strongEnergy = strong.sumOf { (it.dx * it.dx).toDouble() }
        assertTrue("strong profile left more motion ($strongEnergy vs $minimalEnergy)", strongEnergy < minimalEnergy)
    }

    @Test
    fun `auto crop zooms only when the correction needs it`() {
        val still = StabilizationPath(StabilizationProfile.RECOMMENDED).correct(
            (0 until 10).map { CameraMotion(it * SECOND, .01f * it, 0f, 1f) },
        )
        assertEquals(1f, still.maxOf { it.zoom }, 1e-4f)

        val shaken = StabilizationPath(StabilizationProfile.RECOMMENDED).correct(
            (0 until 20).map { CameraMotion(it * SECOND, if (it % 2 == 0) .03f else -.03f, 0f, 1f) },
        )
        assertTrue("no auto crop was applied", shaken.maxOf { it.zoom } > 1f)
    }

    @Test
    fun `keyframes respect the domain and the source duration`() {
        val clip = VideoClip(uri = "content://v/1", name = "v.mp4", sourceUs = 6 * SECOND, width = 1920, height = 1080)
        val stabilizer = VideoStabilizer(StabilizationProfile.RECOMMENDED)
        val corrections = (0 until 6).map {
            com.termex.replay15.editor.stabilize.StabilizationCorrection(it * SECOND, .02f, -.01f, 1.1f)
        }
        val keyframes = stabilizer.toKeyframes(clip, corrections)

        assertTrue(keyframes.isNotEmpty())
        assertTrue(keyframes.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs })
        keyframes.forEach {
            assertTrue(it.zoom in .25f..4f)
            assertTrue(it.x in -.5f.. .5f && it.y in -.5f.. .5f)
            assertTrue(it.sourceUs <= clip.sourceUs)
        }
    }

    @Test
    fun `empty input produces nothing rather than guessing`() {
        val stabilizer = VideoStabilizer()
        assertTrue(stabilizer.analyze(emptyList()).isEmpty())
        val clip = VideoClip(uri = "content://v/1", name = "v.mp4", sourceUs = SECOND, width = 16, height = 16)
        assertTrue(stabilizer.toKeyframes(clip, emptyList()).isEmpty())
    }
}
