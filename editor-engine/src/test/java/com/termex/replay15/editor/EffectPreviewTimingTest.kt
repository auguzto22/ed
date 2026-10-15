package com.termex.replay15.editor

import com.termex.replay15.editor.assets.EffectInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.engine.ActiveClipResolver
import com.termex.replay15.editor.preview.engine.EffectTiming
import com.termex.replay15.editor.preview.engine.RenderStateEvaluator
import org.junit.Assert.*
import org.junit.Test

/**
 * The preview used to send uProgress = 0 and the absolute source time as uTime to every effect,
 * so effects driven by progress stood still and time-driven ones differed from the export.
 * These pin the preview to the export rule (PackageEffect).
 */
class EffectPreviewTimingTest {
    private fun video(id: String, effects: List<EffectInstance> = emptyList()) = VideoClip(
        id = id, uri = "content://$id", name = id, sourceUs = 10 * SECOND, width = 640, height = 360, effects = effects)

    private fun render(project: Project, timeUs: Long) =
        RenderStateEvaluator.evaluate(ActiveClipResolver(project).resolve(timeUs), project, 1, 1, 640, 360)

    @Test fun rangedEffectCountsFromItsOwnStart() {
        val value = EffectTiming.evaluate(localUs = 4 * SECOND, startUs = 2 * SECOND, endUs = 6 * SECOND,
            layerTimeUs = 4 * SECOND, layerDurationUs = 10 * SECOND)
        assertEquals(2f, value.timeSeconds, 1e-4f)
        assertEquals(.5f, value.progress, 1e-4f)
    }

    @Test fun unrangedEffectFollowsItsLayer() {
        val value = EffectTiming.evaluate(localUs = 7 * SECOND, startUs = null, endUs = null,
            layerTimeUs = 2_500_000L, layerDurationUs = 10 * SECOND)
        assertEquals(2.5f, value.timeSeconds, 1e-4f)
        assertEquals(.25f, value.progress, 1e-4f)
    }

    @Test fun progressStaysInsideZeroToOne() {
        val before = EffectTiming.evaluate(SECOND, 2 * SECOND, 6 * SECOND, SECOND, 10 * SECOND)
        val after = EffectTiming.evaluate(9 * SECOND, 2 * SECOND, 6 * SECOND, 9 * SECOND, 10 * SECOND)
        assertEquals(0f, before.progress, 0f)
        assertEquals(0f, before.timeSeconds, 0f)
        assertEquals(1f, after.progress, 0f)
    }

    @Test fun clipEffectInPreviewAdvancesWithThePlayhead() {
        val effect = EffectInstance("fx", "recly_bloom", startTimeUs = 2 * SECOND, endTimeUs = 6 * SECOND)
        val project = Project(videos = listOf(video("a", listOf(effect))))
        val early = render(project, 3 * SECOND).layers.single().effects.single()
        val middle = render(project, 4 * SECOND).layers.single().effects.single()
        assertEquals(.25f, early.progress, 1e-3f)
        assertEquals(.5f, middle.progress, 1e-3f)
        assertEquals(2f, middle.timeSeconds, 1e-3f)
    }

    @Test fun adjustmentEffectIsTimedByItsLayer() {
        val adjustment = AdjustmentClip(id = "adj", startUs = SECOND, endUs = 5 * SECOND,
            effects = listOf(EffectInstance("glow", "recly_glow")))
        val project = Project(videos = listOf(video("a")), adjustmentClips = listOf(adjustment))
        val effect = render(project, 3 * SECOND).layers.single().effects.single()
        assertEquals(.5f, effect.progress, 1e-3f)
        assertEquals(2f, effect.timeSeconds, 1e-3f)
    }
}
