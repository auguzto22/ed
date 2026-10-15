package com.termex.replay15.editor

import com.termex.replay15.editor.autoedit.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.history.ProjectHistory
import com.termex.replay15.editor.project.ProjectCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Podcast reference style: short highlighted captions and full-screen B-roll images. */
class AutoEditPodcastBrollTest {
    private val ms = 1_000L

    /** Words spoken back to back (180 ms each, 40 ms apart) from [startUs]. */
    private fun speech(text: String, startUs: Long = 0L, wordUs: Long = 180 * ms, gapUs: Long = 40 * ms): List<TimedWord> {
        var cursor = startUs
        return text.split(' ').filter(String::isNotBlank).map { word ->
            TimedWord(word, cursor, cursor + wordUs).also { cursor += wordUs + gapUs }
        }
    }

    private fun clip(seconds: Long = 30) = VideoClip(uri = "file:///podcast.mp4", name = "podcast",
        sourceUs = seconds * SECOND, width = 1080, height = 1920)

    private fun media(name: String) = BrollMedia("file:///data/files/auto-broll/$name.jpg", 1080, 1920, "Openverse")

    // ---- Captions -------------------------------------------------------------------------

    @Test fun podcastLinesAreShortOrderedAndKeepMeasuredTiming() {
        val words = speech("Que cê consegue ser rico, cara. Rico pra caramba, ele falou assim: três coisas!")
        val lines = PodcastCaptions.chunk(words, 30 * SECOND)
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertTrue(line.text, line.words.size in 1..PodcastCaptions.MAX_WORDS)
            assertTrue(line.text, line.words.size == 1 || line.text.length <= PodcastCaptions.MAX_CHARS)
            assertFalse("pontuacao some como na referencia: ${line.text}", line.text.any { it in ".,:!?" })
            assertTrue(line.words.all { it.startUs >= line.startUs && it.endUs <= line.endUs })
            assertTrue(line.endUs - line.startUs >= PodcastCaptions.MIN_DURATION_US)
        }
        lines.zipWithNext().forEach { (a, b) -> assertTrue("linhas nao se sobrepoem", a.endUs <= b.startUs) }
        // Sentence punctuation closes a line: "cara." ends one, "Rico" starts the next.
        assertTrue(lines.any { it.text.endsWith("cara") })
        assertTrue(lines.any { it.text.startsWith("Rico") })
        // Every spoken word appears once, in order.
        assertEquals(words.map { PodcastCaptions.displayWord(it.text) }, lines.flatMap { it.words.map(TimedWord::text) })
    }

    @Test fun aLongPauseStartsANewLine() {
        val words = speech("fecha o") + speech("ralo", startUs = 2 * SECOND)
        val lines = PodcastCaptions.chunk(words, 5 * SECOND)
        assertEquals(listOf("fecha o", "ralo"), lines.map { it.text })
    }

    @Test fun executorReplacesClipCaptionsWithTheReferenceLookAndKeepsTitles() {
        val source = clip(10)
        val oldCaption = TextClip(text = "legenda antiga longa demais", startUs = 0, endUs = 2 * SECOND, isCaption = true,
            wordCues = listOf(SubtitleWordCue("legenda", 0, 400 * ms)))
        val title = TextClip(text = "TITULO", startUs = 0, endUs = 3 * SECOND, y = .1f)
        val project = Project(videos = listOf(source), texts = listOf(oldCaption, title))
        val words = speech("abre mais a torneira e trabalha mais", startUs = 500 * ms)
        val analysis = ClipAnalysis(source.id, source.durationUs, words = words)
        val plan = AutoEditPlanner.plan(analysis, AutoEditOptions(podcastCaptions = true, autoReframe = false, cleanPauses = false,
            smartZoom = false, audioEnhancement = false))
        assertTrue(plan.podcastCaptions)
        val edited = AutoEditExecutor.execute(project, 0, plan)

        assertTrue("titulo continua", edited.texts.any { it.text == "TITULO" && !it.isCaption })
        assertFalse("legenda antiga trocada", edited.texts.any { it.text == oldCaption.text })
        val captions = edited.texts.filter { it.isCaption }
        assertEquals(plan.captions.size, captions.size)
        captions.forEach { caption ->
            assertTrue(caption.wordStyle.uppercase)
            assertEquals(0xFFFFD43B.toInt(), caption.wordStyle.activeColor)
            assertEquals(AnimatedCaptionStylesFont.font, caption.captionFontOverride)
            assertEquals(TextAnimation.KARAOKE, caption.duringAnimation)
            assertTrue(caption.outlineWidth > 0f)
            assertTrue(caption.wordCues.isNotEmpty())
            assertTrue(caption.wordCues.all { it.startUs >= caption.startUs && it.endUs <= caption.endUs })
        }
        // Saved and reloaded exactly, and undone in one step.
        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(edited, it) }.toByteArray()
        assertEquals(edited, ProjectCodec.read(ByteArrayInputStream(bytes)))
        val history = ProjectHistory(project)
        assertTrue(history.apply(edited)); assertTrue(history.undo()); assertEquals(project, history.current)
    }

    @Test fun captionsCrossingACutStayInsideTheirLineAndTheTimeline() {
        val source = clip(10)
        val project = Project(videos = listOf(source))
        val words = speech("dinheiro entra", startUs = SECOND) + speech("dinheiro sai", startUs = 4 * SECOND)
        val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(podcastCaptions = true, autoReframe = false),
            cuts = listOf(CutDecision(2 * SECOND, 3500 * ms, .9f, "silencio")),
            captions = PodcastCaptions.chunk(words, source.durationUs))
        val edited = AutoEditExecutor.execute(project, 0, plan)
        assertEquals(8500 * ms, edited.durationUs)
        val captions = edited.texts.filter { it.isCaption }
        assertEquals(2, captions.size)
        assertTrue(captions.all { it.endUs <= edited.durationUs })
        assertEquals(4 * SECOND - 1500 * ms, captions[1].startUs)
    }

    @Test fun defaultOptionsKeepThePreviousBehaviour() {
        val source = clip(20)
        val words = speech("o dinheiro e o trabalho", startUs = 3 * SECOND)
        val plan = AutoEditPlanner.plan(ClipAnalysis(source.id, source.durationUs, words = words), AutoEditOptions(autoReframe = false))
        assertFalse(plan.podcastCaptions)
        assertTrue(plan.brolls.isEmpty())
        assertEquals("transcricao temporizada", plan.captions.single().reason)
    }

    // ---- B-roll planning ------------------------------------------------------------------

    @Test fun lexiconFindsPicturableWordsIgnoringCaseAccentsAndPunctuation() {
        assertEquals("money cash", BrollLexicon.lookup("Dinheiro,")?.second)
        assertEquals("worker working", BrollLexicon.lookup("TRABALHAR")?.second)
        assertEquals("family", BrollLexicon.lookup("família")?.second)
        assertEquals("faucet water", BrollLexicon.lookup("torneira")?.second)
        assertNull("ambiguo fica de fora", BrollLexicon.lookup("real"))
        assertNull(BrollLexicon.lookup("tempo"))
        assertNull(BrollLexicon.lookup("que"))
    }

    @Test fun brollKeepsTheHookSpacingBudgetAndEnding() {
        val words = (0 until 30).flatMap { second -> speech("o dinheiro", startUs = second * SECOND) }
        val duration = 30 * SECOND
        val plan = BrollPlanner.plan(words, duration)
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.size <= BrollPlanner.budget(duration))
        assertTrue(plan.first().startUs >= BrollPlanner.MIN_START_US)
        assertTrue(plan.all { it.endUs <= duration - BrollPlanner.END_GUARD_US })
        assertTrue(plan.all { it.endUs - it.startUs in BrollPlanner.MIN_DURATION_US..BrollPlanner.MAX_DURATION_US })
        plan.zipWithNext().forEach { (a, b) -> assertTrue(b.startUs - a.endUs >= BrollPlanner.MIN_SPACING_US) }
        assertTrue(plan.all { it.query == "money cash" })
        // Same thing is not shown again within the repeat window.
        plan.zipWithNext().forEach { (a, b) -> assertTrue(b.startUs - a.startUs >= BrollPlanner.SAME_KEYWORD_US) }
    }

    @Test fun synonymsWithTheSameSearchAreNotShownBackToBack() {
        val words = speech("vida minimalista", startUs = 5 * SECOND) + speech("simplicidade extrema", startUs = 10 * SECOND) +
            speech("simplicidade", startUs = 25 * SECOND)
        val plan = BrollPlanner.plan(words, 40 * SECOND)
        assertEquals(listOf(5 * SECOND + 220 * ms - 80 * ms, 25 * SECOND - 80 * ms), plan.map { it.startUs })
    }

    @Test fun brollNeverSpansARemovedPause() {
        val words = speech("meu carro", startUs = 3 * SECOND)
        val cut = CutDecision(3800 * ms, 4600 * ms, .9f, "silencio")
        val plan = BrollPlanner.plan(words, 20 * SECOND, listOf(cut))
        plan.forEach { assertFalse("nao cruza o corte", it.startUs < cut.endUs && it.endUs > cut.startUs) }
        val validated = AutoEditPlanner.validate(AutoEditPlan("c", 20 * SECOND, AutoEditOptions(broll = true), cuts = listOf(cut), brolls = plan))
        assertEquals(plan, validated.brolls)
    }

    @Test fun shortClipsAndSilentClipsGetNoBroll() {
        assertTrue(BrollPlanner.plan(speech("dinheiro"), SECOND).isEmpty())
        assertTrue(BrollPlanner.plan(emptyList(), 30 * SECOND).isEmpty())
        val plan = AutoEditPlanner.plan(ClipAnalysis("c", 30 * SECOND), AutoEditOptions(broll = true, autoReframe = false))
        assertTrue(plan.brolls.isEmpty())
        assertTrue(plan.warnings.any { it.contains("B-roll") })
    }

    // ---- AI suggestions -------------------------------------------------------------------

    @Test fun aiSuggestionsAreSnappedToTheSpokenWordAndValidated() {
        val words = speech("eu tinha um trampo com algum bico e o dinheiro sumia", startUs = 2 * SECOND)
        val bico = words.first { it.text == "bico" }
        val answer = """```json
            {"broll":[
              {"start": ${bico.startUs / 1e6 + .6}, "end": ${bico.startUs / 1e6 + 2.0}, "keyword": "bico", "query": "construction worker"},
              {"start": 3.0, "keyword": "Dinheiro", "query": "Money  Cash"},
              {"start": 4.0, "keyword": "unicornio", "query": "unicorn"},
              {"start": 2.5, "keyword": "trampo", "query": "<script>"},
              {"start": 99.0, "keyword": "bico", "query": "worker"}
            ]}
            ```"""
        val parsed = BrollSuggestionParser.parse(answer, words, 20 * SECOND)
        assertEquals(listOf("bico", "dinheiro"), parsed.map { it.keyword })
        assertEquals(bico.startUs - 80 * ms, parsed[0].startUs)
        assertEquals("money cash", parsed[1].query)
        assertTrue(parsed.all { it.endUs - it.startUs in BrollPlanner.MIN_DURATION_US..BrollPlanner.MAX_DURATION_US })
        // The planner prefers them over the dictionary and labels the reason.
        val plan = BrollPlanner.plan(words, 20 * SECOND, suggestions = parsed)
        assertTrue(plan.isNotEmpty() && plan.all { it.reason.startsWith("IA") })
    }

    @Test fun malformedAiAnswersYieldNothing() {
        val words = speech("dinheiro")
        assertTrue(BrollSuggestionParser.parse("desculpe, nao sei", words, 10 * SECOND).isEmpty())
        assertTrue(BrollSuggestionParser.parse("{\"outro\":[]}", words, 10 * SECOND).isEmpty())
        val prompt = BrollSuggestionParser.prompt(words, 10 * SECOND, 4, "pt-BR")
        assertTrue(prompt.contains("[0.00] dinheiro"))
    }

    // ---- Stock libraries ------------------------------------------------------------------

    @Test fun pexelsPrefersLargeImagesAndOnlyHttps() {
        val body = """{"photos":[{"width":4000,"height":6000,"src":{"original":"https://images.pexels.com/o.jpg",
            "large2x":"https://images.pexels.com/l2.jpg","portrait":"https://images.pexels.com/p.jpg"}},
            {"width":3000,"height":4000,"src":{"portrait":"https://images.pexels.com/p2.jpg"}},
            {"width":10,"height":10,"src":{"original":"http://insecure.example/x.jpg"}}]}"""
        assertEquals(listOf("https://images.pexels.com/l2.jpg", "https://images.pexels.com/p2.jpg"),
            StockImageParsers.pexels(body, tall = true).map { it.url })
        assertEquals(listOf("https://images.pexels.com/l2.jpg"), StockImageParsers.pexels(body, tall = false).map { it.url })
    }

    @Test fun openverseKeepsOnlyFreeSafeRasterImages() {
        val body = """{"results":[
            {"url":"https://live.staticflickr.com/a.jpg","license":"cc0","mature":false,"width":1200,"height":1800},
            {"url":"https://live.staticflickr.com/b.jpg","license":"by","mature":false},
            {"url":"https://live.staticflickr.com/c.jpg","license":"pdm","mature":true},
            {"url":"https://upload.wikimedia.org/d.svg","license":"cc0","mature":false},
            {"url":"http://insecure.example/e.jpg","thumbnail":"https://api.openverse.org/v1/images/e/thumb/","license":"pdm"}]}"""
        assertEquals(listOf("https://live.staticflickr.com/a.jpg", "https://api.openverse.org/v1/images/e/thumb/"),
            StockImageParsers.openverse(body).map { it.url })
    }

    @Test fun imagesAreCenterCroppedToTheCanvasWithoutUpscaling() {
        val wide = BrollImageFetcher.centerCrop(4000, 3000, 9f / 16f)
        assertEquals(1688, wide.width); assertEquals(3000, wide.height); assertEquals((4000 - 1688) / 2, wide.left)
        val tall = BrollImageFetcher.centerCrop(1000, 3000, 9f / 16f)
        assertEquals(1000, tall.width); assertEquals(1778, tall.height)
        assertEquals(1080 to 1920, BrollImageFetcher.outputSize(2160, 3840))
        assertEquals(800 to 1422, BrollImageFetcher.outputSize(801, 1423))
        assertEquals(2, BrollImageFetcher.sampleSize(2160, 3840, 1080, 1920))
        assertEquals(1, BrollImageFetcher.sampleSize(1080, 1920, 1080, 1920))
    }

    // ---- Executor: B-roll layer -----------------------------------------------------------

    @Test fun placedBrollBecomesAnEditableImageLayerMappedThroughCuts() {
        val source = clip(20)
        val project = Project(videos = listOf(source), export = ExportSettings.forSource(source))
        val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(broll = true, autoReframe = false),
            cuts = listOf(CutDecision(2 * SECOND, 3 * SECOND, .9f, "silencio")),
            brolls = listOf(
                BrollDecision(4 * SECOND, 5500 * ms, "dinheiro", "money cash", .8f, "fala", media("a")),
                BrollDecision(9 * SECOND, 10 * SECOND, "carro", "car", .8f, "fala", media = null),
                BrollDecision(12 * SECOND, 13500 * ms, "casa", "house home", .8f, "fala", media("b")),
            ))
        val edited = AutoEditExecutor.execute(project, 0, plan)
        assertEquals("o video principal so perde a pausa", 19 * SECOND, edited.durationUs)
        val track = edited.videoTracks.single()
        assertEquals(AutoEditExecutor.BROLL_TRACK_NAME, edited.trackState(track.id).name)
        assertEquals("sem imagem baixada nao entra", 2, track.clips.size)
        assertEquals(3 * SECOND, track.clips[0].startUs)
        assertEquals(1500 * ms, track.clips[0].clip.durationUs)
        assertEquals(11 * SECOND, track.clips[1].startUs)
        track.clips.forEach { item ->
            assertTrue(item.clip.image)
            assertEquals(0f, item.clip.volume)
            assertTrue(item.clip.keyframes.isEmpty())
            val animation = edited.animationClips.single { it.targetId == item.clip.id }
            assertTrue(animation.preset in AutoEditExecutor.BROLL_PRESETS)
            assertEquals(0L, animation.startUs)
            assertEquals(item.clip.durationUs, animation.endUs)
        }
        assertTrue("direcao alterna entre as imagens",
            edited.animationClips.map { it.preset }.distinct().size == 2)
        assertEquals("B-roll: dinheiro", track.clips[0].clip.name)
        assertTrue(plan.summary().contains("2 imagens"))

        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(edited, it) }.toByteArray()
        assertEquals(edited, ProjectCodec.read(ByteArrayInputStream(bytes)))
        val history = ProjectHistory(project)
        assertTrue(history.apply(edited)); assertTrue(history.undo()); assertEquals(project, history.current)
    }

    @Test fun brollIsSkippedWhenAllLayersAreTaken() {
        val source = clip(20)
        val tracks = (0 until AutoEditExecutor.MAX_VIDEO_TRACKS).map { VideoTrack() }
        val project = Project(videos = listOf(source), videoTracks = tracks)
        val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(broll = true, autoReframe = false),
            brolls = listOf(BrollDecision(4 * SECOND, 5 * SECOND, "dinheiro", "money", .8f, "fala", media("a"))))
        val edited = AutoEditExecutor.execute(project, 0, plan)
        assertEquals(tracks, edited.videoTracks)
    }

    @Test fun brollOnALaterClipLandsOnThatClip() {
        val intro = VideoClip(uri = "file:///intro.mp4", name = "intro", sourceUs = 5 * SECOND, width = 1080, height = 1920)
        val source = clip(20)
        val project = Project(videos = listOf(intro, source))
        val plan = AutoEditPlan(source.id, source.durationUs, AutoEditOptions(broll = true, autoReframe = false),
            brolls = listOf(BrollDecision(4 * SECOND, 5 * SECOND, "dinheiro", "money", .8f, "fala", media("a"))))
        val edited = AutoEditExecutor.execute(project, 1, plan)
        assertEquals(9 * SECOND, edited.videoTracks.single().clips.single().startUs)
    }

    @Test fun canvasAspectFollowsReframeOrTheProject() {
        val vertical = Project(videos = listOf(clip()))
        assertEquals(9f / 16f, AutoEditExecutor.canvasAspectAfter(vertical, AutoEditPlan("c", SECOND, AutoEditOptions())), .01f)
        val landscape = Project(videos = listOf(VideoClip(uri = "file:///l.mp4", name = "l", sourceUs = SECOND, width = 1920, height = 1080)))
        val reframed = AutoEditPlan("c", SECOND, AutoEditOptions(autoReframe = true), reframes = listOf(ReframeDecision(0, 0f, 0f, .8f, "acao")))
        assertEquals(9f / 16f, AutoEditExecutor.canvasAspectAfter(landscape, reframed), .001f)
        assertEquals(16f / 9f, AutoEditExecutor.canvasAspectAfter(landscape, AutoEditPlan("c", SECOND, AutoEditOptions(autoReframe = false))), .01f)
    }

    // ---- Transcribed captions merged while the user kept editing --------------------------

    @Test fun transcribedCaptionsFollowTheClipAndRespectTheLimit() {
        val caption = TextClip(text = "oi", startUs = SECOND, endUs = 2 * SECOND, isCaption = true,
            wordCues = listOf(SubtitleWordCue("oi", SECOND, 1500 * ms)))
        val moved = AutoEditExecutor.shiftedCaptions(listOf(caption), 3 * SECOND).single()
        assertEquals(4 * SECOND, moved.startUs)
        assertEquals(4 * SECOND, moved.wordCues.single().startUs)
        assertTrue(AutoEditExecutor.shiftedCaptions(listOf(caption), -5 * SECOND).isEmpty())

        val project = Project(videos = listOf(clip()))
        val merged = AutoEditExecutor.withCaptions(project, listOf(moved))
        assertEquals(listOf(moved), merged.texts)
        assertEquals("nao duplica", merged, AutoEditExecutor.withCaptions(merged, listOf(moved)))
    }
}

/** The font the podcast caption style uses, read from the style itself so the test follows it. */
private object AnimatedCaptionStylesFont {
    val font: String = com.termex.replay15.editor.captions.AnimatedCaptionStyles.byId(PodcastCaptions.STYLE_ID)!!.fontId
}
