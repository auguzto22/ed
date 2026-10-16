package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.tracking.LayerGeometry
import com.termex.replay15.editor.tracking.StickerTrackingBinder
import com.termex.replay15.editor.transform.TransformKeyframe
import com.termex.replay15.editor.transform.TransformState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sistema de stickers no jeito do CapCut: animações, dividir/duplicar/camadas e rastreamento. */
class StickerCapCutSystemTest {
    private val second = 1_000_000L

    private fun sticker(id: String = newId(), start: Long = second, end: Long = 5 * second, animations: StickerAnimations = StickerAnimations()) =
        StickerClip(id = id, uri = "content://image/$id", name = "$id.png", startUs = start, endUs = end, x = .3f, y = .6f,
            animations = animations)

    private fun project(vararg stickers: StickerClip) = Project(stickers = stickers.toList())

    private fun reload(project: Project): Project {
        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }
        return ProjectCodec.read(ByteArrayInputStream(bytes.toByteArray()))
    }

    // ── Animações ──────────────────────────────────────────────────────────────────

    @Test fun noAnimationIsIdentity() {
        val s = sticker()
        assertEquals(StickerMotionState.IDENTITY, StickerMotion.evaluate(s, 2 * second))
        assertTrue(!StickerMotion.isAnimated(s))
    }

    @Test fun fadeInStartsHiddenAndEndsVisible() {
        val s = sticker(animations = StickerAnimations(enter = StickerAnimationType.IN_FADE, enterUs = 500_000L))
        assertEquals(0f, StickerMotion.evaluate(s, s.startUs).alpha, 1e-4f)
        assertEquals(1f, StickerMotion.evaluate(s, s.startUs + 500_000L).alpha, 1e-4f)
        assertEquals(1f, StickerMotion.evaluate(s, s.endUs - 1).alpha, 1e-4f)
        assertTrue(StickerMotion.isAnimated(s))
    }

    @Test fun slideOutLeftMovesLeftAndFadesAtTheEnd() {
        val s = sticker(animations = StickerAnimations(exit = StickerAnimationType.OUT_SLIDE_LEFT, exitUs = 400_000L))
        val before = StickerMotion.evaluate(s, s.endUs - 500_000L)
        val atEnd = StickerMotion.evaluate(s, s.endUs)
        assertEquals(0f, before.offsetX, 1e-5f)
        assertTrue(atEnd.offsetX < -.2f)
        assertEquals(0f, atEnd.alpha, 1e-4f)
    }

    @Test fun enterAndExitShareShortStickers() {
        // 1 s de sticker com 1 s de entrada e 1 s de saída: cada uma fica com metade.
        val s = sticker(end = 2 * second, animations = StickerAnimations(
            enter = StickerAnimationType.IN_FADE, enterUs = second, exit = StickerAnimationType.OUT_FADE, exitUs = second))
        assertEquals(1f, StickerMotion.evaluate(s, s.startUs + 500_000L).alpha, 1e-3f)
        assertTrue(StickerMotion.evaluate(s, s.startUs + 250_000L).alpha in .1f..1f)
        assertEquals(0f, StickerMotion.evaluate(s, s.endUs).alpha, 1e-4f)
    }

    @Test fun loopIsExclusiveWithEnterAndExit() {
        val withBoth = StickerAnimations().with(StickerAnimationType.IN_POP).with(StickerAnimationType.OUT_FADE)
        assertEquals(StickerAnimationType.IN_POP, withBoth.enter)
        assertEquals(StickerAnimationType.OUT_FADE, withBoth.exit)
        val looped = withBoth.with(StickerAnimationType.LOOP_PULSE)
        assertEquals(StickerAnimationType.NONE, looped.enter)
        assertEquals(StickerAnimationType.NONE, looped.exit)
        assertEquals(StickerAnimationType.LOOP_PULSE, looped.loop)
        assertEquals(StickerAnimationType.NONE, looped.with(StickerAnimationType.IN_FADE).loop)
    }

    @Test fun loopRepeatsEveryPeriod() {
        val s = sticker(animations = StickerAnimations(loop = StickerAnimationType.LOOP_SWING, loopUs = 800_000L))
        val a = StickerMotion.evaluate(s, s.startUs + 200_000L)
        val b = StickerMotion.evaluate(s, s.startUs + 1_000_000L)
        assertEquals(a.rotation, b.rotation, 1e-3f)
        assertNotEquals(0f, a.rotation, 1e-3f)
    }

    @Test fun legacySingleAnimationKeepsTheOldLook() {
        val s = sticker().copy(animation = TextAnimation.POP)
        val start = StickerMotion.evaluate(s, s.startUs)
        assertEquals(0f, start.alpha, 1e-5f)
        // Fórmula antiga: .72 + .28 × overshoot(0), e overshoot(0) = 1.
        assertEquals(1f, start.scaleX, 1e-4f)
        // Abrir o painel converte para entrada + saída.
        val converted = StickerAnimations.fromLegacy(s.animations, s.animation)
        assertEquals(StickerAnimationType.IN_POP, converted.enter)
        assertEquals(StickerAnimationType.OUT_FADE, converted.exit)
    }

    @Test fun everyTypeEvaluatesToFiniteValues() {
        StickerAnimationType.entries.filter { it.kind != null }.forEach { type ->
            val s = sticker(animations = StickerAnimations().with(type))
            var t = s.startUs
            while (t <= s.endUs) {
                val m = StickerMotion.evaluate(s, t)
                assertTrue("$type", listOf(m.alpha, m.scaleX, m.scaleY, m.offsetX, m.offsetY, m.rotation).all { it.isFinite() })
                assertTrue("$type alpha", m.alpha in 0f..1f)
                t += 50_000L
            }
        }
    }

    @Test fun animationsSurviveSaveAndLoad() {
        val p = project(
            sticker("a", animations = StickerAnimations(enter = StickerAnimationType.IN_SPIRAL, enterUs = 700_000L,
                exit = StickerAnimationType.OUT_SINK, exitUs = 300_000L)),
            sticker("b", animations = StickerAnimations(loop = StickerAnimationType.LOOP_HEARTBEAT, loopUs = 1_500_000L)),
            sticker("c"),
        )
        assertEquals(p, reload(p))
        assertEquals(31, PROJECT_SCHEMA)
    }

    // ── Dividir, duplicar, espelhar, camadas ────────────────────────────────────────

    @Test fun splitCutsTimeKeyframesAndAnimations() {
        val keyed = sticker("s", animations = StickerAnimations(enter = StickerAnimationType.IN_POP, exit = StickerAnimationType.OUT_FADE))
            .copy(transformKeyframes = listOf(
                TransformKeyframe(second, TransformState(x = .2f, y = .5f)),
                TransformKeyframe(5 * second, TransformState(x = .6f, y = .5f)),
            ))
        val (next, secondId) = StickerEditing.split(project(keyed), "s", 3 * second)!!
        assertEquals(2, next.stickers.size)
        val (first, second) = next.stickers
        assertEquals("s", first.id); assertEquals(secondId, second.id)
        assertEquals(3 * this.second, first.endUs); assertEquals(3 * this.second, second.startUs)
        assertEquals(StickerAnimationType.IN_POP, first.animations.enter)
        assertEquals(StickerAnimationType.NONE, first.animations.exit)
        assertEquals(StickerAnimationType.NONE, second.animations.enter)
        assertEquals(StickerAnimationType.OUT_FADE, second.animations.exit)
        // Sem pulo no corte: as duas partes têm o mesmo lugar no ponto de divisão.
        assertEquals(first.transformAt(3 * this.second).x, second.transformAt(3 * this.second).x, 1e-4f)
        assertEquals(keyed.transformAt(3 * this.second).x, second.transformAt(3 * this.second).x, 1e-4f)
        assertEquals(.6f, second.transformAt(5 * this.second).x, 1e-4f)
    }

    @Test fun splitRefusesNearTheEdges() {
        assertNull(StickerEditing.split(project(sticker("s")), "s", second + 50_000L))
        assertNull(StickerEditing.split(project(sticker("s")), "s", 9 * second))
    }

    @Test fun splitShiftsLocalMaskKeyframes() {
        val mask = MaskState.default().copy(keyframes = listOf(
            MaskKeyframe(0L, .5f, .5f, .5f, .5f, 0f, 0f, 0f),
            MaskKeyframe(4 * second, .5f, .5f, .9f, .5f, 0f, 0f, 0f),
        ))
        val (next, _) = StickerEditing.split(project(sticker("s").copy(mask = mask)), "s", 3 * second)!!
        val right = next.stickers[1].mask!!
        // O sticker começa em 1 s: o corte em 3 s é o tempo local 2 s, que vira o novo zero.
        assertEquals(0L, right.keyframes.first().timeUs)
        assertEquals(2 * second, right.keyframes.last().timeUs)
        assertEquals(mask.at(2 * second).width, right.at(0L).width, 1e-4f)
        assertEquals(.9f, right.at(2 * second).width, 1e-4f)
    }

    @Test fun duplicateSitsAboveTheOriginal() {
        val (next, copyId) = StickerEditing.duplicate(project(sticker("a"), sticker("b")), "a")!!
        assertEquals(listOf("a", copyId, "b"), next.stickers.map { it.id })
        val copy = next.stickers[1]
        assertEquals(.34f, copy.x, 1e-4f)
        assertEquals(next.stickers[0].startUs, copy.startUs)
    }

    @Test fun limitsAreRespected() {
        val full = Project(stickers = List(MAX_STICKERS) { sticker("s$it") })
        assertNull(StickerEditing.duplicate(full, "s0"))
        assertNull(StickerEditing.split(full, "s0", 3 * second))
    }

    @Test fun mirrorTogglesFlip() {
        val once = StickerEditing.mirror(project(sticker("a")), "a")
        assertTrue(once.stickers.single().flip)
        assertTrue(!StickerEditing.mirror(once, "a").stickers.single().flip)
    }

    @Test fun layerOrderFrontToBack() {
        val p = project(sticker("back"), sticker("mid"), sticker("front"))
        assertEquals(listOf("front", "mid", "back"), StickerEditing.frontToBack(p).map { it.id })
        val reordered = StickerEditing.reorder(p, listOf("back", "front", "mid"))
        assertEquals(listOf("mid", "front", "back"), reordered.stickers.map { it.id })
        assertEquals(listOf("mid", "back", "front"), StickerEditing.step(p, "back", 1).stickers.map { it.id })
        assertEquals(listOf("mid", "front", "back"), StickerEditing.bringToFront(p, "back").stickers.map { it.id })
        assertEquals(listOf("front", "back", "mid"), StickerEditing.sendToBack(p, "front").stickers.map { it.id })
        // Lista que não bate com os stickers do projeto não muda nada.
        assertEquals(p, StickerEditing.reorder(p, listOf("front")))
    }

    @Test fun basicPanelWritesKeyframeAtCursorWhenAnimated() {
        val plain = sticker("a")
        val moved = StickerEditing.applyTransform(plain, TransformState(x = .7f, y = .2f, scaleX = 2f, scaleY = 2f, rotation = 30f, opacity = .5f), 2 * second)
        assertEquals(.7f, moved.x, 1e-5f); assertEquals(2f, moved.scale, 1e-5f); assertEquals(30f, moved.rotation, 1e-5f)
        val keyed = plain.copy(transformKeyframes = listOf(TransformKeyframe(second, TransformState())))
        val withKey = StickerEditing.applyTransform(keyed, TransformState(x = .9f), 3 * second)
        assertEquals(2, withKey.transformKeyframes.size)
        assertEquals(.9f, withKey.transformAt(3 * second).x, 1e-4f)
    }

    // ── Rastreamento ────────────────────────────────────────────────────────────────

    @Test fun sourceToCanvasInvertsCanvasToSource() {
        val geometries = listOf(
            LayerGeometry(1920, 1080, CropRect(), 0, false, 1f, 0f, 0f, 0f, 1080, 1920, CanvasFill.FIT),
            LayerGeometry(1920, 1080, CropRect(.1f, .2f, .9f, .8f), 90, true, 1.5f, .1f, -.05f, 12f, 1080, 1920, CanvasFill.FILL),
        )
        geometries.forEach { g ->
            listOf(.2f to .3f, .5f to .5f, .8f to .65f).forEach { (cx, cy) ->
                val (sx, sy) = g.canvasToSource(cx, cy)
                val (bx, by) = g.sourceToCanvas(sx, sy)
                assertEquals(cx, bx, 1e-4f); assertEquals(cy, by, 1e-4f)
            }
        }
    }

    @Test fun trackedStickerFollowsTheObjectFromWhereItWasPlaced() {
        val identity = LayerGeometry(1000, 1000, CropRect(), 0, false, 1f, 0f, 0f, 0f, 1000, 1000, CanvasFill.FILL)
        val track = TrackingTrack("t1", listOf(
            TrackingPoint(10 * second, .5f, .5f, .1f, .1f, 1f),
            TrackingPoint(11 * second, .6f, .45f, .1f, .1f, 1f),
            TrackingPoint(12 * second, .7f, .4f, .2f, .2f, 1f),
        ))
        // Clipe começa na timeline em 0 com inUs = 10 s, velocidade 1.
        val placed = sticker("s", start = 0L, end = 3 * second)
        val bound = StickerTrackingBinder.bind(placed, track,
            timelineAt = { source -> source - 10 * second }, geometryAt = { identity }, followScale = true)
        assertNotNull(bound)
        val keys = bound!!.transformKeyframes
        assertEquals(3, keys.size)
        // Âncora: onde o sticker estava (.3, .6); depois anda o mesmo que o objeto.
        assertEquals(.3f, bound.transformAt(0L).x, 1e-4f)
        assertEquals(.4f, bound.transformAt(second).x, 1e-4f)
        assertEquals(.5f, bound.transformAt(2 * second).x, 1e-4f)
        assertEquals(.5f, bound.transformAt(2 * second).y, 1e-4f)
        // O objeto dobrou de tamanho: o sticker também.
        assertEquals(2f, bound.transformAt(2 * second).scaleX, 1e-3f)
    }

    @Test fun trackingOutsideTheStickerIsIgnored() {
        val identity = LayerGeometry(1000, 1000, CropRect(), 0, false, 1f, 0f, 0f, 0f, 1000, 1000, CanvasFill.FILL)
        val track = TrackingTrack("t1", listOf(TrackingPoint(0L, .5f, .5f, .1f, .1f, 1f), TrackingPoint(second, .6f, .5f, .1f, .1f, 1f)))
        val late = sticker("s", start = 5 * second, end = 6 * second)
        assertNull(StickerTrackingBinder.bind(late, track, { it }, { identity }))
    }
}
