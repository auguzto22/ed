package com.termex.replay15.editor

import com.termex.replay15.editor.autoedit.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.reference.*
import org.junit.Assert.*
import org.junit.Test

class ReferencePunchInTest {
    /** A talking-head reference: jump cuts every 3 s, captions, vertical. */
    private fun talkingReference(aspect: Float = 9f / 16f) = ReferenceStyleProfile("ref", "Podcast", "finger", "content://ref",
        createdAtMs = 1, sourceDurationUs = 12_000_000, cutCount = 3, averageCutIntervalUs = 3_000_000, medianCutIntervalUs = 3_000_000,
        cutIntervalDistributionUs = listOf(3_000_000, 3_000_000), dominantCutType = ReferenceEventType.HARD_CUT,
        events = listOf(3_000_000L, 6_000_000L, 9_000_000L).map { ReferenceEvent(ReferenceEventType.HARD_CUT, it, it, it + 1, .8f, .8f) },
        beats = emptyList(), zoomsPerMinute = 0f, medianZoomScale = 1f, medianShakeDurationUs = 0, medianFlashDurationUs = 0,
        speedRampUsage = 0f, captionStyle = CaptionStyleProfile(present = true, uppercase = true), beatAlignment = 0f,
        effectCombinations = emptyList(), overallConfidence = .7f, aspect = aspect)

    private fun words(): List<TimedWord> = (0 until 10).flatMap { phrase ->
        val start = 200_000L + phrase * 1_400_000L
        listOf(TimedWord("isso", start, start + 400_000L), TimedWord("aqui.", start + 420_000L, start + 900_000L))
    }

    @Test fun referenceCutRhythmBecomesPunchInsOnPhraseStarts() {
        val analysis = ClipAnalysis("clip", 14 * SECOND, words = words(),
            faces = listOf(FaceSample(0, .5f, .38f, .3f), FaceSample(14 * SECOND - 1, .5f, .38f, .3f)))
        val plan = ReferenceStylePlanner.plan(talkingReference(), analysis)
        val zooms = plan.base.zooms
        assertTrue("no punch-ins: ${plan.skipped}", zooms.size >= 2)
        assertTrue(zooms.all { it.hold && it.scale >= 1.1f })
        val phraseStarts = PunchInPlanner.phraseStarts(analysis.words).toSet()
        assertTrue(zooms.all { it.timeUs in phraseStarts })
        assertTrue(plan.applied.any { it.startsWith("Punch-in") })
        AutoEditPlanner.validate(plan.base)
    }

    @Test fun withoutCaptionsTheSkipNoteSaysWhy() {
        val plan = ReferenceStylePlanner.plan(talkingReference(), ClipAnalysis("clip", 14 * SECOND))
        assertTrue(plan.base.zooms.isEmpty())
        assertTrue(plan.skipped.any { it.startsWith("Zoom:") })
    }

    @Test fun editTakesTheReferenceProportionsForAClipOfTheSameOrientation() {
        val vertical = VideoClip(uri = "file:///v.mp4", name = "v", sourceUs = 10 * SECOND, width = 720, height = 960)
        val project = Project(videos = listOf(vertical), export = ExportSettings.forSource(vertical))
        val result = ReferenceStyleExecutor.withReferenceAspect(project, vertical, talkingReference())
        assertEquals(9f / 16f, result.aspect, 1e-4f)
        assertEquals(CanvasFill.FILL, result.canvasFill)
        // A landscape clip is not squeezed into 9:16; that is Auto Reframe's job.
        val wide = vertical.copy(width = 1920, height = 1080)
        assertEquals(project.aspect, ReferenceStyleExecutor.withReferenceAspect(project, wide, talkingReference()).aspect)
        // Profiles analysed before the aspect was stored change nothing.
        assertEquals(project, ReferenceStyleExecutor.withReferenceAspect(project, vertical, talkingReference(aspect = 0f)))
    }
}
