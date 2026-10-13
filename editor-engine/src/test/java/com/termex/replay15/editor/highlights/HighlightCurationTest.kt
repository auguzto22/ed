package com.termex.replay15.editor.highlights

import com.termex.replay15.editor.highlights.HighlightCuration.Candidate
import com.termex.replay15.editor.highlights.HighlightCuration.Sentence
import org.junit.Assert.*
import org.junit.Test

class HighlightCurationTest {

    private fun seg(id: Int, text: String, start: Long, end: Long) = TranscriptSegment(id, text, start, end)

    /** n frases de 5 s, separadas por 0,5 s, com textos distintos. */
    private fun sentences(n: Int, lengthMs: Long = 5_000L, gapMs: Long = 500L): List<Sentence> =
        (0 until n).map { i ->
            val start = i * (lengthMs + gapMs)
            Sentence(i, start, start + lengthMs, "frase numero $i assunto${i}a assunto${i}b assunto${i}c")
        }

    private fun candidate(first: Int, last: Int, score: Float = .8f, title: String = "Título") = Candidate(
        startSentence = first, endSentence = last, title = title, hook = "Gancho", reason = "Motivo",
        score = score, scores = HighlightScores(.9f, .8f, .7f, .6f),
    )

    // ---------- frases ----------

    @Test
    fun `caption blocks are joined until punctuation`() {
        val result = HighlightCuration.sentences(listOf(
            seg(1, "Hoje eu vou", 0, 800),
            seg(2, "te mostrar isso.", 900, 1_800),
            seg(3, "Presta atenção!", 2_000, 3_000),
        ))
        assertEquals(2, result.size)
        assertEquals("Hoje eu vou te mostrar isso.", result[0].text)
        assertEquals(0L, result[0].startMs)
        assertEquals(1_800L, result[0].endMs)
        assertEquals(1, result[1].index)
    }

    @Test
    fun `long pause or long duration also closes a sentence`() {
        val byPause = HighlightCuration.sentences(listOf(seg(1, "sem ponto", 0, 1_000), seg(2, "depois", 3_000, 4_000)))
        assertEquals(2, byPause.size)

        val blocks = (0 until 20).map { seg(it, "bloco $it", it * 1_000L, it * 1_000L + 950) }
        val byLength = HighlightCuration.sentences(blocks)
        assertTrue(byLength.all { it.durationMs <= HighlightCuration.MAX_SENTENCE_MS })
        assertTrue(byLength.size >= 2)
    }

    // ---------- marcas ----------

    @Test
    fun `marks flag pauses energy and scene cuts`() {
        val s = listOf(
            Sentence(0, 0, 2_000, "a."), Sentence(1, 3_500, 5_000, "b."), Sentence(2, 5_100, 7_000, "c."),
            Sentence(3, 7_100, 9_000, "d."), Sentence(4, 9_100, 11_000, "e."),
        )
        val energy = listOf(.1f, .1f, .9f, .1f, .01f).flatMapIndexed { i, rms ->
            (0 until 3).map { k -> AudioEnergyPoint(s[i].startMs + k * 100L, rms, rms) }
        }
        val marks = HighlightCuration.marks(s, energy, sceneCutsMs = listOf(7_300))
        assertTrue(marks[1].contains("⏸1.5s"))
        assertTrue(marks[2].contains("↑energia"))
        assertTrue(marks[4].contains("↓energia"))
        assertTrue(marks[3].contains("✂cena"))
        assertFalse(marks[0].contains("⏸"))
    }

    @Test
    fun `energy marks need at least five sentences`() {
        val s = sentences(3)
        val energy = s.map { AudioEnergyPoint(it.startMs, it.index.toFloat(), 1f) }
        assertTrue(HighlightCuration.marks(s, energy).none { it.contains("energia") })
    }

    // ---------- prompt ----------

    @Test
    fun `prompt numbers sentences and carries limits and marks`() {
        val s = sentences(2)
        val prompt = HighlightCuration.prompt(s, listOf("", "⏸1.0s"), 6, 20_000, 60_000, "pt-BR")
        assertTrue(prompt.contains("0 [00:00] (5s) frase numero 0"))
        assertTrue(prompt.contains("1 [00:05] (5s) {⏸1.0s} frase numero 1"))
        assertTrue(prompt.contains("Escolha até 6 cortes"))
        assertTrue(prompt.contains("entre 20s e 60s"))
        assertTrue(prompt.contains("português brasileiro"))
    }

    // ---------- resposta da IA ----------

    @Test
    fun `parser reads sentence indexes and scores`() {
        val json = """
            ```json
            {"clips": [
              {"start_sentence": 4, "end_sentence": 2, "title": "Segredo", "hook": "Ninguém conta isso",
               "reason": "Revelação", "hook_score": 90, "coherence_score": "80", "value_score": 70,
               "trend_score": 60, "overall_score": 85}
            ]}
            ```
        """.trimIndent()
        val c = HighlightCuration.parseCandidates(json, sentenceCount = 10).single()
        assertEquals(2, c.startSentence) // índices fora de ordem são reordenados
        assertEquals(4, c.endSentence)
        assertEquals(.85f, c.score, .001f)
        assertEquals(.8f, c.scores.coherence, .001f) // número como texto é aceito
        assertEquals("Segredo", c.title)
    }

