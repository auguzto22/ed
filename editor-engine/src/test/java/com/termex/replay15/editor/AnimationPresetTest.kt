package com.termex.replay15.editor

import com.termex.replay15.editor.animation.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class AnimationPresetTest {
    private fun clip() = VideoClip(uri = "file:///video", name = "Video", sourceUs = 8 * SECOND, width = 1280, height = 720,
        inUs = SECOND, outUs = 7 * SECOND, speedCurve = listOf(SpeedPoint(0, .5f), SpeedPoint(8 * SECOND, 3f)))
    private fun preset(category: AnimationCategory, points: List<AnimationPoint>) = AnimationPreset("test", 1, "Test", category, points, "Test license")

    @Test fun entranceUsesEditedTimeAndKeepsOriginalMedia() {
        val p = preset(AnimationCategory.IN, listOf(AnimationPoint(0f, opacity = 0f, zoom = .5f, easing = Easing.SPRING), AnimationPoint(1f)))
        val c = clip(); val edited = p.applyTo(c, SECOND)
        assertEquals(c.uri, edited.uri); assertEquals(c.speedCurve, edited.speedCurve)
        assertEquals(c.timeMap.sourceAt(SECOND), edited.keyframes.last().sourceUs)
        assertEquals(0f, edited.transformAt(c.inUs).opacity, 0f)
        assertEquals(1f, edited.transformAt(c.outUs).opacity, 0f)
        assertEquals(Easing.SPRING, edited.keyframes.first().easing)
    }
    @Test fun exitDoesNotAnimateBeforeItsWindow() {
        val p = preset(AnimationCategory.OUT, listOf(AnimationPoint(0f), AnimationPoint(1f, opacity = 0f)))
        val c = clip(); val edited = p.applyTo(c, SECOND)
        assertEquals(1f, edited.transformAt(c.timeMap.sourceAt(100_000)).opacity, 0f)
        assertEquals(0f, edited.transformAt(c.outUs).opacity, 0f)
    }
    @Test fun repeatedPresetsReturnToBaseAndRejectExcessiveKeys() {
        val p = preset(AnimationCategory.LOOP, listOf(AnimationPoint(0f), AnimationPoint(.5f, zoom = 1.1f), AnimationPoint(1f)))
        val c = clip(); val edited = p.applyTo(c, SECOND)
        assertEquals(c.inUs, edited.keyframes.first().sourceUs)
        assertEquals(c.outUs, edited.keyframes.last().sourceUs)
        assertEquals(1f, edited.keyframes.last().zoom, 0f)
        val long = c.copy(sourceUs = 1000 * SECOND, outUs = 1000 * SECOND, speedCurve = emptyList())
        assertThrows(IllegalArgumentException::class.java) { p.applyTo(long, SECOND) }
    }
    @Test fun applyingAnotherEntranceDoesNotMultiplyZeroOpacity() {
        val p = preset(AnimationCategory.IN, listOf(AnimationPoint(0f, opacity = 0f), AnimationPoint(1f)))
        val c = clip(); val once = p.applyTo(c, SECOND); val twice = p.applyTo(once, SECOND)
        assertEquals(once, twice)
        assertEquals(1f, twice.transformAt(c.outUs).opacity, 0f)
    }
    @Test fun presetsAreSelfContainedAfterSavingWithoutAssetPack() {
        val p = preset(AnimationCategory.IN, listOf(AnimationPoint(0f, x = -.3f, easing = Easing.BEZIER,
            bezier = CubicBezier(.1f, -.2f, .5f, 1.5f)), AnimationPoint(1f)))
        val project = Project(videos = listOf(p.applyTo(clip(), SECOND)))
        val output = ByteArrayOutputStream(); ProjectCodec.write(project, output)
        assertEquals(project, ProjectCodec.read(output.toByteArray().inputStream()))
    }
    @Test fun malformedPresetsCannotConsumeUnboundedMemory() {
        assertThrows(IllegalArgumentException::class.java) { AnimationPoint(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { preset(AnimationCategory.IN, listOf(AnimationPoint(.5f), AnimationPoint(1f))) }
        assertThrows(IllegalArgumentException::class.java) { preset(AnimationCategory.LOOP, listOf(AnimationPoint(0f), AnimationPoint(1f, zoom = 2f))) }
        assertThrows(IllegalArgumentException::class.java) { preset(AnimationCategory.IN, List(33) { AnimationPoint(it / 32f) }) }
    }
}
