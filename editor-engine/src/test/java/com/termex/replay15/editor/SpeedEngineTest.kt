package com.termex.replay15.editor

import androidx.media3.common.util.SpeedProviderUtil
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import com.termex.replay15.editor.render.ClipSpeedProvider
import com.termex.replay15.editor.speed.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * O motor de velocidade em Rust visto do Kotlin (JNI): curvas com easing, presets, consistência
 * com o Media3, persistência do schema 31 e reajuste de legendas/itens do projeto.
 */
class SpeedEngineTest {
    private fun clip(curve: List<SpeedPoint> = emptyList(), speed: Float = 1f, inUs: Long = 0, outUs: Long = 10 * SECOND) =
        VideoClip(uri = "content://video/1", name = "Clip", sourceUs = 10 * SECOND, width = 1920, height = 1080,
            inUs = inUs, outUs = outUs, speed = speed, speedCurve = curve)

    private val eased = listOf(
        SpeedPoint(0, 1f, Easing.SMOOTH), SpeedPoint(2 * SECOND, .1f, Easing.EASE_OUT),
        SpeedPoint(4 * SECOND, 8f, Easing.BEZIER, CubicBezier(.1f, .8f, .3f, 1f)),
        SpeedPoint(6 * SECOND, .5f, Easing.HOLD), SpeedPoint(8 * SECOND, 3f, Easing.EASE_IN_OUT), SpeedPoint(10 * SECOND, 1f),
    )

    @Test fun easedCurvesAreMonotonicInvertibleAndContinuous() {
        val c = clip(eased, inUs = 333_333, outUs = 9_876_543)
        var previous = -1L
        for (t in 0..c.durationUs step 997) {
            val s = c.timeMap.sourceAt(t)
            assertTrue(s >= previous); previous = s
            assertTrue("t=$t", abs(t - c.timeMap.timelineAt(s)) <= 12)
        }
        val range = c.timeMap.speedRange()
        assertEquals(.1f, range.start, 1e-4f)
        assertEquals(8f, range.endInclusive, 1e-4f)
        // A curva amostrada para a interface é o mesmo valor que o motor integra.
        val samples = c.timeMap.sampleCurve(0, 10 * SECOND, 11)
        assertEquals(1f, samples[0], 1e-6f)
        assertEquals(.1f, samples[2], 1e-6f)
        assertEquals(c.timeMap.curveSpeedAtSource(3 * SECOND), samples[3], 1e-6f)
    }

    @Test fun previewAndExportMapEveryFrameToTheSameSourceTime() {
        // O Media3 constrói seu próprio mapeador a partir do ClipSpeedProvider; a prévia usa o
        // mapa do motor. Os dois devem concordar em cada quadro de saída.
        for (c in listOf(clip(eased, inUs = 1_234_567, outUs = 9_000_000), clip(speed = .25f), clip(speed = 7.5f, inUs = SECOND))) {
            val mapper = SpeedProviderUtil.SpeedProviderMapper(ClipSpeedProvider(c))
            assertEquals(c.durationUs, mapper.getAdjustedTimeUs(c.outUs - c.inUs))
            var t = 0L
            while (t < c.durationUs) {
                val export = c.inUs + mapper.getOriginalTimeUs(t)
                assertTrue("t=$t export=$export preview=${c.timeMap.sourceAt(t)}", abs(export - c.timeMap.sourceAt(t)) <= 1)
                t += 33_333
            }
        }
    }

    @Test fun durationIsTheExactIntegralNotTheMidpointRule() {
        // Rampa linear 0,25× → 4× sobre 4 s: duração exata = L·ln(v1/v0)/(v1−v0).
        val c = clip(listOf(SpeedPoint(0, .25f), SpeedPoint(4 * SECOND, 4f)), outUs = 4 * SECOND)
        val exact = 4.0 * SECOND * kotlin.math.ln(16.0) / 3.75
        assertTrue("${c.durationUs} vs $exact", abs(c.durationUs - exact) < 300)
    }