    @Test
    fun `parser drops invented indexes and clamps scores`() {
        val json = """{"clips": [
            {"start_sentence": 1, "end_sentence": 99, "title": "x", "overall_score": 90},
            {"start_sentence": -1, "end_sentence": 2, "title": "y", "overall_score": 90},
            {"start_sentence": 0, "end_sentence": 1, "title": "z", "overall_score": 250}
        ]}"""
        val result = HighlightCuration.parseCandidates(json, sentenceCount = 5)
        assertEquals(listOf("z"), result.map { it.title })
        assertEquals(1f, result.single().score, .001f)
    }

    @Test(expected = HighlightDetectionException::class)
    fun `parser rejects non json`() {
        HighlightCuration.parseCandidates("não sei", 5)
    }

    // ---------- refine ----------

    @Test
    fun `refine uses sentence times with padding`() {
        val s = sentences(10)
        val h = HighlightCuration.refine(listOf(candidate(2, 4)), s, 3_000, 60_000, 5, 60_000).single()
        assertEquals(s[2].startMs - HighlightCuration.PAD_START_MS, h.startMs)
        assertEquals(s[4].endMs + HighlightCuration.PAD_END_MS, h.endMs)
        assertEquals("Título", h.title)
        assertEquals(HighlightSource.GEMINI, h.source)
        assertNotNull(h.scores)
    }

    @Test
    fun `padding never invades the neighbouring sentence`() {
        val s = listOf(Sentence(0, 0, 5_000, "um."), Sentence(1, 5_050, 10_000, "dois."), Sentence(2, 10_100, 15_000, "tres."))
        val h = HighlightCuration.refine(listOf(candidate(1, 1)), s, 1_000, 60_000, 5, 20_000).single()
        assertEquals(5_000L, h.startMs)
        assertEquals(10_100L, h.endMs)
    }

    @Test
    fun `refine trims from the end and extends to reach limits`() {
        val s = sentences(20) // 5 s por frase + 0,5 s de pausa
        val tooLong = HighlightCuration.refine(listOf(candidate(0, 15)), s, 3_000, 20_000, 5, 200_000).single()
        assertEquals(0L, tooLong.startMs)
        assertTrue(tooLong.durationMs <= 20_000)

        val tooShort = HighlightCuration.refine(listOf(candidate(5, 5)), s, 15_000, 60_000, 5, 200_000).single()
        assertEquals(s[5].startMs - HighlightCuration.PAD_START_MS, tooShort.startMs) // gancho preservado
        assertTrue(tooShort.durationMs >= 15_000)
    }

    @Test
    fun `single sentence longer than max is capped`() {
        val s = listOf(Sentence(0, 0, 90_000, "monólogo enorme sem pausa."))
        val h = HighlightCuration.refine(listOf(candidate(0, 0)), s, 3_000, 30_000, 5, 90_000).single()
        assertEquals(30_000L, h.durationMs)
    }

    @Test
    fun `refine keeps best score and drops heavy overlap`() {
        val s = sentences(20)
        val result = HighlightCuration.refine(
            listOf(candidate(0, 3, score = .5f, title = "fraco"), candidate(1, 4, score = .9f, title = "forte"),
                candidate(10, 12, score = .7f, title = "outro")),
            s, 3_000, 60_000, 5, 200_000,
        )
        assertEquals(listOf("forte", "outro"), result.map { it.title })
    }

    @Test
    fun `refine drops the same content said twice`() {
        val repeated = "o segredo do sucesso é acordar cedo e treinar todo dia."
        val s = listOf(
            Sentence(0, 0, 5_000, repeated), Sentence(1, 5_500, 10_000, "outra coisa diferente aqui."),
            Sentence(2, 30_000, 35_000, repeated), Sentence(3, 35_500, 40_000, "mais um assunto novo."),
        )
        val result = HighlightCuration.refine(
            listOf(candidate(0, 0, score = .9f, title = "a"), candidate(2, 2, score = .8f, title = "b")),
            s, 1_000, 60_000, 5, 40_000,
        )
        assertEquals(listOf("a"), result.map { it.title })
    }

    @Test
    fun `refine respects max count`() {
        val s = sentences(30)
        val candidates = (0 until 10).map { candidate(it * 3, it * 3 + 1, score = 1f - it / 20f, title = "c$it") }
        val result = HighlightCuration.refine(candidates, s, 3_000, 60_000, 3, 200_000)
        assertEquals(listOf("c0", "c1", "c2"), result.map { it.title })
    }

    @Test
    fun `marker uses title when available`() {
        val h = Highlight(startMs = 0, endMs = 5_000, reason = "Motivo", score = .5f, title = "Título curto")
        assertEquals("Título curto", h.toMarker().name)
    }
}
