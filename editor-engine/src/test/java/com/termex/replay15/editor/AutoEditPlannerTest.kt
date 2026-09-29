package com.termex.replay15.editor

import com.termex.replay15.editor.autoedit.*
import org.junit.Assert.*
import org.junit.Test

class AutoEditPlannerTest {
    @Test fun balancedCleanupRemovesOnlyCenterOfMeasuredDeadAir() {
        val audio = (0 until 60).map { index ->
            val start = index * 100_000L
            val silent = index in 20..34
            AudioSample(start, start + 100_000L, if (silent) .0002f else .09f, if (silent) .0005f else .25f)
        }
        val motion = (0 until 12).map { MotionSample(it * 500_000L, .02f) }
        val plan = AutoEditPlanner.plan(ClipAnalysis("clip", 6_000_000L, audio, motion, hasAudio = true), AutoEditOptions(
            style = AutoEditStyle.CLEAN, smartZoom = false, autoReframe = false, captions = false, audioEnhancement = false,
        ))
        assertEquals(1, plan.cuts.size)
        val cut = plan.cuts.single()
        assertTrue(cut.startUs > 2_000_000L)
        assertTrue(cut.endUs < 3_500_000L)
        assertTrue(cut.endUs > cut.startUs)
    }

    @Test fun noAnalysisEvidenceMeansDoingNothingIsValid() {
        val plan = AutoEditPlanner.plan(ClipAnalysis("clip", 5_000_000L), AutoEditOptions(
            captions = false, audioEnhancement = false,
        ))
        assertTrue(plan.isEmpty())
        assertTrue(plan.summary().contains("nenhuma mudanca"))
    }

    @Test fun captionSegmentationUsesPhrasesAndReadingDuration() {
        val words = listOf(
            TimedWord("isso", 0, 300_000), TimedWord("ficou", 320_000, 620_000),
            TimedWord("muito", 640_000, 900_000), TimedWord("bom!", 920_000, 1_200_000),
            TimedWord("agora", 1_700_000, 2_000_000), TimedWord("olha", 2_020_000, 2_300_000),
        )
        val plan = AutoEditPlanner.plan(ClipAnalysis("clip", 3_000_000, words = words), AutoEditOptions(
            cleanPauses = false, smartZoom = false, autoReframe = false, audioEnhancement = false,
        ))
        assertEquals(listOf("isso ficou muito bom!", "agora olha"), plan.captions.map { it.text })
        assertEquals("agora", plan.captions.last().emphasizedWord)
    }

    @Test fun effectBudgetAndSpacingStayModerateAndDeterministic() {
        val motion = (1..20).map { MotionSample(it * 500_000L, .95f, .8f, .3f) }
        val audio = (0 until 100).map { AudioSample(it * 100_000L, (it + 1) * 100_000L, .2f, .9f) }
        val input = ClipAnalysis("clip", 10_000_000L, audio, motion, hasAudio = true)
        val options = AutoEditOptions(style = AutoEditStyle.GAMING, intensity = AutoEditIntensity.STRONG, captions = false)
        val first = AutoEditPlanner.plan(input, options)
        val second = AutoEditPlanner.plan(input, options)
        assertEquals(first, second)
        assertTrue(first.effects.size <= 3)
        assertTrue(first.zooms.zipWithNext().all { (a, b) -> b.timeUs - a.timeUs >= 1_450_000L })
    }

    @Test fun impactAtFinalFrameNeverCreatesOutOfRangeEvents() {
        val duration = 5_000_000L
        val input = ClipAnalysis("clip", duration, motion = listOf(
            MotionSample(1_000_000L, .1f), MotionSample(duration, 1f, .8f, .5f),
        ))
        val plan = AutoEditPlanner.plan(input, AutoEditOptions(style = AutoEditStyle.GAMING,
            intensity = AutoEditIntensity.STRONG, captions = false, cleanPauses = false, audioEnhancement = false))
        assertTrue(plan.zooms.all { it.timeUs + it.durationUs <= duration })
        assertTrue(plan.effects.all { it.endUs <= duration })
    }
}
