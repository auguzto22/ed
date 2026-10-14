package com.termex.replay15.editor

import com.termex.replay15.editor.animation.AnimationPoint
import com.termex.replay15.editor.animation.AnimationPreviewSampler
import com.termex.replay15.editor.domain.Easing
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationPreviewSamplerTest {
    private val slideIn = listOf(
        AnimationPoint(0f, x = -1f, opacity = 0f, easing = Easing.LINEAR),
        AnimationPoint(.5f, x = 0f, opacity = 1f, easing = Easing.HOLD),
        AnimationPoint(1f, x = 0f, zoom = 2f),
    )

    @Test
    fun matchesEndpointsAndInterpolatesWithStartEasing() {
        val start = AnimationPreviewSampler.sample(slideIn, 0f)
        assertEquals(-1f, start.x, 1e-6f)
        assertEquals(0f, start.opacity, 1e-6f)
        val quarter = AnimationPreviewSampler.sample(slideIn, .25f)
        assertEquals(-.5f, quarter.x, 1e-5f)
        assertEquals(.5f, quarter.opacity, 1e-5f)
        // Second segment uses HOLD from its starting point: zoom stays until the end.
        assertEquals(1f, AnimationPreviewSampler.sample(slideIn, .9f).zoom, 1e-6f)
        assertEquals(2f, AnimationPreviewSampler.sample(slideIn, 1f).zoom, 1e-6f)
    }

    @Test
    fun clampsProgressOutsideRange() {
        assertEquals(-1f, AnimationPreviewSampler.sample(slideIn, -3f).x, 1e-6f)
        assertEquals(2f, AnimationPreviewSampler.sample(slideIn, 7f).zoom, 1e-6f)
    }

    @Test
    fun oneShotHoldsFinalPoseThenRestarts() {
        assertEquals(.5f, AnimationPreviewSampler.progressAt(350, 700, loop = false), 1e-6f)
        assertEquals(1f, AnimationPreviewSampler.progressAt(1000, 700, loop = false, holdMs = 700), 1e-6f)
        assertEquals(0f, AnimationPreviewSampler.progressAt(1400, 700, loop = false, holdMs = 700), 1e-6f)
        assertEquals(.25f, AnimationPreviewSampler.progressAt(1750, 1400, loop = true), 1e-6f)
    }
}
