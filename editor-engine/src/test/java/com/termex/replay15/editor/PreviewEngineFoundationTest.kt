package com.termex.replay15.editor

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.engine.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Tests real scheduling/state logic. These do not claim hardware playback validation. */
class PreviewEngineFoundationTest {
    private fun video(id: String, seconds: Int = 5) = VideoClip(
        id = id, uri = "content://test/$id", name = id,
        sourceUs = seconds * SECOND, width = 1920, height = 1080,
    )

    @Test fun clockExcludesTimeSpentPausedAcrossTwentyPlayCycles() {
        var now = 0L
        val clock = PreviewClock { now }
        clock.setDuration(100 * SECOND)
        repeat(20) { cycle ->
            clock.start()
            clock.start() // Idempotent, must not reset the anchor.
            now += 100_000_000L
            clock.pause()
            now += 9_000_000_000L
            assertEquals((cycle + 1) * 100_000L, clock.positionUs())
            assertFalse(clock.isPlaying())
        }
    }

    @Test fun clockSeeksWhileRunningAndClampsAfterTrim() {
        var now = 0L
        val clock = PreviewClock { now }
        clock.setDuration(20 * SECOND)
        clock.start()
        now = 2_000_000_000
        clock.seek(8 * SECOND)
        now += 500_000_000
        assertEquals(8_500_000L, clock.positionUs())
        clock.setDuration(3 * SECOND)
        assertEquals(3 * SECOND, clock.positionUs())
        assertFalse(clock.isPlaying())
        clock.seek(0)
        clock.start()
        now += 4_000_000_000
        assertEquals(3 * SECOND, clock.positionUs())
        assertFalse(clock.isPlaying())
    }

    @Test fun laterClipResolvesItsOwnTrimmedSourceAndSpeed() {
        val a = video("a")
        val b = video("b", 20).copy(inUs = 4 * SECOND, outUs = 12 * SECOND, speed = 2f)
        val resolver = ActiveClipResolver(Project(videos = listOf(a, b)))
        val snapshot = resolver.resolve(6 * SECOND)
        assertEquals("b", snapshot.videos.single().clip.id)
        assertEquals(6 * SECOND, snapshot.videos.single().sourceTimeUs(snapshot.projectTimeUs))
        assertEquals(2f, snapshot.audio.single().speed, 0f)
    }

    @Test fun extendingEndedProjectDoesNotRestartPlayback() {
        var now = 0L
        val clock = PreviewClock { now }
        clock.setDuration(SECOND)
        clock.start()
        now = 2_000_000_000
        clock.setDuration(10 * SECOND)
        assertFalse(clock.isPlaying())
        assertEquals(SECOND, clock.positionUs())
        clock.seek(0)
        assertFalse(clock.isPlaying())
    }

    @Test fun splitKeepsBothClipIdentitiesForEdits() {
        val a = video("a")
        val split = Project(videos = listOf(a)).split(0, 2 * SECOND)
        val resolver = ActiveClipResolver(split)
        assertEquals(split.videos[0].id, resolver.resolve(SECOND).videos.single().clip.id)
        assertEquals(split.videos[1].id, resolver.resolve(3 * SECOND).videos.single().clip.id)
    }

    @Test fun transitionWindowHasTwoInputsAndHalfOpenEnd() {
        val transition = TransitionInstance("t", "cross_dissolve", "a", "b", SECOND, easing = Easing.LINEAR)
        val resolver = ActiveClipResolver(Project(videos = listOf(video("a"), video("b")), transitions = listOf(transition)))
        val middle = resolver.resolve(5_500_000)
        assertEquals(listOf("a", "b"), middle.videos.map { it.clip.id })
        assertEquals(.5f, middle.transitions.single().progress, .00001f)
        val after = resolver.resolve(6 * SECOND)
        assertEquals("b", after.videos.single().clip.id)
        assertTrue(after.transitions.isEmpty())
    }

    @Test fun endOfProjectKeepsLastVisibleFrame() {
        val resolver = ActiveClipResolver(Project(videos = listOf(video("a"))))
        val end = resolver.resolve(5 * SECOND)
        assertEquals(5 * SECOND - 1, end.projectTimeUs)
        assertEquals("a", end.videos.single().clip.id)
    }

