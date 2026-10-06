package com.termex.replay15.editor

import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.SpeedPoint
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.motion.FrameInterpolator
import com.termex.replay15.editor.motion.SmoothSlowMoPlanner
import com.termex.replay15.editor.motion.SmoothSlowMoProfile
import com.termex.replay15.editor.stabilize.LumaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothSlowMoTest {

    private val width = 64
    private val height = 64

    private fun clip(speed: Float = .3f, fps: Float = 30f, curve: List<SpeedPoint> = emptyList()) = VideoClip(
        uri = "content://video/1", name = "Replay.mp4",
        sourceUs = 5 * SECOND, width = 1920, height = 1080, fps = fps, speed = speed, speedCurve = curve,
    )

    private fun pattern(): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        (x * 37 + y * 17 + x * y) % 251
    }

    /** The same content displaced by (dx, dy) pixels, clamped at the borders. */
    private fun shifted(base: IntArray, dx: Int, dy: Int): IntArray {
        val out = IntArray(base.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = base[(y - dy).coerceIn(0, height - 1) * width + (x - dx).coerceIn(0, width - 1)]
            }
        }
        return out
    }

    private fun luma(pixels: IntArray) = FrameInterpolator.luminanceOf(pixels, width, height)

    @Test
    fun `velocity is recovered from a known translation`() {
        val base = pattern()
        val velocity = FrameInterpolator.measureVelocity(luma(base), luma(shifted(base, 4, 2)))
        assertNotNull(velocity)
        // The content slid by (4, 2) pixels, which is what the warp has to undo.
        assertEquals(4f, velocity!!.first, .6f)
        assertEquals(2f, velocity.second, .6f)
    }

    @Test
    fun `the endpoints of the blend are the source frames`() {
        // Whatever the velocity, the first and last rendered frames must be untouched,
        // otherwise the derived file would drift against the original at its boundaries.
        val base = pattern()
        val next = shifted(base, 5, -3)
        val start = FrameInterpolator.interpolateArgb(base, next, width, height, 5f, -3f, 0f)
        val end = FrameInterpolator.interpolateArgb(base, next, width, height, 5f, -3f, 1f)
        assertEquals(0f, FrameInterpolator.meanError(LumaFrame(width, height, start), LumaFrame(width, height, base)), .01f)
        assertEquals(0f, FrameInterpolator.meanError(LumaFrame(width, height, end), LumaFrame(width, height, next)), .01f)
    }

    @Test
    fun `compensation beats a plain crossfade during motion`() {
        val base = pattern()
        val next = shifted(base, 6, 0)
        val velocity = FrameInterpolator.measureVelocity(luma(base), luma(next))!!
        // The true frame halfway through a steady pan is the content moved by three pixels.
        val truth = shifted(base, 3, 0)
        val reference = LumaFrame(width, height, truth)

        val crossfade = shifted(base, 0, 0).let {
            IntArray(it.size) { index -> it[index] }
        }
        val blended = IntArray(base.size) { index -> (base[index] + next[index]) / 2 }
        val compensated = FrameInterpolator.interpolateArgb(
            base, next, width, height, velocity.first, velocity.second, .5f,
        )

        val crossfadeError = FrameInterpolator.meanError(LumaFrame(width, height, crossfade), reference)
        val compensatedError = FrameInterpolator.meanError(LumaFrame(width, height, compensated), reference)
        val blindError = FrameInterpolator.meanError(LumaFrame(width, height, blended), reference)
        assertTrue("compensation ($compensatedError) did not beat crossfade ($blindError)",
            compensatedError < blindError)
        assertTrue(crossfadeError > compensatedError)
    }

    @Test
    fun `a still shot produces no usable motion`() {
        val base = pattern()
        val velocity = FrameInterpolator.measureVelocity(luma(base), luma(base))
        assertNotNull(velocity)
        assertEquals(0f, velocity!!.first, 1e-3f)
        assertEquals(0f, velocity.second, 1e-3f)
    }

    @Test
    fun `only a slowed clip is worth interpolating`() {
        assertNull(SmoothSlowMoPlanner.plan(clip(speed = 1f), SmoothSlowMoProfile.FLUID))
        assertNotNull(SmoothSlowMoPlanner.plan(clip(speed = .3f), SmoothSlowMoProfile.FLUID))
    }

    @Test
    fun `the slowest point of a ramp decides the output rate`() {
        val ramped = clip(speed = 1f, curve = listOf(SpeedPoint(0L, 1f), SpeedPoint(2 * SECOND, .25f), SpeedPoint(4 * SECOND, 1f)))
        assertEquals(.25f, SmoothSlowMoPlanner.slowestSpeed(ramped), 1e-4f)
        val plan = SmoothSlowMoPlanner.plan(ramped, SmoothSlowMoProfile.FLUID)
        assertNotNull(plan)
        // 30fps source at 0.25x needs 120fps, but the profile caps the work.
        assertEquals(60, plan!!.outputFps)
        assertTrue(plan.outputFps > ramped.fps)
    }

    @Test
    fun `an off profile never plans any work`() {
        assertNull(SmoothSlowMoPlanner.plan(clip(speed = .2f), SmoothSlowMoProfile.OFF))
    }

    @Test
    fun `images and very short clips are skipped`() {
        val still = clip(speed = .3f).copy(image = true, width = 1080, height = 1920)
        assertNull(SmoothSlowMoPlanner.plan(still, SmoothSlowMoProfile.FLUID))

        val tiny = clip(speed = .3f).copy(sourceUs = 100_000L, outUs = 100_000L)
        assertNull(SmoothSlowMoPlanner.plan(tiny, SmoothSlowMoProfile.FLUID))
    }

    @Test
    fun `the cache key changes whenever the output would change`() {
        val slow = SmoothSlowMoPlanner.plan(clip(speed = .3f), SmoothSlowMoProfile.FLUID)!!
        val faster = SmoothSlowMoPlanner.plan(clip(speed = .5f), SmoothSlowMoProfile.FLUID)!!
        val trimmed = SmoothSlowMoPlanner.plan(clip(speed = .3f).copy(inUs = SECOND), SmoothSlowMoProfile.FLUID)!!
        assertTrue(slow.cacheKey != faster.cacheKey)
        assertTrue(slow.cacheKey != trimmed.cacheKey)
    }

    @Test
    fun `frame count is rounded so a mild slowdown still interpolates`() {
        val moving = 6f to 4f
        // Truncating 1/0.9 gave 1 and silently reduced the feature to frame duplication for any
        // speed above 0.5x, which is most of the range the profile accepts.
        assertEquals(1, SmoothSlowMoPlanner.interpolatedFrameCount(null, .9f, 60))
        assertEquals(2, SmoothSlowMoPlanner.interpolatedFrameCount(moving, .5f, 60))
        assertEquals(4, SmoothSlowMoPlanner.interpolatedFrameCount(moving, .25f, 60))
        // 1/0.9 = 1.11 rounds to 1... and 1/0.6 = 1.67 rounds to 2.
        assertEquals(1, SmoothSlowMoPlanner.interpolatedFrameCount(moving, .9f, 60))
        assertEquals(2, SmoothSlowMoPlanner.interpolatedFrameCount(moving, .6f, 60))
    }

    @Test
    fun `a still shot or an unmeasurable frame is never interpolated`() {
        val still = .4f to -.3f
        assertEquals(1, SmoothSlowMoPlanner.interpolatedFrameCount(still, .25f, 90))
        assertEquals(1, SmoothSlowMoPlanner.interpolatedFrameCount(null, .25f, 90))
        // Never more frames than the output rate can carry.
        assertEquals(90, SmoothSlowMoPlanner.interpolatedFrameCount(20f to 20f, .01f, 90))
    }

    @Test
    fun `luminance reduction keeps the aspect ratio`() {
        val frame = FrameInterpolator.luminanceOf(pattern(), width, height, size = 16)
        assertEquals(16, frame.width)
        assertEquals(16, frame.height)
    }
}
