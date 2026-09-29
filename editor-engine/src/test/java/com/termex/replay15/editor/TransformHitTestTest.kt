package com.termex.replay15.editor

import com.termex.replay15.editor.preview.TransformHitTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformHitTestTest {
    @Test
    fun `rotated bounds are tested in object local coordinates`() {
        assertTrue(contains(rotation = 90f, x = 100f, y = 119f))
        assertFalse(contains(rotation = 90f, x = 119f, y = 100f))
    }

    @Test
    fun `small visual object retains a forty eight pixel touch target`() {
        assertTrue(
            TransformHitTest.contains(
                left = 98f, top = 98f, right = 102f, bottom = 102f,
                centerX = 100f, centerY = 100f, rotationDegrees = 0f,
                touchX = 122f, touchY = 100f, minimumSizePx = 48f,
            ),
        )
        assertFalse(
            TransformHitTest.contains(
                left = 98f, top = 98f, right = 102f, bottom = 102f,
                centerX = 100f, centerY = 100f, rotationDegrees = 0f,
                touchX = 125f, touchY = 100f, minimumSizePx = 48f,
            ),
        )
    }

    private fun contains(rotation: Float, x: Float, y: Float) = TransformHitTest.contains(
        left = 80f, top = 90f, right = 120f, bottom = 110f,
        centerX = 100f, centerY = 100f, rotationDegrees = rotation,
        touchX = x, touchY = y, minimumSizePx = 0f,
    )
}