    @Test fun trackGapDoesNotFallBackToFirstVideo() {
        val project = Project(videoTracks = listOf(VideoTrack("upper", listOf(TimedVideoClip(SECOND, video("v"))))))
        val resolver = ActiveClipResolver(project)
        assertTrue(resolver.resolve(0).videos.isEmpty())
        assertEquals("v", resolver.resolve(SECOND).videos.single().clip.id)
    }

    @Test fun hiddenVideoCanRemainAudibleAndMutedVideoVisible() {
        val project = Project(videos = listOf(video("a")),
            videoTracks = listOf(VideoTrack("upper", listOf(TimedVideoClip(0, video("b"))))),
            trackStates = mapOf(MAIN_TRACK to TrackState(visible = false), "upper" to TrackState(muted = true)))
        val state = ActiveClipResolver(project).resolve(0)
        assertEquals(listOf("b"), state.videos.map { it.clip.id })
        assertEquals(listOf("a"), state.audio.map { it.key.clipId })
    }

    @Test fun audioAndOverlaysUseTheirOwnActiveIntervals() {
        val project = Project(videos = listOf(video("a")),
            audio = listOf(AudioClip("sound", "content://audio", "audio", 4 * SECOND,
                startUs = SECOND, inUs = SECOND, outUs = 3 * SECOND)),
            texts = listOf(TextClip("text", "hello", SECOND, 2 * SECOND)),
            stickers = listOf(StickerClip("sticker", "content://image", "image", 2 * SECOND, 3 * SECOND)))
        val resolver = ActiveClipResolver(project)
        assertTrue(resolver.resolve(0).texts.isEmpty())
        val first = resolver.resolve(1_500_000)
        assertEquals("text", first.texts.single().id)
        assertEquals(1_500_000L, first.audio.first { it.key.clipId == "sound" }.sourceTimeUs)
        assertTrue(first.stickers.isEmpty())
        val second = resolver.resolve(2 * SECOND)
        assertTrue(second.texts.isEmpty())
        assertEquals("sticker", second.stickers.single().id)
        assertEquals(1, resolver.resolve(3 * SECOND).audio.size)
    }

    @Test fun embeddedAndExternalAudioHaveStableDistinctIdentities() {
        val clip = video("spoken").copy(inUs = SECOND, outUs = 4 * SECOND, speed = 2f, preservePitch = true)
        val music = AudioClip("music", "content://music", "music", 10 * SECOND, startUs = 250_000L,
            inUs = 2 * SECOND, outUs = 6 * SECOND)
        val resolver = ActiveClipResolver(Project(videos = listOf(clip), audio = listOf(music)))
        val sources = resolver.audioAt(500_000L, 7L)
        assertEquals(setOf("video:spoken:audio", "audio:music"), sources.map { it.id }.toSet())
        assertEquals(7L, sources.single { it.embedded }.generation)
        assertEquals(2 * SECOND, sources.single { it.embedded }.sourceTimeUs)
        assertEquals(2_250_000L, sources.single { !it.embedded }.sourceTimeUs)
    }

    @Test fun activeTextUsesHalfOpenProjectInterval() {
        val text = TextClip("text", "TESTE", 0L, 5 * SECOND)
        val project = Project(texts = listOf(text))
        assertEquals(listOf(text), ActiveTextResolver.activeTexts(project, 0L))
        assertEquals(listOf(text), ActiveTextResolver.activeTexts(project, 2 * SECOND))
        assertEquals(listOf(text), ActiveTextResolver.activeTexts(project, 4_999_999L))
        assertTrue(ActiveTextResolver.activeTexts(project, 5 * SECOND).isEmpty())
    }

    @Test fun videoFrameSchedulerDropsLateVideoWithoutHoldingAudio() {
        val scheduler = VideoFrameScheduler()
        assertTrue(scheduler.decide(900_000L, SECOND) is VideoFrameScheduler.Decision.Drop)
        assertTrue(scheduler.decide(SECOND, SECOND) is VideoFrameScheduler.Decision.Present)
        assertTrue(scheduler.decide(1_030_000L, SECOND) is VideoFrameScheduler.Decision.Wait)
    }

