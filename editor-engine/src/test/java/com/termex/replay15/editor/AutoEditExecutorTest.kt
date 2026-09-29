package com.termex.replay15.editor

import com.termex.replay15.editor.autoedit.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.history.ProjectHistory
import com.termex.replay15.editor.project.ProjectCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class AutoEditExecutorTest {
    private fun clip() = VideoClip(uri = "file:///clip.mp4", name = "clip", sourceUs = 10 * SECOND, width = 1920, height = 1080)

    @Test fun executionUsesNormalEditablePrimitivesAndOneUndoSnapshot() {
        val source = clip()
        val titleAfter = TextClip(text = "fim", startUs = 11 * SECOND, endUs = 12 * SECOND)
        val project = Project(videos = listOf(source), texts = listOf(titleAfter), export = ExportSettings.forSource(source))
        val plan = AutoEditPlan(
            clipId = source.id,
            sourceDurationUs = source.durationUs,
            options = AutoEditOptions(style = AutoEditStyle.GAMING, autoReframe = true),
            cuts = listOf(CutDecision(2 * SECOND, 3 * SECOND, .9f, "silencio")),
            captions = listOf(CaptionDecision(500_000, 1_500_000, "fala clara")),
            zooms = listOf(ZoomDecision(5 * SECOND, 250_000, 1.12f, .9f, "impacto")),
            reframes = listOf(ReframeDecision(4 * SECOND, -.08f, .02f, .8f, "acao")),
            effects = listOf(EffectDecision(5 * SECOND, 5_200_000, "recly_shake", .3f,
                mapOf("distance" to 5f), .9f, "impacto")),
            audio = AudioDecision(1.1f, 80_000, 120_000, "nivel"),
        )
        val edited = AutoEditExecutor.execute(project, 0, plan)
        assertEquals(2, edited.videos.size)
        assertEquals(9 * SECOND, edited.durationUs)
        assertTrue(edited.videos.flatMap { it.keyframes }.isNotEmpty())
        assertEquals(1, edited.videos.sumOf { it.effects.size })
        assertTrue(edited.texts.any { it.text == "fala clara" })
        assertTrue(edited.texts.any { it.text == "fim" && it.startUs == 10 * SECOND })
        assertEquals(9f / 16f, edited.aspect)
        assertEquals(CanvasFill.FILL, edited.canvasFill)

        val history = ProjectHistory(project)
        assertTrue(history.apply(edited))
        assertTrue(history.undo())
        assertEquals(project, history.current)

        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(edited, it) }.toByteArray()
        assertEquals(edited, ProjectCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test fun planDoesNotMutateOriginalProject() {
        val source = clip()
        val existingCaption = TextClip(text = "legenda existente", startUs = 0, endUs = SECOND, y = .8f)
        val original = Project(videos = listOf(source), texts = listOf(existingCaption))
        val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(captions = false, autoReframe = false),
            zooms = listOf(ZoomDecision(SECOND, 200_000, 1.08f, .8f, "enfase")))
        val edited = AutoEditExecutor.execute(original, 0, plan)
        assertTrue(original.videos.single().keyframes.isEmpty())
        assertTrue(edited.videos.single().keyframes.isNotEmpty())
        assertEquals(listOf(existingCaption), edited.texts)
    }

    @Test fun commonDurationAndFpsMatrixRemainsValid() {
        for (seconds in listOf(5, 15, 30, 60, 90)) for (fps in listOf(24f, 30f, 60f)) {
            val source = VideoClip(uri = "file:///matrix-$seconds-$fps.mp4", name = "matrix", sourceUs = seconds * SECOND,
                width = 1920, height = 1080, fps = fps)
            val project = Project(videos = listOf(source), export = ExportSettings.forSource(source))
            val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(captions = false, autoReframe = false),
                zooms = listOf(ZoomDecision(SECOND, 200_000, 1.06f, .8f, "enfase")))
            val edited = AutoEditExecutor.execute(project, 0, plan)
            assertEquals(seconds * SECOND, edited.durationUs)
            assertEquals(fps, edited.videos.single().fps)
        }
    }
}
