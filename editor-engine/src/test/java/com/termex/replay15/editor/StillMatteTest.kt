package com.termex.replay15.editor

import com.termex.replay15.editor.backgroundremoval.StillMatte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StillMatteTest {
    private val opaqueRed = 0xFFFF0000.toInt()

    @Test
    fun keepsSubjectAndClearsBackground() {
        // 8x1 image, mask says the right half is subject.
        val pixels = IntArray(8) { opaqueRed }
        val mask = floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
        StillMatte.applyAlpha(pixels, 8, 1, mask, 8, 1, threshold = 0.65f, feather = 0.08f)
        assertEquals(0, pixels[0] ushr 24)
        assertEquals(255, pixels[7] ushr 24)
        // Colour is untouched, only alpha changes.
        assertEquals(0xFF0000, pixels[0] and 0x00FFFFFF)
    }

    @Test
    fun preservesSourceTransparency() {
        val pixels = intArrayOf(0x00FF0000, opaqueRed)
        StillMatte.applyAlpha(pixels, 2, 1, floatArrayOf(1f, 1f), 2, 1, threshold = 0.5f, feather = 0.1f)
        assertEquals(0, pixels[0] ushr 24)
        assertEquals(255, pixels[1] ushr 24)
    }

    @Test
    fun upsamplesSmallMaskSmoothly() {
        // A 2x1 mask stretched over 16 px must ramp instead of jumping in one step.
        val pixels = IntArray(16) { opaqueRed }
        StillMatte.applyAlpha(pixels, 16, 1, floatArrayOf(0f, 1f), 2, 1, threshold = 0.5f, feather = 0.3f)
        val alphas = pixels.map { it ushr 24 }
        assertEquals(0, alphas.first())
        assertEquals(255, alphas.last())
        assertTrue(alphas.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(alphas.any { it in 1..254 })
    }
}
