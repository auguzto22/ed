package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import org.junit.Assert.*
import org.junit.Test

class EasingTest {
    @Test fun everyEasingHasExactEndpointsAndFiniteSamples() {
        Easing.entries.forEach { easing ->
            assertEquals(0f, easing.apply(0f), 0f); assertEquals(1f, easing.apply(1f), 0f)
            for (i in 0..1000) assertTrue(easing.apply(i / 1000f).isFinite())
        }
    }
    @Test fun bezierInvertsXInsteadOfUsingTimeAsCurveParameter() {
        val linear = CubicBezier(.15f, .15f, .7f, .7f)
        for (i in 0..100) assertEquals(i / 100f, linear.apply(i / 100f), .00001f)
        assertEquals(.8024f, CubicBezier().apply(.5f), .001f)
    }
    @Test fun physicalCurvesOvershootButStayWithinPropertyBounds() {
        listOf(Easing.SPRING, Easing.ELASTIC, Easing.BACK).forEach { easing ->
            assertTrue((0..100).any { easing.apply(it / 100f) > 1f })
            val clip = VideoClip(uri = "file://clip", name = "Test", sourceUs = SECOND, width = 100, height = 100,
                keyframes = listOf(TransformKeyframe(0, opacity = 0f, easing = easing),
                    TransformKeyframe(SECOND, zoom = 4f, x = .5f, y = -.5f, rotation = 180f)))
            for (i in 0..1000) {
                val key = clip.transformAt(i * 1000L)
                assertTrue(key.zoom in .25f..4f); assertTrue(key.opacity in 0f..1f)
            }
        }
    }
    @Test fun invalidBezierRejectedAndOldEnumOrdinalsPreserved() {
        assertEquals(Easing.HOLD, Easing.entries[4])
        assertThrows(IllegalArgumentException::class.java) { CubicBezier(x1 = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { CubicBezier(y2 = 4f) }
        assertEquals(0f, Easing.HOLD.apply(.99f), 0f)
    }
}
