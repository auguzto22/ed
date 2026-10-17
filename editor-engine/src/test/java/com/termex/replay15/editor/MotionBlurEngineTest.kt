package com.termex.replay15.editor

import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.SpeedPoint
import com.termex.replay15.editor.domain.TransformKeyframe
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.motion.MotionBlurEngine
import com.termex.replay15.editor.speed.MotionBlurConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blur pela animação da camada (o método alternativo ao blur por vetores do motor). */
class MotionBlurEngineTest {

    private val on = MotionBlurConfig(enabled = true)

    private fun clip(vararg keyframes: TransformKeyframe) = clip(4 * SECOND, *keyframes)

    private fun clip(durationUs: Long, vararg keyframes: TransformKeyframe) = VideoClip(
        uri = "content://v/1", name = "v.mp4", sourceUs = durationUs, width = 1920, height = 1080, fps = 30f,
        keyframes = keyframes.toList(),
    )

    private fun travel(clip: VideoClip, config: MotionBlurConfig = on) =
        abs(MotionBlurEngine.layerEffect(clip, config, 30)!!.keyframes.getValue("dx").first().value)

    @Test
    fun `static clip produces no effect`() {
        assertNull(MotionBlurEngine.layerEffect(clip(TransformKeyframe(0L), TransformKeyframe(2 * SECOND)), on, 30))
    }

    @Test
    fun `panning clip blurs along the pan only`() {
        val effect = MotionBlurEngine.layerEffect(clip(TransformKeyframe(0L, x = 0f), TransformKeyframe(SECOND, x = .25f)), on, 30)
        assertNotNull(effect)
        assertEquals(MotionBlurEngine.ASSET_ID, effect!!.assetId)
        assertTrue(effect.keyframes.getValue("dx").first().value > 0f)
        assertTrue(effect.keyframes.getValue("dy").all { abs(it.value) < 1e-3f })
        // A intensidade não é aplicada duas vezes (o shader recebe amount = 1).
        assertEquals(1f, effect.values.getValue("amount"))
    }

    @Test
    fun `blur grows linearly with the shutter angle`() {
        val pan = clip(TransformKeyframe(0L, x = 0f), TransformKeyframe(SECOND, x = .05f))
        val narrow = travel(pan, on.copy(shutterAngle = 90f))
        val wide = travel(pan, on.copy(shutterAngle = 360f))
        assertEquals(4f, wide / narrow, .01f)
    }

    @Test
    fun `slowing the clip down shortens the blur`() {
        val pan = clip(TransformKeyframe(0L, x = 0f), TransformKeyframe(SECOND, x = .05f))
        val normal = travel(pan)
        val slow = travel(pan.copy(speed = .5f))
        val ramp = travel(pan.copy(speedCurve = listOf(SpeedPoint(0, .25f), SpeedPoint(4 * SECOND, .25f))))
        assertEquals(.5f, slow / normal, .01f)
        assertEquals(.25f, ramp / normal, .01f)
    }

    @Test
    fun `disabled or zero intensity blur is not produced`() {
        val pan = clip(TransformKeyframe(0L, x = 0f), TransformKeyframe(SECOND, x = .25f))
        assertNull(MotionBlurEngine.layerEffect(pan, MotionBlurConfig(enabled = false), 30))
        assertNull(MotionBlurEngine.layerEffect(pan, on.copy(intensity = 0f), 30))
    }

    @Test
    fun `result stays inside every EffectInstance contract`() {
        val many = (0..40).map { TransformKeyframe(it * SECOND / 5, x = it * .01f) }
        val effect = MotionBlurEngine.layerEffect(clip(12 * SECOND, *many.toTypedArray()), on.copy(shutterAngle = 360f, intensity = 2f), 24)
        assertNotNull(effect)
        val keys = effect!!.keyframes
        assertTrue(keys.values.sumOf { it.size } <= 400)
        keys.values.forEach { curve ->
            assertTrue(curve.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs })
            assertTrue(curve.all { it.value.isFinite() && abs(it.value) <= 200f })
        }
    }

    @Test
    fun `clip with a single keyframe has no path to blur`() {
        assertNull(MotionBlurEngine.layerEffect(clip(TransformKeyframe(0L, x = .3f)), on, 30))
    }

    @Test
    fun `slow movement is ignored by the speed threshold`() {
        val crawling = clip(20 * SECOND, TransformKeyframe(0L, x = 0f), TransformKeyframe(10 * SECOND, x = .001f))
        assertNull(MotionBlurEngine.layerEffect(crawling, on, 30))
    }

    private fun abs(value: Float) = if (value < 0f) -value else value
}
