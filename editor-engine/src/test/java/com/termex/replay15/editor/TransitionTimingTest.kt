package com.termex.replay15.editor

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.engine.ActiveAudioSourceResolver
import com.termex.replay15.editor.preview.engine.ActiveClipResolver
import com.termex.replay15.editor.project.ProjectCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TransitionTimingTest {
    private fun clip(id: String, sourceUs: Long = 12 * SECOND, inUs: Long = SECOND, outUs: Long = 11 * SECOND) =
        VideoClip(id = id, uri = "file:///$id.mp4", name = id, sourceUs = sourceUs, inUs = inUs, outUs = outUs,
            width = 1920, height = 1080)

    private fun transition(left: String, right: String, durationUs: Long, alignment: TransitionAlignment = TransitionAlignment.CENTER) =
        TransitionInstance("$left$right", "cross_dissolve", left, right, durationUs, easing = Easing.LINEAR, alignment = alignment)

    @Test fun alignmentPlacesTheWindowAroundTheCutWithoutMovingIt() {
        val a = clip("a"); val b = clip("b")
        val cut = a.durationUs
        for ((alignment, expected) in listOf(
            TransitionAlignment.CENTER to (cut - 400_000L to cut + 400_000L),
            TransitionAlignment.START_AT_CUT to (cut to cut + 800_000L),
            TransitionAlignment.END_AT_CUT to (cut - 800_000L to cut),
        )) {
            val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", 800_000L, alignment)))
            val window = TransitionTiming.windows(project).single()
            assertEquals(alignment.name, expected.first, window.startUs)
            assertEquals(alignment.name, expected.second, window.endUs)
            assertEquals(cut, window.cutUs)
            assertEquals(cut, project.startOf(1))
            assertEquals(a.durationUs + b.durationUs, project.durationUs)
            // The extension functions used by export/compositor agree with the timing model.
            assertEquals(window.startUs, project.transitions.single().startUs(project))
            assertEquals(window.endUs, project.transitions.single().endUs(project))
        }
    }

    @Test fun shortMiddleClipShrinksBothNeighbouringWindowsInsteadOfOverlapping() {
        val a = clip("a"); val b = clip("b", inUs = SECOND, outUs = SECOND + 600_000L); val c = clip("c")
        // Two centered 2 s windows need 1 s + 1 s inside B, which only lasts 0.6 s.
        val project = Project(videos = listOf(a, b, c), transitions = listOf(transition("a", "b", 2 * SECOND), transition("b", "c", 2 * SECOND)))
        val (ab, bc) = TransitionTiming.windows(project)
        assertTrue("windows overlap: $ab / $bc", ab.endUs <= bc.startUs)
        assertTrue(ab.endUs - ab.cutUs + (bc.cutUs - bc.startUs) <= b.durationUs)
        assertEquals(1 * SECOND, ab.cutUs - ab.startUs) // the side inside A keeps its full share
    }

    @Test fun handlesContinueThroughRealMediaAndHoldWhenMediaRunsOut() {
        val roomy = clip("roomy") // 1 s of media on each side of the trim
        assertEquals(SECOND - 250_000L, TransitionHandles.sourceAt(roomy, -250_000L))
        assertEquals(11 * SECOND + 250_000L, TransitionHandles.sourceAt(roomy, roomy.durationUs + 250_000L))
        val tight = clip("tight", sourceUs = 10 * SECOND, inUs = 0L, outUs = 10 * SECOND) // no media outside the trim
        assertEquals(0L, TransitionHandles.availableHeadUs(tight))
        assertEquals(0L, TransitionHandles.availableTailUs(tight))
        assertEquals(0L, TransitionHandles.sourceAt(tight, -500_000L))
        assertEquals(10 * SECOND - 1, TransitionHandles.sourceAt(tight, tight.durationUs + 500_000L))
        val fast = roomy.copy(speed = 2f)
        assertEquals(SECOND - 500_000L, TransitionHandles.sourceAt(fast, -250_000L))
        assertEquals(500_000L, TransitionHandles.availableHeadUs(fast))
    }

    @Test fun reversedAndRampedClipsHoldTheirBoundaryFrameSoExportCanMatch() {
        val reversed = clip("r").copy(reverse = true)
        val ramped = clip("s").copy(speedCurve = listOf(SpeedPoint(0, .5f), SpeedPoint(12 * SECOND, 2f)))
        for (c in listOf(reversed, ramped)) {
            assertFalse(TransitionHandles.supportsHandles(c))
            val first = TransitionHandles.sourceAt(c, 0L)
            assertEquals(first, TransitionHandles.sourceAt(c, -300_000L))
            val last = TransitionHandles.sourceAt(c, c.durationUs - 1L)
            assertEquals(last, TransitionHandles.sourceAt(c, c.durationUs + 300_000L))
        }
    }

    @Test fun handleFramesMapBackToTheirOwnProjectTimeForPacing() {
        val a = clip("a"); val b = clip("b")
        val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", SECOND)))
        val resolver = ActiveClipResolver(project)
        val cut = a.durationUs
        for (time in listOf(cut - 400_000L, cut - 1L, cut, cut + 250_000L, cut + 499_999L)) {
            resolver.resolve(time).videos.forEach { video ->
                val source = video.sourceTimeUs(time)
                assertEquals("${video.clip.id} at $time", time.toDouble(), video.projectTimeUs(source).toDouble(), 1.0)
            }
        }
        // The incoming clip is pre-warmed from its head handle, before the cut.
        val upcoming = resolver.upcoming(cut - SECOND, 2 * SECOND).single { it.clip.id == "b" }
        assertEquals(cut - 500_000L, upcoming.visibleStartUs)
    }

    @Test fun maxDurationRespectsAlignmentNeighboursAndTheShorterClip() {
        val a = clip("a"); val b = clip("b", inUs = SECOND, outUs = 3 * SECOND); val c = clip("c")
        val base = Project(videos = listOf(a, b, c), transitions = listOf(transition("b", "c", SECOND, TransitionAlignment.END_AT_CUT)))
        // B lasts 2 s and its last second is already used by the B→C window.
        assertEquals(SECOND, TransitionTiming.maxDurationUs(base, transition("a", "b", 0L + 100_000L, TransitionAlignment.START_AT_CUT)))
        assertEquals(2 * SECOND, TransitionTiming.maxDurationUs(base, transition("a", "b", 100_000L, TransitionAlignment.CENTER)))
        assertEquals(2 * SECOND, TransitionTiming.maxDurationUs(base, transition("a", "b", 100_000L, TransitionAlignment.END_AT_CUT)))
        val applied = base.withTransition(transition("a", "b", 4 * SECOND, TransitionAlignment.START_AT_CUT))
        assertEquals(SECOND, applied.transitionBetween("a", "b")!!.durationUs)
        val windows = TransitionTiming.windows(applied)
        assertTrue(windows[0].endUs <= windows[1].startUs)
    }

    @Test fun audioCrossFadesWithEqualPowerAndBothSourcesThroughTheWindow() {
        val a = clip("a"); val b = clip("b")
        val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", SECOND)))
        val resolver = ActiveAudioSourceResolver(project)
        val cut = a.durationUs
        for (time in (cut - 500_000L until cut + 500_000L step 50_000L)) {
            val sources = resolver.resolve(time, 1L)
            assertEquals("both sides audible at $time", 2, sources.size)
            val power = sources.sumOf { (it.volume * it.volume).toDouble() }
            assertEquals("constant loudness at $time", 1.0, power, 0.02)
        }
        val solo = resolver.resolve(cut + 600_000L, 1L).single()
        assertEquals(1f, solo.volume, 0.0001f)
        // B's prewarm seek lands on its head handle.
        val b0 = resolver.resolve(cut - 500_000L, 1L).single { it.key.clipId == "b" }
        assertEquals(b.inUs - 500_000L, b0.sourceStartUs)
    }

    @Test fun alignmentSurvivesSaveAndLegacyFilesLoadAsStartAtCut() {
        val project = Project(videos = listOf(clip("a"), clip("b"), clip("c")), transitions = listOf(
            transition("a", "b", 700_000L, TransitionAlignment.END_AT_CUT),
            transition("b", "c", 900_000L, TransitionAlignment.CENTER)))
        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }.toByteArray()
        val read = ProjectCodec.read(ByteArrayInputStream(bytes))
        assertEquals(project.transitions, read.transitions)
        // A schema-28 file is the same bytes without the alignment appendix.
        val legacy = bytes.copyOf(bytes.size - 4 - 4 * project.transitions.size)
        java.nio.ByteBuffer.wrap(legacy).putInt(4, 28)
        val old = ProjectCodec.read(ByteArrayInputStream(legacy))
        assertEquals(listOf(TransitionAlignment.START_AT_CUT, TransitionAlignment.START_AT_CUT), old.transitions.map { it.alignment })
        assertEquals(project.transitions.map { it.durationUs }, old.transitions.map { it.durationUs })
    }
}