    @Test fun canvasCoordinatesMatchAndroidViewAndGlConventions() {
        assertEquals(Vec2(500f, 250f), PreviewCoordinates.canvasToView(.5f, .25f, 1000, 1000))
        assertEquals(Vec2(0f, .5f), PreviewCoordinates.canvasToNdc(.5f, .25f))
    }

    @Test fun fiftyClipsDoNotCreateFiftyDecoderDemands() {
        val resolver = ActiveClipResolver(Project(videos = List(50) { video("v$it") }))
        val planner = DecoderDemandPlanner(2)
        val far = planner.plan(resolver.resolve(SECOND), resolver.upcoming(SECOND, 300_000))
        assertEquals(1, far.active.size)
        assertTrue(far.prewarm.isEmpty())
        val near = planner.plan(resolver.resolve(4_800_000), resolver.upcoming(4_800_000, 300_000))
        assertEquals("v0", near.active.single().clip.id)
        assertEquals("v1", near.prewarm.single().clip.id)
        val boundary = planner.plan(resolver.resolve(5 * SECOND), resolver.upcoming(5 * SECOND, 300_000))
        assertEquals("v1", boundary.active.single().clip.id)
        assertTrue(boundary.prewarm.isEmpty())
    }

    @Test fun decoderBudgetGivesActiveInputsPriorityOverPrewarm() {
        val resolver = ActiveClipResolver(Project(videos = listOf(video("a"), video("b")),
            videoTracks = List(3) { VideoTrack("layer$it", listOf(TimedVideoClip(0, video("upper$it")))) }))
        val demand = DecoderDemandPlanner(2).plan(resolver.resolve(4_800_000), resolver.upcoming(4_800_000, 300_000))
        assertEquals(2, demand.active.size)
        assertEquals(2, demand.omitted.size)
        assertTrue(demand.prewarm.isEmpty())
    }

    @Test fun photosAndNullObjectsDoNotConsumeVideoDecoderBudget() {
        val resolver = ActiveClipResolver(Project(videos = listOf(video("photo").copy(image = true)),
            videoTracks = listOf(VideoTrack("null", listOf(TimedVideoClip(0, video("control").copy(isNullObject = true)))))))
        val state = resolver.resolve(0)
        assertEquals(1, state.videos.size)
        assertTrue(DecoderDemandPlanner(2).plan(state, emptyList()).active.isEmpty())
    }

    @Test fun tenMinutesOfTimelinePositionsKeepDemandBounded() {
        val resolver = ActiveClipResolver(Project(videos = List(120) { video("v$it") }))
        val planner = DecoderDemandPlanner(2)
        for (time in 0L..600_000_000L step 33_333L) {
            val demand = planner.plan(resolver.resolve(time), resolver.upcoming(time, 300_000))
            assertTrue(demand.active.size + demand.prewarm.size <= 2)
        }
    }

    @Test fun embeddedAudioSurvivesHundredForwardBackwardSeeks() {
        val spoken = video("spoken", 600).copy(inUs = 12 * SECOND, outUs = 590 * SECOND, speed = 1.25f)
        val resolver = ActiveClipResolver(Project(videos = listOf(spoken)))
        repeat(100) { index ->
            val forward = (index * 3_000_000L) % spoken.durationUs
            val backward = (spoken.durationUs - 1 - forward).coerceAtLeast(0L)
            listOf(forward, backward).forEach { time ->
                val source = resolver.audioAt(time, index.toLong()).single()
                assertEquals("video:spoken:audio", source.id)
                assertEquals(ProjectClipTimeMapper.projectToSource(spoken, 0L, time), source.sourceTimeUs)
            }
        }
    }

    @Test fun oneTransportClockSurvivesHundredPlayPauseCycles() {
        var now = 0L
        val clock = com.termex.replay15.editor.preview.engine.audio.PreviewTransportClock(nowNs = { now })
        clock.setDuration(20 * SECOND)
        repeat(100) {
            clock.start(); now += 10_000_000L; clock.pause(); now += 1_000_000_000L
        }
        assertEquals(SECOND, clock.positionUs())
    }

