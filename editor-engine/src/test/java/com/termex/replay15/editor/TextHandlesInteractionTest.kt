package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.TransformHitTest
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.transform.RealtimeProjectState
import com.termex.replay15.editor.transform.TransformState
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.hypot

class TextHandlesInteractionTest {

    @Test
    fun `textClip preserves scale independently from fontSize`() {
        val initialClip = TextClip(
            text = "Recly CapCut Style",
            startUs = 0L,
            endUs = 5 * SECOND,
            size = 0.08f,
            scale = 1.0f,
        )

        // When scaled on preview, scale changes without touching tipographic size
        val scaledClip = initialClip.copy(scale = 1.75f)
        assertEquals(0.08f, scaledClip.size, 0.0001f)
        assertEquals(1.75f, scaledClip.scale, 0.0001f)
        assertEquals(1.75f, scaledClip.baseTransform.scaleX, 0.0001f)
        assertEquals(1.75f, scaledClip.baseTransform.scaleY, 0.0001f)
    }

    @Test
    fun `realtimeProjectState isolates gesture updates from immutable project`() {
        val clipId = "text_test_gesture_123"
        val baseState = TransformState(x = 0.5f, y = 0.5f, scaleX = 1f, scaleY = 1f, rotation = 0f)

        assertNull(RealtimeProjectState.transform(clipId))

        // During gesture
        val movingState = baseState.copy(x = 0.65f, y = 0.40f, scaleX = 1.5f, scaleY = 1.5f, rotation = 15f)
        RealtimeProjectState.updateTransform(clipId, movingState)

        val retrieved = RealtimeProjectState.transform(clipId)
        assertNotNull(retrieved)
        assertEquals(0.65f, retrieved!!.x, 0.0001f)
        assertEquals(1.5f, retrieved.scaleX, 0.0001f)
        assertEquals(15f, retrieved.rotation, 0.0001f)

        // On gesture end (commit or cancel)
        RealtimeProjectState.clear(clipId)
        assertNull(RealtimeProjectState.transform(clipId))
    }

    @Test
    fun `uniform scale ratio preserves aspect ratio and stays within safe bounds`() {
        val initialDist = 100f
        val currentDist = 250f
        val ratio = currentDist / initialDist
        val initialScale = 1.2f
        val newScale = (initialScale * ratio).coerceIn(0.05f, 10f)

        assertEquals(3.0f, newScale, 0.0001f)
        assertTrue(newScale.isFinite())
        assertTrue(newScale > 0f)
    }

    @Test
    fun `rotation angle delta accumulates properly without resetting to zero`() {
        val initialRotation = 35f
        val initialKnobAngle = 0.1f // rad
        val currentKnobAngle = 0.6f // rad
        val angleDiff = Math.toDegrees((currentKnobAngle - initialKnobAngle).toDouble()).toFloat()
        val finalRotation = ((initialRotation + angleDiff + 180f) % 360f + 360f) % 360f - 180f

        assertTrue(finalRotation > initialRotation)
        assertTrue(finalRotation in -180f..180f)
    }

    @Test
    fun `project serialization roundtrips text scale in schema 18`() {
        val clip = TextClip(
            id = "t_scale_test",
            text = "Hello World",
            startUs = 0L,
            endUs = 3 * SECOND,
            x = 0.45f,
            y = 0.60f,
            size = 0.07f,
            scale = 2.4f,
            rotation = -22f,
        )
        val project = Project(texts = listOf(clip))

        val output = ByteArrayOutputStream()
        ProjectCodec.write(project, output)

        val restored = ProjectCodec.read(ByteArrayInputStream(output.toByteArray()))
        val restoredClip = restored.texts.first()

        assertEquals(2.4f, restoredClip.scale, 0.0001f)
        assertEquals(0.07f, restoredClip.size, 0.0001f)
        assertEquals(-22f, restoredClip.rotation, 0.0001f)
        assertEquals(0.45f, restoredClip.x, 0.0001f)
        assertEquals(0.60f, restoredClip.y, 0.0001f)
    }
}
