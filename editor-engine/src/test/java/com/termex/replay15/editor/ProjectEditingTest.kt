package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.history.ProjectHistory
import org.junit.Assert.*
import org.junit.Test

class ProjectEditingTest {
    private fun video(length: Long = 15 * SECOND) = VideoClip(uri = "content://media/external/video/media/1", name = "Replay.mp4",
        sourceUs = length, width = 1920, height = 1080)

    @Test fun splitMapsGlobalCursorIntoTrimmedFastSourceWithoutLosingTime() {
        val first = video(4 * SECOND)
        val fast = video(20 * SECOND).copy(inUs = 2 * SECOND, outUs = 18 * SECOND, speed = 2f)
        val p = Project(videos = listOf(first, fast))
        val split = p.split(1, 7 * SECOND)
        assertEquals(3, split.videos.size)
        assertEquals(8 * SECOND, split.videos[1].outUs)
        assertEquals(8 * SECOND, split.videos[2].inUs)
        assertEquals(p.durationUs, split.durationUs)
        assertEquals(fast.id, split.videos[1].id)
        assertNotEquals(fast.id, split.videos[2].id)
        assertEquals(fast.uri, split.videos[2].uri)
    }
    @Test fun splitRejectsEdgesOutsideClipAndInvalidSelection() {
        val p = Project(videos = listOf(video()))
        for (time in listOf(-1L, 0L, MIN_CLIP - 1, p.durationUs - MIN_CLIP + 1, p.durationUs, p.durationUs + SECOND))
            assertSame(p, p.split(0, time))
        assertSame(p, p.split(-1, SECOND))
    }
    @Test fun splitAtAlwaysUsesClipUnderGlobalCursorAfterPreviousSplits() {
        val original = Project(videos = listOf(video(15 * SECOND)))
        val first = original.splitAt(5 * SECOND)
        val second = first.splitAt(10 * SECOND)
        val third = second.splitAt(2 * SECOND)
        assertEquals(listOf(2L, 3L, 5L, 5L), third.videos.map { it.durationUs / SECOND })
        assertEquals(original.durationUs, third.durationUs)
    }
    @Test fun splitKeepsKeyframesScopedToEachCutSegment() {
        val firstKey = TransformKeyframe(SECOND, zoom = 1f, x = 0f, easing = Easing.LINEAR)
        val lastKey = TransformKeyframe(7 * SECOND, zoom = 3f, x = .3f, easing = Easing.LINEAR)
        val original = Project(videos = listOf(video(10 * SECOND).copy(keyframes = listOf(firstKey, lastKey))))

        val split = original.splitAt(5 * SECOND)

        assertEquals(2, split.videos.size)
        assertEquals(listOf(SECOND, 5 * SECOND), split.videos[0].keyframes.map { it.sourceUs })
        assertEquals(listOf(5 * SECOND, 7 * SECOND), split.videos[1].keyframes.map { it.sourceUs })
        assertEquals(5 * SECOND, split.videos[0].outUs)
        assertEquals(5 * SECOND, split.videos[1].inUs)
        assertEquals(2.3333333f, split.videos[0].keyframes.last().zoom, .0001f)
        assertEquals(split.videos[0].keyframes.last(), split.videos[1].keyframes.first())
    }
    @Test fun speedAndTrimHaveMicrosecondPrecision() {
        val clip = video().copy(inUs = 123_456, outUs = 9_876_543, speed = .25f)
        assertEquals(39_012_348L, clip.durationUs)
        val p = Project(videos = listOf(clip))
        assertEquals(p.durationUs, p.split(0, 1_234_568).durationUs)
    }
    @Test fun clipBoundaryBelongsToNextClipAndEndToLast() {
        val p = Project(videos = listOf(video(SECOND), video(2 * SECOND), video(3 * SECOND)))
        assertEquals(0, p.indexAt(SECOND - 1)); assertEquals(1, p.indexAt(SECOND))
        assertEquals(2, p.indexAt(3 * SECOND)); assertEquals(2, p.indexAt(p.durationUs))
        assertEquals(3 * SECOND, p.startOf(2)); assertEquals(-1, Project().indexAt(0))
    }
    @Test fun reorderDoesNotMutatePreviousSnapshotOrAuxiliaryTracks() {
        val first = video(); val second = video()
        val text = TextClip(text = "Goal!", startUs = SECOND, endUs = 2 * SECOND)
        val p = Project(videos = listOf(first, second), texts = listOf(text))
        val moved = p.moveVideo(0, 1)
        assertEquals(listOf(second, first), moved.videos)
        assertEquals(listOf(first, second), p.videos)
        assertEquals(p.texts, moved.texts)
        assertEquals(p.durationUs, moved.durationUs)
    }
    @Test fun allEditsUndoAndRedoAsWholeTransactions() {
        val p = Project(videos = listOf(video()))
        val history = ProjectHistory(p)
        val split = p.split(0, 5 * SECOND)
        history.apply(split)
        val edit = split.changeVideo(1) { it.copy(speed = 2f, volume = .5f, crop = CropRect(.1f, .1f, .9f, .9f)) }
        history.apply(edit)
        history.undo(); assertEquals(split, history.current)
        history.undo(); assertEquals(p, history.current); assertFalse(history.canUndo)
        history.redo(); history.redo(); assertEquals(edit, history.current); assertFalse(history.canRedo)
    }
    @Test fun newEditAfterUndoDropsRedoButNoopDoesNot() {
        val p = Project(videos = listOf(video()))
        val history = ProjectHistory(p)
        history.apply(p.copy(name = "A")); history.undo()
        assertFalse(history.apply(p)); assertTrue(history.canRedo)
        history.apply(p.copy(name = "B")); assertFalse(history.canRedo)
    }
    @Test fun historyMemoryIsBounded() {
        val history = ProjectHistory(Project(), 3)
        for (i in 1..20) history.apply(history.current.copy(name = "Edit $i"))
        repeat(10) { history.undo() }
        assertEquals("Edit 17", history.current.name)
    }
    @Test fun outputMatchesPortraitCropRotationAndEvenCodecDimensions() {
        val p = Project(videos = listOf(video()))
        assertEquals(1280 to 720, p.dimensions())
        assertEquals(720 to 1280, p.copy(aspect = 9f / 16f).dimensions())
        assertEquals(720 to 1280, p.changeVideo(0) { it.copy(rotation = 90) }.dimensions())
        assertEquals(720 to 810, p.changeVideo(0) { it.copy(crop = CropRect(0f, 0f, .5f, 1f)) }.dimensions())
    }
    @Test fun exportEstimateIncludesAudioAndUsesDecimalBytes() {
        val export = ExportSettings(bitrate = 8_000_000)
        assertEquals(15_360_000L, export.estimatedBytes(15 * SECOND))
    }
    @Test fun newProjectQualityMatchesSourceResolutionAndCadence() {
        assertEquals(ExportSettings(1080, 60, 28_800_000), ExportSettings.forSource(video().copy(fps = 59.94f)))
        assertEquals(ExportSettings(1080, 120, 54_400_000), ExportSettings.forSource(video().copy(fps = 119.88f)))
        assertEquals(ExportSettings(720, 30, 8_000_000), ExportSettings.forSource(video().copy(width = 1280, height = 720, fps = 29.97f)))
        assertEquals(ExportSettings(480, 24, 4_000_000), ExportSettings.forSource(video().copy(width = 640, height = 360, fps = 20f)))
    }
    @Test fun invalidInputsCannotCreateNegativeOrNonFiniteTimeline() {
        assertThrows(IllegalArgumentException::class.java) { video().copy(speed = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { video().copy(inUs = 5 * SECOND, outUs = 4 * SECOND) }
        assertThrows(IllegalArgumentException::class.java) { CropRect(.9f, 0f, .2f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { ExportSettings(fps = 0) }
        assertThrows(IllegalArgumentException::class.java) { TextClip(text = "", startUs = 0, endUs = SECOND) }
        assertThrows(IllegalArgumentException::class.java) { video().copy(contrast = 1f) }
        assertThrows(IllegalArgumentException::class.java) { video().copy(saturation = 101f) }
        assertThrows(IllegalArgumentException::class.java) { video().copy(filter = VideoFilter.entries.size) }
        assertThrows(IllegalArgumentException::class.java) { video().copy(filterStrength = 1.1f) }
        assertThrows(IllegalArgumentException::class.java) { video().copy(zoom = 4.1f) }
        assertThrows(IllegalArgumentException::class.java) { video().copy(blur = 26f) }
        assertThrows(IllegalArgumentException::class.java) { video().copy(transitionDurationUs = 99_999) }
        assertThrows(IllegalArgumentException::class.java) { AudioClip(uri = "a", name = "a", sourceUs = SECOND, fadeInUs = 11 * SECOND) }
        assertThrows(IllegalArgumentException::class.java) {
            StickerClip(uri = "image", name = "bad", startUs = 0, endUs = SECOND, opacity = 0f)
        }
        val track = AudioClip(uri = "content://audio", name = "Track", sourceUs = SECOND)
        assertThrows(IllegalArgumentException::class.java) { Project(audio = List(9) { track.copy(id = newId()) }) }
    }

    @Test fun withTransitionClampsDurationAgainstHandleAndNeighboringTransitions() {
        val v1 = video(3 * SECOND).copy(id = "v1")
        val v2 = video(2 * SECOND).copy(id = "v2")
        val v3 = video(3 * SECOND).copy(id = "v3")
        val p = Project(videos = listOf(v1, v2, v3))

        // v2 is 2 seconds. Add a 1.2s transition between v1 and v2.
        val t1 = com.termex.replay15.editor.assets.TransitionInstance(
            id = "t1",
            transitionId = "cross_dissolve",
            leftClipId = "v1",
            rightClipId = "v2",
            durationUs = 1_200_000L,
        )
        val pWithT1 = p.withTransition(t1)
        assertEquals(1_200_000L, pWithT1.transitions.single().durationUs)

        // Now add a transition between v2 and v3 requesting 1.5s.
        // v2 only has 2.0s total, and 1.2s is already used by incoming t1.
        // The available duration on v2 for t2 is at most 2.0s - 1.2s = 800ms.
        val t2 = com.termex.replay15.editor.assets.TransitionInstance(
            id = "t2",
            transitionId = "zoom_blur",
            leftClipId = "v2",
            rightClipId = "v3",
            durationUs = 1_500_000L,
        )
        val pWithT2 = pWithT1.withTransition(t2)
        val addedT2 = pWithT2.transitions.first { it.id == "t2" }
        assertEquals(800_000L, addedT2.durationUs)
        assertTrue(pWithT2.durationUs > 0)
    }
}
