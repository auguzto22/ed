package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.transform.TransformEvaluator
import com.termex.replay15.editor.transform.TransformKeyframe
import com.termex.replay15.editor.transform.TransformState
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class TransformKeyframeSystemTest {

    @Test
    fun testEmptyKeyframesReturnsBase() {
        val base = TransformState(x = 0.2f, y = 0.8f, scaleX = 1.5f, scaleY = 1.5f, rotation = 45f, opacity = 0.7f)
        val result = TransformEvaluator.evaluate(base, emptyList(), 1_000_000L)
        assertEquals(base, result)
    }

    @Test
    fun testSingleKeyframeReturnsKeyframeTransform() {
        val base = TransformState(x = 0.5f, y = 0.5f)
        val kf = TransformKeyframe(timeUs = 2_000_000L, transform = TransformState(x = 0.8f, y = 0.2f))
        val before = TransformEvaluator.evaluate(base, listOf(kf), 1_000_000L)
        val after = TransformEvaluator.evaluate(base, listOf(kf), 3_000_000L)
        assertEquals(0.8f, before.x, 0.001f)
        assertEquals(0.8f, after.x, 0.001f)
    }

    @Test
    fun testTwoKeyframesLinearInterpolation() {
        val base = TransformState()
        val k1 = TransformKeyframe(timeUs = 1_000_000L, transform = TransformState(x = 0.2f, y = 0.8f, scaleX = 1f, scaleY = 1f, rotation = 0f, opacity = 1f), easing = Easing.LINEAR)
        val k2 = TransformKeyframe(timeUs = 3_000_000L, transform = TransformState(x = 0.8f, y = 0.2f, scaleX = 2f, scaleY = 2f, rotation = 90f, opacity = 0.5f), easing = Easing.LINEAR)

        val mid = TransformEvaluator.evaluate(base, listOf(k1, k2), 2_000_000L)
        assertEquals(0.5f, mid.x, 0.001f)
        assertEquals(0.5f, mid.y, 0.001f)
        assertEquals(1.5f, mid.scaleX, 0.001f)
        assertEquals(1.5f, mid.scaleY, 0.001f)
        assertEquals(45f, mid.rotation, 0.001f)
        assertEquals(0.75f, mid.opacity, 0.001f)
    }

    @Test
    fun testLerpAngleShortestPath() {
        // From 350 to 10 degrees is a 20 degree turn across 0.
        // At midpoint t = 0.5, it should be 0 (or 360) degrees, NOT 180 degrees!
        val midAngle = TransformEvaluator.lerpAngle(350f, 10f, 0.5f)
        assertTrue("Expected angle near 0 or 360, but got $midAngle", abs(midAngle) < 1f || abs(midAngle - 360f) < 1f)

        // From 10 to 350 degrees
        val reverseAngle = TransformEvaluator.lerpAngle(10f, 350f, 0.5f)
        assertTrue("Expected angle near 0 or 360, but got $reverseAngle", abs(reverseAngle) < 1f || abs(reverseAngle - 360f) < 1f)
    }

    @Test
    fun testTextClipTransformAtExtension() {
        val clip = TextClip(
            text = "Title",
            startUs = 0L,
            endUs = 5 * SECOND,
            x = 0.1f,
            y = 0.1f,
            rotation = 0f,
            opacity = 1f,
            transformKeyframes = listOf(
                TransformKeyframe(timeUs = 1 * SECOND, transform = TransformState(x = 0.2f, y = 0.2f), easing = Easing.LINEAR),
                TransformKeyframe(timeUs = 3 * SECOND, transform = TransformState(x = 0.6f, y = 0.6f), easing = Easing.LINEAR),
            )
        )

        val at0 = clip.transformAt(0L)
        assertEquals(0.2f, at0.x, 0.001f) // clamped to first keyframe

        val at2 = clip.transformAt(2 * SECOND)
        assertEquals(0.4f, at2.x, 0.001f)
        assertEquals(0.4f, at2.y, 0.001f)

        val at4 = clip.transformAt(4 * SECOND)
        assertEquals(0.6f, at4.x, 0.001f) // clamped to last keyframe
    }

    @Test
    fun testTextKeyframesPersistenceRoundtrip() {
        val originalText = TextClip(
            text = "Animado",
            startUs = SECOND,
            endUs = 6 * SECOND,
            transformKeyframes = listOf(
                TransformKeyframe(timeUs = 2 * SECOND, transform = TransformState(x = 0.25f, y = 0.75f, scaleX = 1.2f, scaleY = 1.2f, rotation = -15f, opacity = 0.8f), easing = Easing.EASE_IN),
                TransformKeyframe(timeUs = 4 * SECOND, transform = TransformState(x = 0.75f, y = 0.25f, scaleX = 1.8f, scaleY = 1.8f, rotation = 45f, opacity = 1.0f), easing = Easing.SMOOTH),
            )
        )

        val project = Project(texts = listOf(originalText))
        val output = ByteArrayOutputStream()
        ProjectCodec.write(project, output)

        val input = ByteArrayInputStream(output.toByteArray())
        val restored = ProjectCodec.read(input)

        assertEquals(1, restored.texts.size)
        val restoredText = restored.texts[0]
        assertEquals("Animado", restoredText.text)
        assertEquals(2, restoredText.transformKeyframes.size)

        val k1 = restoredText.transformKeyframes[0]
        assertEquals(2 * SECOND, k1.timeUs)
        assertEquals(0.25f, k1.transform.x, 0.001f)
        assertEquals(0.75f, k1.transform.y, 0.001f)
        assertEquals(1.2f, k1.transform.scaleX, 0.001f)
        assertEquals(-15f, k1.transform.rotation, 0.001f)
        assertEquals(0.8f, k1.transform.opacity, 0.001f)
        assertEquals(Easing.EASE_IN, k1.easing)

        val k2 = restoredText.transformKeyframes[1]
        assertEquals(4 * SECOND, k2.timeUs)
        assertEquals(0.75f, k2.transform.x, 0.001f)
        assertEquals(45f, k2.transform.rotation, 0.001f)
        assertEquals(Easing.SMOOTH, k2.easing)
    }
}
