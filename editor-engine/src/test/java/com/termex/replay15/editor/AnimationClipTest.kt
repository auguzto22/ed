package com.termex.replay15.editor

import com.termex.replay15.editor.core.EditorSessionController
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.history.ProjectHistory
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.render.RenderPlan
import com.termex.replay15.editor.transform.AnimationDelta
import com.termex.replay15.editor.transform.AnimationEvaluator
import com.termex.replay15.editor.transform.LayerStateEvaluator
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Balões de animação (Fase 3): migração, corte/ripple/mover, avaliação e conversão. */
class AnimationClipTest {
    private val s = 1_000_000L

    private fun clip(seconds: Long = 10, name: String = "clip") =
        VideoClip(uri = "content://video/$name", name = name, sourceUs = seconds * s, width = 1080, height = 1920)

    private fun write(project: Project) = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }.toByteArray()

    private fun animation(target: String, start: Long, end: Long, preset: MotionPreset = MotionPreset.ZOOM_IN,
        intensity: Float = 1f, easing: Easing = Easing.LINEAR) =
        AnimationClip(targetId = target, startUs = start, endUs = end, preset = preset, intensity = intensity, easing = easing)

    // ---- migração -------------------------------------------------------------------------

    @Test fun schemaIs31() { assertEquals(31, PROJECT_SCHEMA) }

    @Test fun projectSavedBeforeSchema31OpensIdentical() {
        val project = Project(videos = listOf(clip(), clip(name = "b")), texts = listOf(TextClip(text = "oi", startUs = 0, endUs = s)))
        // Um arquivo do schema 30 é o mesmo fluxo sem a seção final de animações (contagem 0, 4 bytes).
        val bytes = write(project).copyOf(write(project).size - 4)
        java.nio.ByteBuffer.wrap(bytes).putInt(4, 30)
        val reopened = ProjectCodec.read(bytes.inputStream())
        assertEquals(project, reopened)
        assertTrue(reopened.animationClips.isEmpty())
    }

    @Test fun animationsRoundTripThroughTheCodec() {
        val a = clip()
        val project = Project(videos = listOf(a), animationClips = listOf(
            animation(a.id, s, 3 * s, MotionPreset.SHAKE, 1.5f, Easing.EASE_IN_OUT),
            animation(a.id, 4 * s, 6 * s, MotionPreset.KEN_BURNS_OUT_LEFT, .85f, Easing.SMOOTH)))
        assertEquals(project, ProjectCodec.read(write(project).inputStream()))
    }

    // ---- corte -----------------------------------------------------------------------------

    @Test fun splitInsideABalloonDividesItBetweenBothHalves() {
        val a = clip()
        val split = Project(videos = listOf(a), animationClips = listOf(animation(a.id, 2 * s, 6 * s))).split(0, 4 * s)
        val (left, right) = split.videos
        val onLeft = split.animationClips.single { it.targetId == left.id }
        val onRight = split.animationClips.single { it.targetId == right.id }
        assertEquals(2 * s to 4 * s, onLeft.startUs to onLeft.endUs)
        assertEquals(0L to 2 * s, onRight.startUs to onRight.endUs)
        // Na timeline os dois pedaços continuam juntos onde o balão original estava.
        assertEquals(2 * s until 4 * s, AnimationEditing.absoluteWindow(split, onLeft))
        assertEquals(4 * s until 6 * s, AnimationEditing.absoluteWindow(split, onRight))
    }

    @Test fun splitBeforeOrAfterAMoveKeepsTheWholeBalloonOnItsHalf() {
        val a = clip()
        val base = Project(videos = listOf(a), animationClips = listOf(animation(a.id, 5 * s, 8 * s)))
        val before = base.split(0, 2 * s)
        assertEquals(before.videos[1].id, before.animationClips.single().targetId)
        assertEquals(3 * s to 6 * s, before.animationClips.single().let { it.startUs to it.endUs })
        val after = base.split(0, 9 * s)
        assertEquals(after.videos[0].id, after.animationClips.single().targetId)
        assertEquals(5 * s to 8 * s, after.animationClips.single().let { it.startUs to it.endUs })
    }

    @Test fun splitOfAnOverlayClipDividesItsBalloons() {
        val overlay = clip(8, "overlay")
        val track = VideoTrack(clips = listOf(TimedVideoClip(2 * s, overlay)))
        val project = Project(videos = listOf(clip()), videoTracks = listOf(track),
            animationClips = listOf(animation(overlay.id, 1 * s, 5 * s)))
        val split = TrackEditing.split(project, track.id, overlay.id, 5 * s)
        val pieces = split.videoTracks.single().clips
        assertEquals(2, pieces.size)
        assertEquals(2, split.animationClips.size)
        assertEquals(setOf(pieces[0].clip.id, pieces[1].clip.id), split.animationClips.map { it.targetId }.toSet())
    }

    // ---- ripple / mover / sanitizar -------------------------------------------------------

    @Test fun deletingAnEarlierClipRipplesTheBalloonWithItsClip() {
        val first = clip(5, "first"); val second = clip(5, "second")
        val project = Project(videos = listOf(first, second), animationClips = listOf(animation(second.id, 1 * s, 3 * s)))
        assertEquals(6 * s until 8 * s, AnimationEditing.absoluteWindow(project, project.animationClips.single()))
        val rippled = project.copy(videos = listOf(second)).withSanitizedAnimations()
        assertEquals(1 * s until 3 * s, AnimationEditing.absoluteWindow(rippled, rippled.animationClips.single()))
    }

    @Test fun deletingTheTargetRemovesItsBalloons() {
        val a = clip(); val b = clip(name = "b")
        val project = Project(videos = listOf(a, b), animationClips = listOf(animation(a.id, 0, s), animation(b.id, 0, s)))
        val result = project.copy(videos = listOf(b)).withSanitizedAnimations()
        assertEquals(listOf(b.id), result.animationClips.map { it.targetId })
    }

    @Test fun shorteningTheTargetClampsTheBalloon() {
        val a = clip(10)
        val project = Project(videos = listOf(a), animationClips = listOf(animation(a.id, 4 * s, 9 * s)))
        val trimmed = project.copy(videos = listOf(a.copy(outUs = 7 * s))).withSanitizedAnimations()
        assertEquals(7 * s, trimmed.animationClips.single().endUs)
        val gone = project.copy(videos = listOf(a.copy(outUs = 4 * s))).withSanitizedAnimations()
        assertTrue(gone.animationClips.isEmpty())
    }

    @Test fun movingAnOverlayClipMovesItsBalloonOnTheTimeline() {
        val overlay = clip(8, "overlay")
        val track = VideoTrack(clips = listOf(TimedVideoClip(2 * s, overlay)))
        val project = Project(videos = listOf(clip(20)), videoTracks = listOf(track),
            animationClips = listOf(animation(overlay.id, 1 * s, 3 * s)))
        assertEquals(3 * s until 5 * s, AnimationEditing.absoluteWindow(project, project.animationClips.single()))
        val moved = TrackEditing.move(project, track.id, overlay.id, 6 * s)
        assertEquals(7 * s until 9 * s, AnimationEditing.absoluteWindow(moved, moved.animationClips.single()))
    }

    @Test fun movingOrStretchingTheBalloonStaysInsideItsTarget() {
        val a = clip(10)
        val project = Project(videos = listOf(a), animationClips = listOf(animation(a.id, 2 * s, 4 * s)))
        val id = project.animationClips.single().id
        val moved = AnimationEditing.setAbsoluteWindow(project, id, 7 * s, 9 * s).animationClips.single()
        assertEquals(7 * s to 9 * s, moved.startUs to moved.endUs)
        val stretched = AnimationEditing.setAbsoluteWindow(project, id, 2 * s, 30 * s).animationClips.single()
        assertEquals(10 * s, stretched.endUs)
        val tiny = AnimationEditing.setAbsoluteWindow(project, id, 5 * s, 5 * s).animationClips.single()
        assertEquals(MIN_ANIMATION_US, tiny.durationUs)
    }

    @Test fun createNearTheEndSlidesBackInsteadOfOverflowing() {
        val a = clip(10)
        val (project, created) = AnimationEditing.create(Project(videos = listOf(a)), a.id, 9 * s, MotionPreset.ZOOM_IN)!!
        assertEquals(DEFAULT_ANIMATION_US, created.durationUs)
        assertEquals(10 * s, created.endUs)
        assertEquals(1, project.animationClips.size)
    }

    // ---- avaliador -------------------------------------------------------------------------

    @Test fun zoomInIsHalfwayThereInTheMiddleOfTheBalloon() {
        val anim = animation("x", 2 * s, 4 * s, MotionPreset.ZOOM_IN, intensity = 1f, easing = Easing.LINEAR)
        assertEquals(1f, AnimationEvaluator.delta(anim, 2 * s).zoom, 1e-5f)
        assertEquals(1.1f, AnimationEvaluator.delta(anim, 3 * s).zoom, 1e-5f)
        // Fora da janela o balão não contribui com nada.
        assertTrue(AnimationEvaluator.delta(anim, 4 * s).isNeutral)
        assertTrue(AnimationEvaluator.delta(anim, s).isNeutral)
    }

    @Test fun balloonMultipliesManualZoomAndAddsOffset() {
        val manual = TransformKeyframe(sourceUs = 0, zoom = 1.5f, x = .1f)
        val pan = animation("x", 0, 4 * s, MotionPreset.PAN_LEFT, easing = Easing.LINEAR)
        // 25% do caminho: dx = 0.04 * (1 - 0.5) = 0.02.
        val delta = AnimationEvaluator.delta(pan, s)
        assertEquals(.02f, delta.dx, 1e-5f)
        val combined = AnimationEvaluator.apply(manual, delta + AnimationDelta(zoom = 1.1f))
        assertEquals(1.65f, combined.zoom, 1e-5f)
        assertEquals(.12f, combined.x, 1e-5f)
    }

    @Test fun twoActiveBalloonsStack() {
        val a = animation("x", 0, 4 * s, MotionPreset.ZOOM_IN, easing = Easing.LINEAR)
        val b = animation("x", 0, 4 * s, MotionPreset.ZOOM_IN, easing = Easing.LINEAR)
        val other = animation("y", 0, 4 * s, MotionPreset.ZOOM_IN, easing = Easing.LINEAR)
        val delta = AnimationEvaluator.delta(listOf(a, b, other), "x", 2 * s)
        assertEquals(1.1f * 1.1f, delta.zoom, 1e-5f)
    }

    @Test fun kenBurnsEndsBetween118And125ForTheAutoEditIntensities() {
        AutoEditIntensities.forEach { k ->
            val anim = animation("x", 0, 4 * s, MotionPreset.KEN_BURNS, intensity = k, easing = Easing.LINEAR)
            val end = AnimationEvaluator.delta(anim, 4 * s - 1).zoom
            assertTrue("zoom final $end", end in 1.18f..1.25f)
        }
    }

    @Test fun textZoomIsMultipliedOnlyInsideTheBalloon() {
        val text = TextClip(text = "oi", startUs = 10 * s, endUs = 20 * s, scale = 2f)
        val anim = animation(text.id, 2 * s, 4 * s, MotionPreset.ZOOM_IN, easing = Easing.LINEAR)
        val inside = LayerStateEvaluator.evaluate(text, 13 * s, realtime = false, animations = listOf(anim))
        assertEquals(2f * 1.1f, inside.transform.scaleX, 1e-4f)
        val outside = LayerStateEvaluator.evaluate(text, 16 * s, realtime = false, animations = listOf(anim))
        assertEquals(2f, outside.transform.scaleX, 1e-4f)
        // Sem balões o resultado é exatamente o de antes.
        assertEquals(LayerStateEvaluator.evaluate(text, 13 * s, realtime = false), LayerStateEvaluator.evaluate(text, 13 * s, realtime = false, animations = emptyList()))
    }

    @Test fun innerEdgesRampInsteadOfJumping() {
        val anim = animation("x", 2 * s, 4 * s, MotionPreset.ZOOM_IN, easing = Easing.LINEAR)
        val target = 10 * s
        // Meio do balão: rampa não interfere.
        assertEquals(1.1f, AnimationEvaluator.delta(anim, 3 * s, target).zoom, 1e-5f)
        // Último quadro antes do fim (que fica DENTRO do clipe): já voltou ao neutro, sem salto.
        assertEquals(1f, AnimationEvaluator.delta(anim, 4 * s - 1, target).zoom, 1e-3f)
        // Fim do balão = fim do clipe: aí há corte, então o valor vai até o fim.
        assertEquals(1.2f, AnimationEvaluator.delta(anim, 4 * s - 1, 4 * s).zoom, 1e-3f)
    }

    @Test fun zoomOutStartingInsideTheClipEasesIn() {
        val anim = animation("x", 2 * s, 4 * s, MotionPreset.ZOOM_OUT, easing = Easing.LINEAR)
        assertEquals(1f, AnimationEvaluator.delta(anim, 2 * s, 10 * s).zoom, 1e-5f)
        // Começo do alvo: sem rampa, começa já ampliado.
        val atStart = animation("x", 0, 2 * s, MotionPreset.ZOOM_OUT, easing = Easing.LINEAR)
        assertEquals(1.2f, AnimationEvaluator.delta(atStart, 0, 10 * s).zoom, 1e-5f)
    }

    // ---- aparar o início / velocidade ------------------------------------------------------

    private fun applied(before: Project, after: Project): Project =
        EditorSessionController(ProjectHistory(before)).also { assertTrue(it.apply(after)) }.project

    @Test fun trimmingTheHeadKeepsTheBalloonOnTheSameContent() {
        val a = clip(10)
        val before = Project(videos = listOf(a), animationClips = listOf(animation(a.id, 4 * s, 6 * s)))
        val after = applied(before, before.copy(videos = listOf(a.copy(inUs = 2 * s))))
        val moved = after.animationClips.single()
        assertEquals(2 * s to 4 * s, moved.startUs to moved.endUs)
        // Mesmo instante da mídia antes e depois.
        assertEquals(a.timeMap.sourceAt(4 * s), after.videos.single().timeMap.sourceAt(moved.startUs))
    }

    @Test fun trimmingAwayTheWholeBalloonRemovesIt() {
        val a = clip(10)
        val before = Project(videos = listOf(a), animationClips = listOf(animation(a.id, 1 * s, 3 * s)))
        assertTrue(applied(before, before.copy(videos = listOf(a.copy(inUs = 4 * s)))).animationClips.isEmpty())
    }

    @Test fun speedingUpTheClipCompressesTheBalloon() {
        val a = clip(10)
        val before = Project(videos = listOf(a), animationClips = listOf(animation(a.id, 4 * s, 6 * s)))
        val moved = applied(before, before.copy(videos = listOf(a.copy(speed = 2f)))).animationClips.single()
        assertEquals(2 * s to 3 * s, moved.startUs to moved.endUs)
    }

    @Test fun trimmingOnlyTheTailLeavesTheBalloonAlone() {
        val a = clip(10)
        val before = Project(videos = listOf(a), animationClips = listOf(animation(a.id, 4 * s, 6 * s)))
        val kept = applied(before, before.copy(videos = listOf(a.copy(outUs = 8 * s)))).animationClips.single()
        assertEquals(4 * s to 6 * s, kept.startUs to kept.endUs)
    }

    // ---- export ----------------------------------------------------------------------------

    @Test fun animatedPiecesAreNotCoalescedInTheRenderPlan() {
        val split = Project(videos = listOf(clip())).split(0, 4 * s)
        val placed = listOf(TimedVideoClip(0, split.videos[0]), TimedVideoClip(split.videos[0].durationUs, split.videos[1]))
        assertEquals("sem balão, pedaços idênticos se fundem", 1, RenderPlan.coalesce(placed).size)
        assertEquals(2, RenderPlan.coalesce(placed, setOf(split.videos[1].id)).size)
    }

    // ---- converter em keyframes -----------------------------------------------------------

    @Test fun convertingToKeyframesReproducesTheMovementAndRemovesTheBalloon() {
        val a = clip(10)
        val anim = animation(a.id, 2 * s, 6 * s, MotionPreset.ZOOM_IN, easing = Easing.LINEAR)
        val project = Project(videos = listOf(a), animationClips = listOf(anim))
        val converted = AnimationKeyframes.convert(project, anim.id)!!
        assertTrue(converted.animationClips.isEmpty())
        val video = converted.videos.single()
        assertTrue(video.keyframes.isNotEmpty())
        assertEquals(1.1f, video.transformAt(4 * s).zoom, 1e-3f)
        assertEquals(1f, video.transformAt(s).zoom, 1e-3f)
        assertEquals("depois do fim volta ao neutro", 1f, video.transformAt(8 * s).zoom, 1e-3f)
    }

    private companion object {
        val AutoEditIntensities = com.termex.replay15.editor.autoedit.AutoEditExecutor.BROLL_INTENSITIES
    }
}
