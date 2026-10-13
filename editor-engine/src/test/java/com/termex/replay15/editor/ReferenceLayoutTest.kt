package com.termex.replay15.editor

import com.termex.replay15.editor.autoedit.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.reference.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReferenceLayoutTest {
    private val n = ReferenceLayoutDetector.GRID_ROWS * ReferenceLayoutDetector.GRID_COLUMNS

    private fun cells(luma: Float = .4f, chroma: Float = .2f, edit: (FloatArray) -> Unit = {}) =
        FloatArray(n * 2) { if (it < n) luma else chroma }.also(edit)

    private fun frame(time: Long, cells: FloatArray, light: Float = .35f) = FrameFeatures(
        time, 4, 4, ByteArray(0), FloatArray(32).also { it[(light * 31).toInt()] = 1f }, light, .3f, .5f, .4f, 0f, .02f, cells,
    )

    private fun profile(layout: List<ReferenceLayoutElement>, captions: CaptionStyleProfile = CaptionStyleProfile()) = ReferenceStyleProfile(
        "ref", "Ref", "finger", "content://ref", createdAtMs = 1, sourceDurationUs = 10_000_000, cutCount = 0, averageCutIntervalUs = 0,
        medianCutIntervalUs = 0, cutIntervalDistributionUs = emptyList(), dominantCutType = null, events = emptyList(), beats = emptyList(),
        zoomsPerMinute = 0f, medianZoomScale = 1f, medianShakeDurationUs = 0, medianFlashDurationUs = 0, speedRampUsage = 0f,
        captionStyle = captions, beatAlignment = 0f, effectCombinations = emptyList(), overallConfidence = .7f, layout = layout,
    )

    private val hook = ReferenceLayoutElement(ReferenceLayoutKind.TITLE, 0, 3_400_000, LayoutBox(.5f, .08f, .85f, .07f),
        LayoutStyle(-1, 0, true, true, LayoutFont.CONDENSED), "VOTE NO LULA", .8f)
    private val banner = ReferenceLayoutElement(ReferenceLayoutKind.OVERLAY_IMAGE, 0, 3_400_000, LayoutBox(.5f, .85f, 1f, .3f))
    private val broll = ReferenceLayoutElement(ReferenceLayoutKind.BROLL, 9_800_000, 10_000_000, LayoutBox.FULL)

    @Test fun verticalVideoIsSampledInItsOwnOrientation() {
        assertEquals(90 to 160, FrameAnalysisConfig().boxFor(576, 1024))
        assertEquals(160 to 90, FrameAnalysisConfig().boxFor(1920, 1080))
        assertEquals(720 to 1280, TextStyleAnalyzer.ocrBox(576, 1024))
        assertTrue(TextStyleAnalyzer.ocrStepUs(10_000_000) <= 250_000L)
    }

    @Test fun jumpCutPunchInIsAZoomNotASceneChange() {
        val frames = (0..11).map { frame(it * 150_000L, cells()) }
        val deltas = (1..11).map { index ->
            val punch = index == 6
            FrameDelta(index * 150_000L, if (punch) .7f else .05f, if (punch) .06f else .02f, .01f, .05f, if (punch) .4f else .92f,
                0f, 0f, if (punch) 1.25f else 1f, 1f, .02f)
        }
        val events = ReferenceVisualDetectors.sceneEvents(frames, deltas)
        assertTrue(events.none { it.type == ReferenceEventType.HARD_CUT })
        val zoom = events.single { it.type == ReferenceEventType.ZOOM }
        assertEquals(1f, zoom.values["punch"])
        assertEquals(1.25f, zoom.values["scale"]!!, .001f)
    }

    @Test fun titleAppearingInOneRegionIsNotACut() {
        val plain = cells(); val titled = cells { for (c in 0 until ReferenceLayoutDetector.GRID_COLUMNS * 2) it[c] = .95f }
        val frames = (0..11).map { frame(it * 150_000L, if (it < 6) plain else titled) }
        val deltas = (1..11).map { index ->
            val change = index == 6
            FrameDelta(index * 150_000L, if (change) .8f else .05f, if (change) .4f else .02f, .02f, .05f, if (change) .2f else .92f, 0f, 0f, 1f, 1f, .02f)
        }
        assertTrue(ReferenceVisualDetectors.sceneEvents(frames, deltas).none { it.type == ReferenceEventType.HARD_CUT })
    }

    @Test fun titleIsTrackedByPlaceAcrossNoisyOcrReads() {
        val box = LayoutBox.fromEdges(.09f, .18f, .91f, .25f)
        val reads = listOf("VOTE", "VOTE NO LULA", "OTE NO", "VOTE NO LULA", "VOTE", "VOTE NO LULA")
        val lines = reads.mapIndexed { i, text -> TextLineObservation(i * 250_000L, text, box, -1, null, true) } +
            // A platform handle on every frame and a one-off caption are not titles.
            (0..5).map { TextLineObservation(it * 250_000L, "@usuario", LayoutBox(.5f, .68f, .3f, .02f), -1, null, false) }
        val titles = ReferenceLayoutDetector.titles(lines, 2_000_000, 250_000, emptySet())
        val title = titles.single()
        assertEquals("VOTE NO LULA", title.label)
        assertEquals(ReferenceLayoutKind.TITLE, title.kind)
        assertTrue(title.style.uppercase)
        assertEquals(0L, title.startUs)
    }

    @Test fun brollIsAShortForeignShotButNeverTheOpeningShot() {
        val main = FloatArray(32).also { it[10] = 1f }; val other = FloatArray(32).also { it[25] = 1f }
        val shots = listOf(
            ReferenceLayoutDetector.Shot(0, 3_300_000, other, cells(.6f)),
            ReferenceLayoutDetector.Shot(3_300_000, 9_800_000, main, cells(.4f)),
            ReferenceLayoutDetector.Shot(9_800_000, 10_000_000, other, cells(.9f, .6f)),
        )
        val broll = ReferenceLayoutDetector.broll(shots, 10_000_000).single()
        assertEquals(9_800_000L, broll.startUs)
    }

    @Test fun geminiDescriptionIsParsedDefensively() {
        val raw = """```json
            {"elements":[
              {"kind":"title","start":0,"end":3.4,"box":{"x":0.5,"y":0.08,"w":0.85,"h":0.07},"text":"VOTE NO LULA",
               "style":{"textColor":"#FFFFFF","bold":true,"uppercase":true,"font":"condensed"}},
              {"kind":"banner","start":0,"end":3.4,"box":{"x":0.5,"y":0.85,"w":1,"h":0.3}},
              {"kind":"card","start":7,"end":9.3,"box":{"x":0.5,"y":0.15,"w":0.9,"h":0.12},"style":{"backgroundColor":"#FFFFFF","textColor":"#111111"}},
              {"kind":"caption","start":0,"end":10},
              {"kind":"broll","start":9.8,"end":12},
              {"kind":"title","start":"x","end":2}
            ],
             "captions":{"present":true,"position_y":0.6,"words_per_caption":2,"animation":"pop","style":{"uppercase":true}},
             "editing":{"punch_ins":[3.4, 99],"notes":"Ritmo rapido"}}
            ```"""
        val result = ReferenceGeminiParser.parse(raw, 10_000_000)
        assertEquals(listOf(ReferenceLayoutKind.TITLE, ReferenceLayoutKind.OVERLAY_IMAGE, ReferenceLayoutKind.CARD, ReferenceLayoutKind.BROLL), result.elements.map { it.kind })
        assertEquals(10_000_000L, result.elements.last().endUs)
        assertEquals(0xFFFFFFFF.toInt(), result.elements[2].style.backgroundColor)
        assertTrue(result.captions!!.present && result.captions!!.uppercase)
        assertEquals(listOf(3_400_000L), result.punchInsUs)
        assertEquals("Ritmo rapido", result.notes)
    }

    @Test fun layoutSurvivesProfilePersistence() {
        val original = profile(listOf(hook, banner, broll), CaptionStyleProfile(present = true, uppercase = true))
        val restored = ReferenceStyleProfile.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original.layout, restored.layout)
        assertTrue(restored.captionStyle.uppercase)
        assertEquals(2, REFERENCE_ANALYSIS_VERSION)
        assertFalse(restored.summary().contains("Edicao minima detectada"))
    }

    @Test fun planKeepsHookAtStartAndExplainsMissingCaptions() {
        val analysis = ClipAnalysis("clip", 20_000_000, motion = listOf(MotionSample(5_000_000, .9f)), hasAudio = true)
        val plan = ReferenceStylePlanner.plan(profile(listOf(hook, banner, broll), CaptionStyleProfile(present = true)), analysis)
        assertEquals(3, plan.layout.size)
        assertEquals(0f, plan.layout.first().start)
        assertEquals(1f, plan.layout.single { it.kind == ReferenceLayoutKind.BROLL }.end)
        assertTrue(plan.skipped.any { it.startsWith("Legendas") })
        assertTrue(plan.applied.any { it.startsWith("Titulos") })
    }

    @Test fun executorTurnsLayoutIntoEditableItems() {
        val clip = VideoClip(id = "clip", uri = "content://video/dance", name = "Dance", sourceUs = 20_000_000, width = 480, height = 852)
        val project = Project(videos = listOf(clip))
        val analysis = ClipAnalysis("clip", 20_000_000, hasAudio = true)
        val ref = profile(listOf(hook, banner, broll))
        val plan = ReferenceStylePlanner.plan(ref, analysis)
        val aspects = mutableListOf<Float>()
        val edited = ReferenceStyleExecutor.execute(project, 0, plan, ref) { aspect -> aspects += aspect; "file:///slot.png" }
        val title = edited.texts.single()
        assertEquals("SEU TITULO AQUI", title.text)
        assertEquals(0L, title.startUs)
        assertEquals(TextFont.BEBAS.id, title.fontId)
        assertEquals(2, edited.stickers.size)
        val fullFrame = edited.stickers.single { it.name == "Espaco: B-roll" }
        assertEquals(20_000_000L, fullFrame.endUs)
        assertEquals(1f, fullFrame.size)
        assertTrue(aspects.any { it > 1.5f }) // the bottom banner is wide
        assertEquals(setOf("Imagem aqui", "B-roll aqui"), edited.markers.map { it.name }.toSet())
        // Without a placeholder image the slots still show up on the timeline.
        val markersOnly = ReferenceStyleExecutor.execute(project, 0, plan, ref)
        assertTrue(markersOnly.stickers.isEmpty())
        assertEquals(2, markersOnly.markers.size)
    }
}
