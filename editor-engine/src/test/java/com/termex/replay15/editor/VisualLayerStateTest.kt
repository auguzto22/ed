package com.termex.replay15.editor

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.engine.ActiveClipResolver
import com.termex.replay15.editor.preview.engine.RenderStateEvaluator
import com.termex.replay15.editor.transform.LayerStateEvaluator
import com.termex.replay15.editor.transform.RealtimeProjectState
import com.termex.replay15.editor.transform.TransformKeyframe as LayerKeyframe
import com.termex.replay15.editor.transform.TransformState
import com.termex.replay15.editor.project.ProjectCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VisualLayerStateTest {
    private fun video(id: String) = VideoClip(id = id, uri = "content://$id", name = id,
        sourceUs = 10 * SECOND, width = 640, height = 360)

    @Test fun imageUsesHalfOpenIntervalAndRepeatedResolvesAgree() {
        val sticker = StickerClip(id = "image", uri = "content://image", name = "image",
            startUs = 2 * SECOND, endUs = 8 * SECOND)
        val project = Project(videos = listOf(video("a")), stickers = listOf(sticker))
        for (time in listOf(0L, SECOND, 2 * SECOND, 3 * SECOND, 5 * SECOND, 7_900_000L, 8 * SECOND)) {
            val first = RenderStateEvaluator.evaluate(ActiveClipResolver(project).resolve(time), project, 1, 1, 640, 360)
            val second = RenderStateEvaluator.evaluate(ActiveClipResolver(project).resolve(time), project, 1, 1, 640, 360)
            assertEquals(first, second)
            assertEquals(time >= 2 * SECOND && time < 8 * SECOND, first.stickers.isNotEmpty())
        }
    }

    @Test fun keyframesInterpolateAndLastValuePersists() {
        val first = LayerKeyframe(2 * SECOND, TransformState(scaleX = 1f, scaleY = 1f))
        val last = LayerKeyframe(4 * SECOND, TransformState(scaleX = 2f, scaleY = 2f), easing = Easing.LINEAR)
        val text = TextClip(id = "text", text = "hello", startUs = 2 * SECOND, endUs = 10 * SECOND,
            transformKeyframes = listOf(first.copy(easing = Easing.LINEAR), last))
        assertEquals(1f, LayerStateEvaluator.evaluate(text, 2 * SECOND).transform.scaleX, .0001f)
        assertEquals(1.5f, LayerStateEvaluator.evaluate(text, 3 * SECOND).transform.scaleX, .0001f)
        for (time in listOf(7 * SECOND, 8 * SECOND, 9 * SECOND)) {
            val state = LayerStateEvaluator.evaluate(text, time)
            assertTrue(state.visible)
            assertEquals(2f, state.transform.scaleX, .0001f)
        }
    }

    @Test fun realtimeTransformIsResolvedOnceForPreviewAndHandles() {
        val image = StickerClip(id = "image", uri = "content://image", name = "image",
            startUs = 0, endUs = 10 * SECOND,
            transformKeyframes = listOf(LayerKeyframe(0, TransformState(x = .2f))))
        val project = Project(videos = listOf(video("a")), stickers = listOf(image))
        try {
            RealtimeProjectState.updateTransform(image.id, TransformState(x = .8f))
            val state = RenderStateEvaluator.evaluate(ActiveClipResolver(project).resolve(5 * SECOND), project, 1, 1, 640, 360)
            assertEquals(LayerStateEvaluator.evaluate(image, 5 * SECOND).transform.x, state.stickers.single().x, 0f)
            assertTrue(state.stickers.single().transformKeyframes.isEmpty())
        } finally { RealtimeProjectState.clear(image.id) }
    }

    @Test fun newlyInsertedTextIsVisibleAtItsStartAndTimingMovesImmediately() {
        val text = TextClip(id = "text", text = "hello", startUs = 4 * SECOND, endUs = 7 * SECOND)
        val project = Project(videos = listOf(video("a")), texts = listOf(text))
        val resolver = ActiveClipResolver(project)
        fun visibleAt(time: Long) = RenderStateEvaluator.evaluate(resolver.resolve(time), project, 1, 1, 640, 360)
            .textLayers.any { it.id == text.id && it.visible }
        assertFalse(visibleAt(4 * SECOND - 1))
        assertTrue(visibleAt(4 * SECOND))
        assertFalse(visibleAt(7 * SECOND))
        try {
            RealtimeProjectState.updateTiming(text.id, 5 * SECOND, 8 * SECOND)
            assertFalse(visibleAt(4 * SECOND))
            assertTrue(visibleAt(5 * SECOND))
        } finally { RealtimeProjectState.clear(text.id) }
    }

    @Test fun wordTimedCaptionCanBeEvaluatedWithoutChangingItsCueTiming() {
        val text = TextClip(id = "caption", text = "hello world", startUs = 2 * SECOND, endUs = 6 * SECOND,
            isCaption = true,
            wordCues = listOf(
                SubtitleWordCue("hello", 2 * SECOND, 3 * SECOND),
                SubtitleWordCue("world", 3 * SECOND, 4 * SECOND)))
        val project = Project(videos = listOf(video("a")), texts = listOf(text))

        val state = RenderStateEvaluator.evaluate(ActiveClipResolver(project).resolve(3 * SECOND), project, 1, 1, 640, 360)

        assertEquals(listOf(text.id), state.textLayers.map { it.id })
        assertEquals(text.wordCues, state.textLayers.single().source.wordCues)
    }

    @Test fun addingAndRemovingTransitionsNeverMovesCuts() {
        val base = Project(videos = listOf(video("a"), video("b")))
        for (duration in listOf(250_000L, 500_000L, SECOND)) {
            val transition = TransitionInstance("ab", "cross_dissolve", "a", "b", duration, easing = Easing.LINEAR)
            val with = base.withTransition(transition)
            assertEquals(20 * SECOND, with.durationUs)
            assertEquals(10 * SECOND, with.startOf(1))
            assertEquals(base.videos, with.videos)
            assertEquals(base, with.withoutTransition("a", "b"))
            val middle = ActiveClipResolver(with).resolve(10 * SECOND + duration / 2)
            assertEquals(listOf("a", "b"), middle.videos.map { it.clip.id })
            assertEquals(.5f, middle.transitions.single().progress, .0001f)
        }
    }

    @Test fun optionalDeviceFixtureKeepsAllVisualLayersAndTimelineDuration() {
        val folder = System.getenv("RECLY_VISUAL_FIXTURES")?.let { File(it).apply { mkdirs() } } ?: return
        val media = "file:///data/user/0/com.recly.editor/files/transition-diagnostics"
        val a = video("visual-a").copy(uri = "$media/a.mp4", sourceUs = 5 * SECOND, outUs = 5 * SECOND)
        val b = video("visual-b").copy(uri = "$media/b.mp4", sourceUs = 5 * SECOND, outUs = 5 * SECOND)
        val image = StickerClip(id = "visual-image", uri = "$media/overlay.png", name = "overlay",
            startUs = 2 * SECOND, endUs = 8 * SECOND, size = .3f,
            transformKeyframes = listOf(
                LayerKeyframe(2 * SECOND, TransformState(x = .3f, scaleX = 1f, scaleY = 1f), easing = Easing.LINEAR),
                LayerKeyframe(4 * SECOND, TransformState(x = .7f, scaleX = 1.5f, scaleY = 1.5f), easing = Easing.LINEAR)))
        val text = TextClip(id = "visual-text", text = "RECLY TESTE", startUs = 4 * SECOND, endUs = 9 * SECOND,
            transformKeyframes = listOf(LayerKeyframe(4 * SECOND, TransformState(y = .75f))))
        val project = Project(id = "visual-preview-diagnostic", name = "Diagnóstico visual 2026",
            videos = listOf(a, b), texts = listOf(text), stickers = listOf(image),
            transitions = listOf(TransitionInstance("visual-transition", "cross_dissolve", a.id, b.id, 500_000L)))
        assertEquals(10 * SECOND, project.durationUs)
        ProjectCodec.write(project, File(folder, "${project.id}.r15").outputStream())
    }
}
