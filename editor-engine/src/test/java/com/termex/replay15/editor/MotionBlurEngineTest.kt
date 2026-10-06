package com.termex.replay15.editor

import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.TransformKeyframe
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.motion.MotionBlurEngine
import com.termex.replay15.editor.motion.MotionBlurSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionBlurEngineTest {

    private fun clip(vararg keyframes: TransformKeyframe) = clip(4 * SECOND, *keyframes)

    private fun clip(durationUs: Long, vararg keyframes: TransformKeyframe) = VideoClip(
        uri = "content://v/1",
        name = "v.mp4",
        sourceUs = durationUs,
        width = 1920,
        height = 1080,
        fps = 30f,
        keyframes = keyframes.toList(),
    )

    @Test
    fun `static clip produces no effect`() {
        val still = clip(
            TransformKeyframe(0L, x = 0f),
            TransformKeyframe(2 * SECOND, x = 0f),
        )
        assertNull(MotionBlurEngine.build(still, MotionBlurSettings(), "blur-1"))
    }

    @Test
    fun `panning clip produces a directional blur along the pan`() {
        val panning = clip(
            TransformKeyframe(0L, x = 0f),
            TransformKeyframe(SECOND, x = .25f),
        )
        val effect = MotionBlurEngine.build(panning, MotionBlurSettings(), "blur-1")
        assertNotNull(effect)
        assertEquals(MotionBlurEngine.ASSET_ID, effect!!.assetId)

        val dx = effect.keyframes.getValue("dx")
        val dy = effect.keyframes.getValue("dy")
        assertTrue("a rightward pan must blur rightward", dx.first().value > 0f)
        assertTrue("a horizontal pan must not blur vertically", dy.all { abs(it.value) < 1e-3f })
    }

    @Test
    fun `blur grows with shutter angle`() {
        val panning = clip(TransformKeyframe(0L, x = 0f), TransformKeyframe(SECOND, x = .25f))
        val tight = MotionBlurEngine.build(panning, MotionBlurSettings(shutterAngleDegrees = 45f), "a")!!
        val wide = MotionBlurEngine.build(panning, MotionBlurSettings(shutterAngleDegrees = 360f), "b")!!
        val tightTravel = abs(tight.keyframes.getValue("dx").first().value)
        val wideTravel = abs(wide.keyframes.getValue("dx").first().value)
        assertTrue("360 should travel 8x further than 45 ($wideTravel vs $tightTravel)", wideTravel > tightTravel * 4f)
    }

    @Test
    fun `shutter angle of zero disables the blur`() {
        val panning = clip(TransformKeyframe(0L, x = 0f), TransformKeyframe(SECOND, x = .25f))
        assertNull(MotionBlurEngine.build(panning, MotionBlurSettings(shutterAngleDegrees = 0f), "a"))
    }

    @Test
    fun `result stays inside every EffectInstance contract`() {
        // VideoClip rejects keyframes past sourceUs, so the clip has to be long enough to
        // hold the whole path.
        val many = (0..40).map { TransformKeyframe(it * SECOND / 5, x = it * .01f) }
        val effect = MotionBlurEngine.build(clip(12 * SECOND, *many.toTypedArray()), MotionBlurSettings(), "blur-1")
        assertNotNull(effect)
        val keys = effect!!.keyframes
        assertTrue(keys.size <= 32)
        assertTrue(keys.values.sumOf { it.size } <= 400)
        keys.values.forEach { curve ->
            assertTrue(curve.size <= 200)
            assertTrue(curve.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs })
            assertTrue(curve.all { it.value.isFinite() })
        }
    }

    @Test
    fun `clip with a single keyframe has no path to blur`() {
        val single = clip(TransformKeyframe(0L, x = .3f))
        assertNull(MotionBlurEngine.build(single, MotionBlurSettings(), "a"))
    }

    @Test
    fun `slow movement is ignored by the speed threshold`() {
        // 0.01 of the frame over 10s is under 12 px/s, so the layer counts as static.
        val crawling = clip(20 * SECOND, TransformKeyframe(0L, x = 0f), TransformKeyframe(10 * SECOND, x = .01f))
        assertNull(MotionBlurEngine.build(crawling, MotionBlurSettings(), "a"))
    }

    private fun abs(value: Float) = if (value < 0f) -value else value
}
