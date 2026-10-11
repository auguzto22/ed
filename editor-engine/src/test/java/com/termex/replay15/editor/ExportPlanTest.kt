package com.termex.replay15.editor

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.engine.ActiveAudioSourceResolver
import com.termex.replay15.editor.preview.engine.ActiveClipResolver
import com.termex.replay15.editor.render.ExportClock
import com.termex.replay15.editor.render.ExportPiece
import com.termex.replay15.editor.render.RenderPlan
import com.termex.replay15.editor.render.TransitionVisibility
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ExportPlanTest {
    private fun clip(id: String, sourceUs: Long = 12 * SECOND, inUs: Long = SECOND, outUs: Long = 11 * SECOND) =
        VideoClip(id = id, uri = "file:///$id.mp4", name = id, sourceUs = sourceUs, inUs = inUs, outUs = outUs,
            width = 1920, height = 1080)

    private fun transition(left: String, right: String, durationUs: Long, alignment: TransitionAlignment = TransitionAlignment.CENTER) =
        TransitionInstance("$left$right", "cross_dissolve", left, right, durationUs, easing = Easing.LINEAR, alignment = alignment)

    private fun mainPieces(project: Project): List<ExportPiece> =
        RenderPlan.layers(project).filter { it.id == MAIN_TRACK_A || it.id == MAIN_TRACK_B }.flatMap { it.pieces }

    private fun ExportPiece.sourceAt(timeUs: Long): Long =
        if (hold) sourceStartUs else sourceStartUs + ((timeUs - startUs) * speed.toDouble()).toLong()

    @Test fun roomyClipsPlayTheirHandlesThroughACenteredWindow() {
        val a = clip("a"); val b = clip("b")
        val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", SECOND)))
        val cut = a.durationUs
        val (pa, pb) = mainPieces(project)
        assertFalse(pa.hold); assertFalse(pb.hold)
        assertEquals(0L, pa.startUs)
        assertEquals(cut + 500_000L, pa.endUs)
        assertEquals(a.outUs + 500_000L, pa.sourceEndUs)
        assertEquals(cut - 500_000L, pb.startUs)
        assertEquals(b.inUs - 500_000L, pb.sourceStartUs)
        assertEquals(b.outUs, pb.sourceEndUs)
        assertEquals(project.durationUs, pb.endUs)
    }

    @Test fun untrimmedIncomingClipHoldsItsFirstFrameAndOutgoingClipEndsAtItsMedia() {
        val a = clip("a", sourceUs = 10 * SECOND, inUs = 0L, outUs = 10 * SECOND)
        val b = clip("b", sourceUs = 10 * SECOND, inUs = 0L, outUs = 10 * SECOND)
        val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", SECOND)))
        val cut = a.durationUs
        val pieces = mainPieces(project)
        assertEquals(3, pieces.size)
        val (pa, hold, pb) = listOf(pieces[0], pieces[1], pieces[2])
        assertEquals(cut, pa.endUs) // no media after the out point: the shader keeps its last frame
        assertTrue(hold.hold)
        assertEquals(cut - 500_000L, hold.startUs)
        assertEquals(cut, hold.endUs)
        assertEquals(0L, hold.sourceStartUs)
        assertEquals(1f, hold.speed, 0.0001f)
        assertEquals(cut, pb.startUs)
        assertFalse(pb.extended)
    }

    @Test fun partialHeadIsHeldThenPlayedExactlyLikeThePreview() {
        val a = clip("a"); val b = clip("b", inUs = 300_000L, outUs = 8 * SECOND).copy(speed = 1.5f)
        val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", 2 * SECOND)))
        val pieces = mainPieces(project).filter { it.clip.id == "b" }
        val (hold, main) = pieces
        assertTrue(hold.hold)
        assertEquals(hold.endUs, main.startUs)
        assertEquals(TransitionHandles.availableHeadUs(b), project.startOf(1) - main.startUs)
        val resolver = ActiveClipResolver(project)
        val window = TransitionTiming.windows(project).single()
        for (time in window.startUs until window.endUs step 20_000L) {
            val piece = pieces.single { it.contains(time) }
            val preview = resolver.resolve(time).videos.single { it.clip.id == "b" }.sourceTimeUs(time)
            assertEquals("b at $time", preview.toDouble(), piece.sourceAt(time).toDouble(), 2.0)
        }
    }

    @Test fun reversedAndRampedClipsHoldTheirBoundaryFrameAndKeepTheirSpeedMap() {
        val a = clip("a")
        for (b in listOf(clip("b").copy(reverse = true), clip("b").copy(speedCurve = listOf(SpeedPoint(0, .5f), SpeedPoint(12 * SECOND, 2f))))) {
            val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", SECOND)))
            val pieces = mainPieces(project).filter { it.clip.id == "b" }
            assertEquals(2, pieces.size)
            assertTrue(pieces[0].hold)
            assertEquals(b.inUs, pieces[0].sourceStartUs)
            assertFalse("the clip's own item keeps its speed map", pieces[1].extended)
        }
    }

    @Test fun photosExtendWithoutHolding() {
        val a = clip("a"); val photo = clip("p", sourceUs = 3 * SECOND, inUs = 0L, outUs = 3 * SECOND).copy(image = true)
        val c = clip("c")
        val project = Project(videos = listOf(a, photo, c), transitions = listOf(
            transition("a", "p", SECOND, TransitionAlignment.END_AT_CUT), transition("p", "c", 600_000L)))
        val piece = mainPieces(project).single { it.clip.id == "p" }
        assertFalse(piece.hold)
        assertEquals(project.startOf(1) - SECOND, piece.startUs)
        assertEquals(project.startOf(2) + 300_000L, piece.endUs)
        assertEquals(1f, piece.speed, 0f)
    }

    @Test fun handlesShorterThanAFrameAreHeldInsteadOfDecoded() {
        val a = clip("a"); val b = clip("b", inUs = 20_000L)
        val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", SECOND)))
        val (hold, main) = mainPieces(project).filter { it.clip.id == "b" }
        assertTrue(hold.hold)
        assertEquals(project.startOf(1), main.startUs)
        assertEquals(b.inUs, main.sourceStartUs)
    }

    @Test fun clipsWithoutTransitionsKeepExactlyTheirOwnItem() {
        val clips = listOf(clip("a"), clip("b"), clip("c"))
        val project = Project(videos = clips, transitions = listOf(transition("a", "b", SECOND)))
        val c = mainPieces(project).single { it.clip.id == "c" }
        assertFalse(c.extended)
        assertEquals(clips[2].inUs, c.sourceStartUs)
        assertEquals(clips[2].outUs, c.sourceEndUs)
    }

    @Test fun randomProjectsNeverOverlapAndAlwaysCoverBothSidesOfEveryWindow() {
        val random = Random(29)
        repeat(300) { round ->
            val clips = (0 until 2 + random.nextInt(5)).map { index ->
                val source = (1 + random.nextInt(12)) * SECOND / 2
                val inUs = if (random.nextBoolean()) 0L else random.nextLong(source / 2)
                val outUs = if (random.nextBoolean()) source else random.nextLong(inUs + 200_000L, source + 1)
                clip("c$index", source, inUs, outUs.coerceAtMost(source)).copy(
                    speed = listOf(.5f, 1f, 1f, 2f)[random.nextInt(4)], image = random.nextInt(6) == 0)
            }.filter { it.durationUs >= 200_000L }
            if (clips.size < 2) return@repeat
            val transitions = clips.zipWithNext().filter { random.nextInt(4) != 0 }.map { (l, r) ->
                transition(l.id, r.id, random.nextLong(100_000L, 4 * SECOND), TransitionAlignment.entries[random.nextInt(3)])
            }
            val project = Project(videos = clips, transitions = transitions)
            val layers = RenderPlan.layers(project)
            layers.forEach { layer ->
                var end = 0L
                layer.pieces.forEach { piece ->
                    assertTrue("round $round overlap in ${layer.id}", RenderPlan.gapBefore(end, piece.startUs) >= 0L)
                    assertTrue(piece.durationUs > 0L)
                    assertTrue(piece.sourceStartUs >= 0L && piece.sourceEndUs <= piece.clip.sourceUs)
                    assertTrue(piece.sourceEndUs > piece.sourceStartUs)
                    end = piece.endUs
                }
                assertTrue(end <= project.durationUs)
            }
            val pieces = layers.flatMap { it.pieces }
            TransitionTiming.windows(project).forEach { window ->
                val left = project.videos[window.leftIndex]; val right = project.videos[window.rightIndex]
                for (time in window.startUs until window.endUs step 10_000L) {
                    // The incoming clip always has a frame to draw the transition on.
                    assertTrue("round $round: no incoming frame at $time",
                        pieces.any { it.clip.id == right.id && it.contains(time) } || time < window.startUs + TransitionHandles.MIN_USABLE_HANDLE_US)
                    // Before the cut the outgoing clip plays its own media.
                    if (time < window.cutUs) assertTrue(pieces.any { it.clip.id == left.id && it.contains(time) })
                }
            }
        }
    }

    @Test fun compositorFallsBackToThePlainCutUntilTheShaderHasDrawn() {
        val project = Project(videos = listOf(clip("a"), clip("b")), transitions = listOf(transition("a", "b", SECOND)))
        val window = TransitionTiming.windows(project).single()
        val before = window.cutUs - 1; val after = window.cutUs
        assertTrue(TransitionVisibility.visible(window, "a", before, Long.MAX_VALUE))
        assertFalse(TransitionVisibility.visible(window, "b", before, Long.MAX_VALUE))
        assertFalse(TransitionVisibility.visible(window, "a", after, Long.MAX_VALUE))
        assertTrue(TransitionVisibility.visible(window, "b", after, Long.MAX_VALUE))
        // From the first composited frame on, the incoming clip carries the whole picture.
        assertFalse(TransitionVisibility.visible(window, "a", before, window.startUs))
        assertTrue(TransitionVisibility.visible(window, "b", before, window.startUs))
        assertTrue(TransitionVisibility.visible(window, "a", window.startUs, window.startUs + 40_000L))
    }

    @Test fun effectClockKeepsCompositionTimeForHandlesBeforeTheClip() {
        val clipStart = 10 * SECOND
        // A head handle frame lies before the clip but inside its item.
        assertEquals(clipStart - 300_000L, ExportClock.projectTimeUs(clipStart - 300_000L, clipStart - 500_000L, clipStart + 4 * SECOND))
        // Item-relative timestamps are still mapped.
        assertEquals(clipStart - 500_000L + 200_000L, ExportClock.projectTimeUs(200_000L, clipStart - 500_000L, clipStart + 4 * SECOND))
        // Unextended items behave exactly like before.
        assertEquals(clipStart + SECOND, ExportClock.projectTimeUs(SECOND, clipStart, clipStart + 4 * SECOND))
    }

    @Test fun audioEnvelopeCrossFadesWithEqualPowerAndKeepsOwnFadesElsewhere() {
        val a = clip("a").copy(audioFadeInUs = 800_000L, audioFadeOutUs = 800_000L, volume = .5f)
        val b = clip("b").copy(audioFadeInUs = 800_000L, audioFadeOutUs = 800_000L)
        val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", SECOND)))
        val window = TransitionTiming.windows(project).single()
        val ea = ClipAudioEnvelope(a, 0L, null, window)
        val eb = ClipAudioEnvelope(b, a.durationUs, window, null)
        for (time in window.startUs until window.endUs step 25_000L) {
            val pa = ea.gainAt(time) / .5f; val pb = eb.gainAt(time)
            assertEquals("power at $time", 1.0, (pa * pa + pb * pb).toDouble(), 0.02)
        }
        assertEquals(0f, ea.gainAt(0L), 0.0001f) // A keeps its own fade-in
        assertEquals(.5f, ea.gainAt(2 * SECOND), 0.0001f) // volume is applied inside and outside fades
        assertTrue(ea.gainAt(400_000L) in 0.01f..0.49f)
        assertEquals(0f, eb.gainAt(project.durationUs), 0.0001f) // B keeps its own fade-out
    }

    @Test fun unityStretchesAreReportedSoTheGainStageCanSkipThem() {
        val a = clip("a"); val b = clip("b")
        val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", SECOND)))
        val window = TransitionTiming.windows(project).single()
        val ea = ClipAudioEnvelope(a, 0L, null, window)
        assertEquals(window.startUs, ea.unityUntilUs(SECOND))
        assertNull(ea.unityUntilUs(window.startUs + 100_000L))
        // cos(0) is exactly 1 at the first instant of the fade: only that instant is unity.
        assertEquals(window.startUs + 1, ea.unityUntilUs(window.startUs))
        val quiet = ClipAudioEnvelope(a.copy(volume = .7f), 0L, null, window)
        assertNull(quiet.unityUntilUs(SECOND))
    }

    @Test fun previewAudioIsSilentWhereThePictureHoldsAFrame() {
        val a = clip("a"); val b = clip("b", inUs = 0L)
        val project = Project(videos = listOf(a, b), transitions = listOf(transition("a", "b", SECOND)))
        val resolver = ActiveAudioSourceResolver(project)
        val cut = a.durationUs
        assertEquals(listOf("a"), resolver.resolve(cut - 300_000L, 1L).map { it.key.clipId })
        assertEquals(setOf("a", "b"), resolver.resolve(cut + 100_000L, 1L).map { it.key.clipId }.toSet())
        // The incoming sound starts at its in point together with the picture.
        assertEquals(0L, resolver.resolve(cut, 1L).single { it.key.clipId == "b" }.sourceStartUs)
        assertTrue(abs(resolver.resolve(cut, 1L).sumOf { (it.volume * it.volume).toDouble() } - 1.0) < 0.02)
    }
}