    @Test fun limitsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { SpeedPoint(0, .09f) }
        assertThrows(IllegalArgumentException::class.java) { SpeedPoint(0, 16.5f) }
        assertThrows(IllegalArgumentException::class.java) { SpeedPoint(0, 1f, Easing.SPRING) }
        // 0,1f em ponto flutuante é 0,1000000015: 10 s duram 99 999 999 µs, como no mapa antigo.
        assertEquals(kotlin.math.round(10.0 * SECOND / .1f.toDouble()).toLong(), clip(speed = .1f).durationUs)
        assertEquals(625_000L, clip(speed = 16f).durationUs)
    }

    @Test fun presetsComeFromTheEngineAndStayInsideTheTrim() {
        val count = NativeSpeed.presetCount()
        assertTrue(count >= 6)
        val names = (0 until count).map { NativeSpeed.presetLabel(it) }
        listOf("Lento suave", "Câmera lenta cinematográfica", "Velocity", "Ação", "Montagem", "Avanço rápido").forEach {
            assertTrue("$it em $names", it in names)
        }
        repeat(count) { index ->
            val points = SpeedPoint.unpack(NativeSpeed.buildPreset(index, 2 * SECOND, 7 * SECOND, 1f))
            assertEquals(2 * SECOND, points.first().sourceUs)
            assertEquals(7 * SECOND, points.last().sourceUs)
            val c = clip(points, inUs = 2 * SECOND, outUs = 7 * SECOND)
            assertTrue(c.durationUs > 0)
        }
        assertThrows(IllegalArgumentException::class.java) { NativeSpeed.buildPreset(99, 0, SECOND, 1f) }
    }

    @Test fun schema31KeepsEasingModesBlurAndExportQuality() {
        val c = clip(eased).copy(slowMotion = SlowMotionMode.OPTICAL_FLOW, retimedAudioMuted = true,
            motionBlur = MotionBlurConfig(enabled = true, shutterAngle = 270f, intensity = 1.4f, quality = MotionBlurQuality.HIGH,
                source = MotionBlurSource.MOTION_VECTORS))
        val overlay = c.copy(id = newId(), slowMotion = SlowMotionMode.BLEND)
        val p = Project(videos = listOf(c), videoTracks = listOf(VideoTrack(clips = listOf(TimedVideoClip(SECOND, overlay)))),
            export = ExportSettings(motionQuality = MotionExportQuality.ECONOMY))
        val bytes = ByteArrayOutputStream(); ProjectCodec.write(p, bytes)
        assertEquals(p, ProjectCodec.read(bytes.toByteArray().inputStream()))
    }

    @Test fun captionsStayOnTheirWordsWhenTheClipSlowsDown() {
        val first = clip(outUs = 4 * SECOND)
        val second = clip(outUs = 3 * SECOND).copy(id = newId())
        // Legenda com duas palavras ditas aos 1,0 s e 2,0 s do primeiro clipe; um texto depois dele.
        val caption = TextClip(text = "olá mundo", startUs = 900_000, endUs = 2_600_000, isCaption = true,
            wordCues = listOf(SubtitleWordCue("olá", SECOND, 1_400_000), SubtitleWordCue("mundo", 2 * SECOND, 2_500_000)))
        val later = TextClip(text = "fim", startUs = 5 * SECOND, endUs = 6 * SECOND)
        val marker = TimelineMarker(timeUs = 6_500_000)
        val p = Project(videos = listOf(first, second), texts = listOf(caption, later), markers = listOf(marker))
        val slowed = first.copy(speedCurve = listOf(SpeedPoint(0, 1f, Easing.SMOOTH), SpeedPoint(2 * SECOND, .25f), SpeedPoint(4 * SECOND, .25f)))
        val edited = SpeedEdit.apply(p, slowed)

        val newCaption = edited.texts.first { it.id == caption.id }
        caption.wordCues.zip(newCaption.wordCues).forEach { (old, new) ->
            // Mesmo instante da mídia antes e depois.
            assertTrue(abs(first.timeMap.sourceAt(old.startUs) - slowed.timeMap.sourceAt(new.startUs)) <= 2)
        }
        val shift = slowed.durationUs - first.durationUs
        assertTrue(shift > 0)
        assertEquals(later.startUs + shift, edited.texts.first { it.id == later.id }.startUs)
        assertEquals(marker.timeUs + shift, edited.markers.single().timeUs)
        assertEquals(edited.startOf(1), first.durationUs + shift)

        val untouched = SpeedEdit.apply(p, slowed, keepInSync = false)
        assertEquals(p.texts, untouched.texts)
    }

    @Test fun aSlowerOverlayClipPushesOnlyItsOwnTrack() {
        val main = clip()
        val a = clip(outUs = 2 * SECOND).copy(id = newId())
        val b = clip(outUs = 2 * SECOND).copy(id = newId())
        val other = clip(outUs = SECOND).copy(id = newId())
        val p = Project(videos = listOf(main), videoTracks = listOf(
            VideoTrack(id = "t1", clips = listOf(TimedVideoClip(SECOND, a), TimedVideoClip(3 * SECOND, b))),
            VideoTrack(id = "t2", clips = listOf(TimedVideoClip(3_500_000, other)))))
        val edited = SpeedEdit.apply(p, a.copy(speed = .5f))
        val track = edited.videoTracks.first { it.id == "t1" }
        assertEquals(SECOND, track.clips[0].startUs)
        assertEquals(5 * SECOND, track.clips[1].startUs) // empurrado para depois do novo fim
        assertEquals(3_500_000L, edited.videoTracks.first { it.id == "t2" }.clips.single().startUs)
    }

    @Test fun motionPlanComesFromTheEngine() {
        val c = clip(speed = .25f)
        val plan = MotionPolicy.plan(c, MotionContext.EXPORT_HIGH, DeviceMotionProfile.DEFAULT, 30f)
        assertEquals(SynthesisMethod.OPTICAL_FLOW, plan.synthesis)
        val scrub = MotionPolicy.plan(c, MotionContext.SCRUB, DeviceMotionProfile.DEFAULT, 30f)
        assertEquals(SynthesisMethod.REPEAT, scrub.synthesis)
        val weak = DeviceMotionProfile(memoryClassMb = 128, lowRamDevice = true, highpFragmentShaders = true, maxTextureSize = 4096)
        val degraded = MotionPolicy.plan(c.copy(slowMotion = SlowMotionMode.OPTICAL_FLOW), MotionContext.PLAYBACK, weak, 30f)
        assertEquals(SynthesisMethod.BLEND, degraded.synthesis)
        assertNotNull(degraded.notice)
    }

    @Test fun previewAudioKeepsPitchAndLengthThroughTheBridge() {
        val stream = NativeSpeed.audioCreate()
        try {
            val rate = 48_000
            val source = FloatArray(rate * 20 * 2) { i -> (kotlin.math.sin(2 * Math.PI * 440.0 * (i / 2) / rate) * .5).toFloat() }
            var cursor = 0
            val block = FloatArray(1024 * 2)
            val out = ArrayList<Float>()
            repeat(60) {
                while (NativeSpeed.audioAvailable(stream) < 1024) {
                    val need = NativeSpeed.audioInputNeeded(stream, 1024, .5f, true).coerceAtLeast(256)
                    val chunk = source.copyOfRange(cursor, cursor + need * 2)
                    cursor += need * 2
                    NativeSpeed.audioPush(stream, chunk, need, .5f, true, 1024)
                }
                assertEquals(1024, NativeSpeed.audioPull(stream, block, 1024))
                for (i in 0 until 1024) out += block[i * 2]
            }
            val body = out.subList(out.size / 10, out.size * 9 / 10)
            val crossings = body.zipWithNext().count { (a, b) -> a <= 0f && b > 0f }
            val hz = crossings / (body.size.toFloat() / rate)
            assertEquals(440f, hz, 13f)
            // Meia velocidade consome cerca de metade da mídia por segundo de timeline.
            assertEquals(.5, cursor / 2.0 / (60 * 1024), .1)
        } finally {
            NativeSpeed.audioRelease(stream)
        }
    }

    @Test fun exportSchedulerActionsCrossTheBridgeIntact() {
        val handle = NativeSpeed.schedulerCreate(30f, 0f)
        try {
            val rows = NativeSpeed.schedulerInput(handle, 0) + NativeSpeed.schedulerInput(handle, 133_333) + NativeSpeed.schedulerEnd(handle)
            val emits = rows.toList().chunked(8).filter { it[0] == 3L }
            assertEquals(listOf(0L, 33_333L, 66_667L, 100_000L, 133_333L), emits.map { it[1] })
            assertEquals(.25f, Float.fromBits(emits[1][6].toInt()), 1e-3f)
            assertThrows(IllegalArgumentException::class.java) { NativeSpeed.schedulerInput(handle, 10) }
        } finally {
            NativeSpeed.schedulerRelease(handle)
        }
    }

    @Test fun exportValidationRulesComeFromTheEngine() {
        val ok = NativeSpeed.validateExport(10 * SECOND, 10 * SECOND + 30_000, 10 * SECOND, true, 30)
        assertEquals(0L, ok[0])
        val late = NativeSpeed.validateExport(10 * SECOND, 10 * SECOND, 10 * SECOND + 400_000, true, 30)
        assertEquals(4L, late[0])
        assertTrue(NativeSpeed.exportVerdictText(late[0].toInt(), late[1]).contains("400 ms"))
    }
}