    @Test fun repeatedTextSeeksNeverUseVideoPts() {
        val project = Project(texts = listOf(TextClip("caption", "TESTE", 3 * SECOND, 6 * SECOND)))
        repeat(100) {
            assertTrue(ActiveTextResolver.activeTexts(project, SECOND).isEmpty())
            assertEquals("caption", ActiveTextResolver.activeTexts(project, 4 * SECOND).single().id)
            assertTrue(ActiveTextResolver.activeTexts(project, 7 * SECOND).isEmpty())
        }
    }

    @Test fun scrubCoalescesAndPreciseReleaseSupersedesAllEarlierRequests() {
        val mailbox = SeekMailbox()
        val first = mailbox.submit(0, SeekMailbox.Mode.FAST_SCRUB)
        repeat(100) { mailbox.submit(it * 50_000L, SeekMailbox.Mode.FAST_SCRUB) }
        val final = mailbox.submit(5 * SECOND, SeekMailbox.Mode.PRECISE)
        assertEquals(final, mailbox.take())
        assertNull(mailbox.take())
        assertEquals(101L, mailbox.coalescedCount)
        var shown = -1L
        assertFalse(mailbox.publishIfCurrent(first.generation) { shown = first.projectTimeUs })
        assertTrue(mailbox.publishIfCurrent(final.generation) { shown = final.projectTimeUs })
        assertEquals(5 * SECOND, shown)
        mailbox.close()
        assertFalse(mailbox.publishIfCurrent(final.generation) { fail("Closed session published") })
    }

    @Test fun concurrentSubmitNeverReplacesNewGenerationWithOldRequest() {
        val mailbox = SeekMailbox()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (0..3).map { worker -> executor.submit {
                check(start.await(2, TimeUnit.SECONDS))
                repeat(500) { mailbox.submit((worker * 500 + it).toLong(), SeekMailbox.Mode.FAST_SCRUB) }
            } }
            start.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS) }
            val request = requireNotNull(mailbox.take())
            assertEquals(2000L, request.generation)
            assertTrue(mailbox.isCurrent(request.generation))
            mailbox.invalidate()
            assertFalse(mailbox.isCurrent(request.generation))
        } finally { executor.shutdownNow() }
    }

    @Test fun runtimeStatusIndependentlyTracksReadiness() {
        val detached = PreviewRuntimeStatus(
            decoderReady = true,
            decoderSurfaceReady = true,
            displaySurfaceAttached = false,
            glReady = true,
            frameAvailable = true,
            playing = true,
        )
        assertTrue(detached.canDecode)
        assertFalse(detached.canRender)
        assertFalse(detached.canPlay)

        val attached = detached.copy(displaySurfaceAttached = true)
        assertTrue(attached.canDecode)
        assertTrue(attached.canRender)
        assertTrue(attached.canPlay)
    }

    @Test fun canonicalQuadMapsBottomLeftToZeroAndTopLeftToOne() {
        val quad = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        // Stride is 4 floats (x, y, u, v)
        // Vertex 0: Bottom-Left (-1, -1) -> UV (0, 0)
        assertEquals(-1f, quad[0], 0f)
        assertEquals(-1f, quad[1], 0f)
        assertEquals(0f, quad[2], 0f)
        assertEquals(0f, quad[3], 0f)

        // Vertex 1: Bottom-Right (1, -1) -> UV (1, 0)
        assertEquals(1f, quad[4], 0f)
        assertEquals(-1f, quad[5], 0f)
        assertEquals(1f, quad[6], 0f)
        assertEquals(0f, quad[7], 0f)

        // Vertex 2: Top-Left (-1, 1) -> UV (0, 1)
        assertEquals(-1f, quad[8], 0f)
        assertEquals(1f, quad[9], 0f)
        assertEquals(0f, quad[10], 0f)
        assertEquals(1f, quad[11], 0f)

        // Vertex 3: Top-Right (1, 1) -> UV (1, 1)
        assertEquals(1f, quad[12], 0f)
        assertEquals(1f, quad[13], 0f)
        assertEquals(1f, quad[14], 0f)
        assertEquals(1f, quad[15], 0f)
    }
}
