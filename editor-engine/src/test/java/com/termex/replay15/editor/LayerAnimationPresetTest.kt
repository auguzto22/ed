package com.termex.replay15.editor

import com.termex.replay15.editor.animation.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.transform.LayerStateEvaluator
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Presets da biblioteca (inclusive pacotes importados) em textos, legendas e stickers. */
class LayerAnimationPresetTest {
    private fun preset(category: AnimationCategory, points: List<AnimationPoint>) =
        AnimationPreset("test", 1, "Test", category, points, "Test license")
    private fun text() = TextClip(text = "Oi", startUs = 4 * SECOND, endUs = 8 * SECOND, x = .5f, y = .7f, scale = 1.5f)
    private fun sticker() = StickerClip(uri = "file:///s.png", name = "S", startUs = 2 * SECOND, endUs = 6 * SECOND)
    private fun at(text: TextClip, timeUs: Long) = LayerStateEvaluator.evaluate(text, timeUs, realtime = false).transform

    @Test fun entranceOnTextStartsAtTheTextAndEndsOnItsOwnPose() {
        val p = preset(AnimationCategory.IN, listOf(AnimationPoint(0f, opacity = 0f, zoom = .5f, x = -.2f), AnimationPoint(1f)))
        val edited = p.applyTo(text(), SECOND)
        assertEquals(listOf(4 * SECOND, 5 * SECOND), edited.transformKeyframes.map { it.timeUs })
        val start = at(edited, 4 * SECOND)
        assertEquals(0f, start.opacity, 1e-5f)
        assertEquals(.75f, start.scaleX, 1e-5f)
        assertEquals(.3f, start.x, 1e-5f)
        val end = at(edited, 6 * SECOND)
        assertEquals(1f, end.opacity, 1e-5f)
        assertEquals(1.5f, end.scaleX, 1e-5f)
        assertEquals(.5f, end.x, 1e-5f)
    }

    @Test fun exitOnStickerOnlyMovesAtTheEnd() {
        val p = preset(AnimationCategory.OUT, listOf(AnimationPoint(0f), AnimationPoint(1f, opacity = 0f)))
        val edited = p.applyTo(sticker(), SECOND)
        assertEquals(1f, LayerStateEvaluator.evaluate(edited, 3 * SECOND, realtime = false).transform.opacity, 1e-5f)
        assertEquals(0f, LayerStateEvaluator.evaluate(edited, 6 * SECOND, realtime = false).transform.opacity, 1e-5f)
        assertEquals(2 * SECOND, edited.transformKeyframes.first().timeUs)
    }

    @Test fun loopCoversTheWholeLayerAndRespectsTheKeyframeLimit() {
        val p = preset(AnimationCategory.LOOP, listOf(AnimationPoint(0f), AnimationPoint(.5f, zoom = 1.2f), AnimationPoint(1f)))
        val edited = p.applyTo(text(), SECOND)
        assertEquals(4 * SECOND, edited.transformKeyframes.first().timeUs)
        assertEquals(8 * SECOND, edited.transformKeyframes.last().timeUs)
        assertEquals(1.5f * 1.2f, at(edited, 4_500_000).scaleX, 1e-4f)
        val long = text().copy(endUs = 4 * SECOND + 1000 * SECOND)
        assertThrows(IllegalArgumentException::class.java) { p.applyTo(long, SECOND) }
    }

    @Test fun applyingTwiceDoesNotStack() {
        val p = preset(AnimationCategory.IN, listOf(AnimationPoint(0f, opacity = 0f), AnimationPoint(1f)))
        val once = p.applyTo(text(), SECOND)
        assertEquals(once, p.applyTo(once, SECOND))
    }

    @Test fun keyframesOutsideTheWindowArePreserved() {
        val manual = com.termex.replay15.editor.transform.TransformKeyframe(7 * SECOND, text().baseTransform.copy(rotation = 20f))
        val p = preset(AnimationCategory.IN, listOf(AnimationPoint(0f, opacity = 0f), AnimationPoint(1f)))
        val edited = p.applyTo(text().copy(transformKeyframes = listOf(manual)), SECOND)
        assertTrue(manual in edited.transformKeyframes)
    }

    @Test fun animatedLayersSurviveSaving() {
        val p = preset(AnimationCategory.IN, listOf(AnimationPoint(0f, x = -.3f, easing = Easing.SPRING), AnimationPoint(1f)))
        val project = Project(texts = listOf(p.applyTo(text(), SECOND)), stickers = listOf(p.applyTo(sticker(), SECOND)))
        val out = ByteArrayOutputStream(); ProjectCodec.write(project, out)
        assertEquals(project, ProjectCodec.read(out.toByteArray().inputStream()))
    }

    @Test fun everyPackagedAnimationAppliesToTextAndSticker() {
        val folder = listOf("src/main/assets/editor/animations", "editor-engine/src/main/assets/editor/animations")
            .map { java.io.File(it) }.first { it.isDirectory }
        val presets = folder.listFiles { f -> f.name.endsWith(".json") }!!.map { AnimationRepository.parse(it.readText()) }
        assertTrue(presets.size > 100)
        presets.forEach { preset ->
            assertTrue(preset.id, preset.applyTo(text(), SECOND).transformKeyframes.isNotEmpty())
            assertTrue(preset.id, preset.applyTo(sticker(), SECOND).transformKeyframes.isNotEmpty())
        }
    }
}
