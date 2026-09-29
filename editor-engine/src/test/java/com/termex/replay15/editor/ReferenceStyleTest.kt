package com.termex.replay15.editor

import com.termex.replay15.editor.autoedit.*
import com.termex.replay15.editor.reference.*
import org.junit.Assert.*
import org.junit.Test

class ReferenceStyleTest {
    private fun frame(time: Long, light: Float = .35f, sharp: Float = .5f, rgb: Float = .02f) = FrameFeatures(
        time, 4, 4, ByteArray(0), FloatArray(32).also { it[(light * 31).toInt()] = 1f }, light, .3f, sharp, .4f, 0f, rgb,
    )

    @Test fun adaptiveCutDetectorFindsDiscontinuityButNotContinuousFastMotion() {
        val frames = (0..11).map { frame(it * 150_000L, if (it < 7) .3f else .65f) }
        val deltas = (1..11).map { index ->
            val cut = index == 7
            FrameDelta(index * 150_000L, if (cut) .84f else .08f, if (cut) .8f else .04f, if (cut) .35f else .01f,
                .02f, if (cut) .2f else .9f, if (index == 4) .05f else .002f, 0f, 1f, 1f, .02f)
        }
        val events = ReferenceVisualDetectors.sceneEvents(frames, deltas)
        assertEquals(1, events.count { it.type == ReferenceEventType.HARD_CUT })
        assertFalse(events.any { it.peakUs == 4 * 150_000L })
    }

    @Test fun longHighFrameRateMediaStillUsesBoundedCoarseSampling() {
        val timestamps = FrameAnalysisConfig().coarseTimestamps(5 * 60_000_000L)
        assertTrue(timestamps.size <= 2_400)
        assertTrue(timestamps.zipWithNext().all { (a, b) -> b - a >= 100_000L })
    }

    @Test fun flashReturnsToSameSceneAndIsNotTwoCuts() {
        val frames = listOf(frame(0), frame(33_333, .95f), frame(66_666))
        val flashes = ReferenceVisualDetectors.flashes(frames)
        assertEquals(1, flashes.size)
        assertTrue(flashes.single().confidence > .5f)
    }

    @Test fun profileUsesRobustMedianForZoomStrength() {
        val media = ReferenceMediaInfo("content://ref", "ref.mp4", 10_000_000, 1920, 1080, 0, 60f, true, 10, 1, "abc")
        val zooms = listOf(1.05f, 1.06f, 1.07f, 1.4f).mapIndexed { index, scale ->
            ReferenceEvent(ReferenceEventType.ZOOM, index * 1_000_000L, index * 1_000_000L + 1, index * 1_000_000L + 200_000,
                scale - 1f, .8f, mapOf("scale" to scale))
        }
        val profile = ReferenceStyleProfileBuilder.build(media, zooms, emptyList(), CaptionStyleProfile(), emptyList(), emptyList())
        assertEquals(1.065f, profile.medianZoomScale, .01f)
        assertTrue(profile.overallConfidence < .9f)
    }

    @Test fun effectMappingUsesExistingReclyAssetsAndKeepsUnknown() {
        assertEquals("recly_shake", ReferenceEffectMapper.asset(ReferenceEventType.SHAKE))
        assertEquals("recly_flash", ReferenceEffectMapper.asset(ReferenceEventType.FLASH))
        assertNull(ReferenceEffectMapper.asset(ReferenceEventType.UNKNOWN_EFFECT))
    }

    @Test fun planIsDeterministicContentBasedAndValid() {
        val profile = ReferenceStyleProfile("id", "Gaming", "finger", "content://ref", createdAtMs = 1, sourceDurationUs = 10_000_000,
            cutCount = 0, averageCutIntervalUs = 0, medianCutIntervalUs = 1_500_000, cutIntervalDistributionUs = emptyList(), dominantCutType = null,
            events = listOf(ReferenceEvent(ReferenceEventType.ZOOM, 4_000_000, 4_100_000, 4_300_000, .6f, .8f, mapOf("scale" to 1.12f)),
                ReferenceEvent(ReferenceEventType.SHAKE, 4_000_000, 4_100_000, 4_250_000, .5f, .8f)), beats = emptyList(), zoomsPerMinute = 6f,
            medianZoomScale = 1.12f, medianShakeDurationUs = 250_000, medianFlashDurationUs = 0, speedRampUsage = 0f,
            captionStyle = CaptionStyleProfile(), beatAlignment = .8f, effectCombinations = emptyList(), overallConfidence = .7f)
        val analysis = ClipAnalysis("clip", 8_000_000, audio = (0 until 80).map { i ->
            val peak = if (i == 33) .95f else .1f; AudioSample(i * 100_000L, (i + 1) * 100_000L, peak / 2, peak)
        }, motion = listOf(MotionSample(3_400_000, .9f)), hasAudio = true)
        val first = ReferenceStylePlanner.plan(profile, analysis); val second = ReferenceStylePlanner.plan(profile, analysis)
        assertEquals(first, second)
        assertTrue(first.base.zooms.all { it.timeUs != 4_100_000L })
        AutoEditPlanner.validate(first.base)
    }
}
