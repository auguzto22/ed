package com.termex.replay15.editor.highlights

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Curadoria de cortes portada do motor do ClipStudio (backend Python, `curate_llm.py` + `refine.py`).
 *
 * Diferença principal para a versão anterior do [GeminiHighlightAnalyzer]: o Gemini **não devolve
 * timestamps**. Ele recebe a transcrição numerada por frase e devolve só os números da primeira e da
 * última frase de cada corte. Os tempos saem das próprias frases, então um corte nunca começa ou termina
 * no meio de uma frase e o modelo não consegue inventar um tempo que não existe.
 *
 * Fluxo (tudo puro, sem Android, testável na JVM):
 *  1. [sentences]: junta os blocos de legenda em frases (pontuação, pausa, duração máxima);
 *  2. [marks]: anota cada frase com sinais medidos (pausa antes, energia alta/baixa, troca de cena);
 *  3. [prompt]: monta o pedido em português com as frases numeradas e as marcas;
 *  4. [parseCandidates]: lê e valida o JSON da IA (índices dentro do intervalo, scores 0..100);
 *  5. [refine]: aplica as durações mínima e máxima por frase, folgas nas bordas, limita sobreposição
 *     a 30% e descarta trechos que repetem o mesmo conteúdo (Jaccard das palavras > 0,6).
 */
object HighlightCuration {
    const val SENTENCE_GAP_MS = 700L
    const val MAX_SENTENCE_MS = 15_000L
    const val PAUSE_MARK_MS = 800L
    const val SCENE_TOLERANCE_MS = 500L
    const val PAD_START_MS = 100L
    const val PAD_END_MS = 350L
    const val MIN_CLIP_MS = 1_000L
    const val MAX_OVERLAP = 0.30f
    const val MAX_TEXT_SIMILARITY = 0.6f
    private const val MAX_TITLE = 80
    private const val MAX_REASON = 500

    /** Frase da transcrição: unidade que a IA numera e escolhe. */
    data class Sentence(val index: Int, val startMs: Long, val endMs: Long, val text: String) {
        val durationMs: Long get() = endMs - startMs
    }

    /** Corte sugerido pela IA, ainda em índices de frase (inclusive). Scores em 0..1. */
    data class Candidate(
        val startSentence: Int,
        val endSentence: Int,
        val title: String,
        val hook: String,
        val reason: String,
        val score: Float,
        val scores: HighlightScores,
    )

    // ---------- 1. frases ----------

    fun sentences(segments: List<TranscriptSegment>): List<Sentence> {
        val out = mutableListOf<Sentence>()
        val text = StringBuilder()
        var start = -1L
        var end = 0L

        fun flush() {
            val value = text.toString().trim()
            if (start >= 0 && value.isNotEmpty() && end > start) out += Sentence(out.size, start, end, value)
            text.setLength(0)
            start = -1L
        }

        for (segment in segments.sortedBy { it.startMs }) {
            if (start >= 0 && (segment.startMs - end > SENTENCE_GAP_MS || segment.endMs - start > MAX_SENTENCE_MS)) {
                flush()
            }
            if (start < 0) {
                start = segment.startMs
                end = segment.endMs
            } else {
                end = max(end, segment.endMs)
            }
            text.append(' ').append(segment.text.trim())
            if (endsSentence(segment.text)) flush()
        }
        flush()
        return out
    }

    private fun endsSentence(text: String): Boolean {
        val trimmed = text.trimEnd().trimEnd('"', '\'', '”', '’', ')', ']')
        return trimmed.isNotEmpty() && trimmed.last() in ".!?…"
    }

    // ---------- 2. marcas de sinais ----------

    /** Marcas compactas por frase, no mesmo formato do ClipStudio: `⏸1.2s`, `↑energia`, `↓energia`, `✂cena`. */
    fun marks(
        sentences: List<Sentence>,
        energy: List<AudioEnergyPoint> = emptyList(),
        sceneCutsMs: List<Long> = emptyList(),
    ): List<String> {
        val energies = if (energy.isEmpty()) emptyList() else sentences.map { s -> meanRms(energy, s.startMs, s.endMs) }
        val useEnergy = energies.size >= 5
        val hi = if (useEnergy) percentile(energies, 0.8f) else 0f
        val lo = if (useEnergy) percentile(energies, 0.2f) else 0f

        return sentences.mapIndexed { i, s ->
            val m = mutableListOf<String>()
            val pause = if (i > 0) s.startMs - sentences[i - 1].endMs else 0L
            if (pause >= PAUSE_MARK_MS) m += String.format(Locale.US, "⏸%.1fs", pause / 1000f)
            if (useEnergy && hi > lo) {
                if (energies[i] >= hi) {
                    m += "↑energia"
                } else if (energies[i] <= lo) {
                    m += "↓energia"
                }
            }
            if (sceneCutsMs.any { abs(it - s.startMs) <= SCENE_TOLERANCE_MS }) m += "✂cena"
            m.joinToString(" ")
        }
    }

    private fun meanRms(energy: List<AudioEnergyPoint>, startMs: Long, endMs: Long): Float {
        var sum = 0f
        var count = 0
        for (point in energy) {
            if (point.timeMs in startMs until endMs) {
                sum += point.rms
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun percentile(values: List<Float>, fraction: Float): Float {
        val sorted = values.sorted()
        val position = fraction * (sorted.size - 1)
        val low = position.toInt()
        val high = min(low + 1, sorted.size - 1)
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }

    // ---------- 3. prompt ----------

    fun languageName(code: String): String = when (code) {
        "pt-BR", "pt" -> "português brasileiro"
        "en-US", "en" -> "inglês"
        "es", "es-ES" -> "espanhol"
        else -> code
    }

    fun prompt(
        sentences: List<Sentence>,
        marks: List<String>,
        wanted: Int,
        minDurationMs: Long,
        maxDurationMs: Long,
        languageCode: String,
    ): String {
        val language = languageName(languageCode)
        val transcript = sentences.joinToString("\n") { s ->
            val mark = marks.getOrNull(s.index)?.takeIf(String::isNotBlank)?.let { " {$it}" }.orEmpty()
            "${s.index} [${clock(s.startMs)}] (${(s.durationMs + 500) / 1000}s)$mark ${s.text}"
        }
        return """
            Você é editor de vídeos curtos (Reels, Shorts, TikTok). Recebe a transcrição de um vídeo, numerada por frase com o tempo de início [mm:ss] e a duração (Ns), e escolhe os trechos com maior potencial de viralizar como cortes independentes.

            Algumas frases trazem marcas de sinais medidos no vídeo:
            - ⏸N.Ns: pausa antes da frase (bom ponto de início);
            - ↑energia / ↓energia: fala bem mais alta (ênfase, emoção, risada) ou mais baixa que a média;
            - ✂cena: troca de cena ou de câmera no início da frase.
            Use as marcas como evidência adicional, sem ignorar o conteúdo.

            Um bom corte:
            - começa com um gancho forte nos 3 primeiros segundos (pergunta, afirmação surpreendente, conflito, promessa);
            - se sustenta sozinho, sem depender de contexto anterior, e termina numa ideia concluída;
            - entrega valor claro (insight, história, humor, emoção, dica prática).

            Regras:
            - Escolha até $wanted cortes, cada um com duração entre ${minDurationMs / 1000}s e ${maxDurationMs / 1000}s.
            - start_sentence e end_sentence são os números das frases (inclusive). Nunca invente números.
            - Cortes não devem se sobrepor.
            - Se o mesmo conteúdo aparece mais de uma vez (repetição, vinheta, trecho reapresentado), escolha só uma ocorrência.
            - title: título curto e chamativo em $language (máx. 60 caracteres).
            - hook: a frase de abertura que prende a atenção.
            - reason: por que o trecho funciona, em uma frase curta em $language.
            - Scores de 0 a 100; overall_score resume o potencial do corte.
            - Ordene do melhor para o pior.

            Responda APENAS com JSON válido neste formato:
            {"clips": [{"start_sentence": 0, "end_sentence": 3, "title": "...", "hook": "...", "reason": "...", "hook_score": 0, "coherence_score": 0, "value_score": 0, "trend_score": 0, "overall_score": 0}]}
        """.trimIndent() + "\n\n<transcricao>\n$transcript\n</transcricao>"
        // A transcrição entra depois do trimIndent: linhas sem recuo dentro do bloco anulariam o recorte.
    }

    private fun clock(ms: Long): String {
        val seconds = ms / 1000
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    // ---------- 4. resposta da IA ----------

    fun parseCandidates(text: String, sentenceCount: Int): List<Candidate> {
        val json = text.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        val array = when {
            json.startsWith("[") -> JSONArray(json)
            json.startsWith("{") -> {
                val root = JSONObject(json)
                root.optJSONArray("clips") ?: root.optJSONArray("highlights")
                    ?: throw HighlightDetectionException("Resposta da IA sem cortes")
            }
            else -> throw HighlightDetectionException("Resposta da IA não é JSON válido")
        }

        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val a = intField(item, "start_sentence", "startSentence") ?: return@mapNotNull null
            val b = intField(item, "end_sentence", "endSentence") ?: return@mapNotNull null
            val first = min(a, b)
            val last = max(a, b)
            if (first < 0 || last >= sentenceCount) return@mapNotNull null // índice inventado: ignora o corte

            fun score(vararg keys: String): Float {
                val value = keys.firstNotNullOfOrNull { k -> number(item, k) } ?: 50.0
                return (value.coerceIn(0.0, 100.0) / 100.0).toFloat()
            }

            Candidate(
                startSentence = first,
                endSentence = last,
                title = item.optString("title").trim().take(MAX_TITLE),
                hook = item.optString("hook").trim().take(MAX_REASON),
                reason = item.optString("reason").trim().take(MAX_REASON),
                score = score("overall_score", "overallScore", "score"),
                scores = HighlightScores(
                    hook = score("hook_score", "hookScore"),
                    coherence = score("coherence_score", "coherenceScore"),
                    value = score("value_score", "valueScore"),
                    trend = score("trend_score", "trendScore"),
                ),
            )
        }
    }

    private fun intField(item: JSONObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> number(item, key)?.toInt() }

    /** Número do JSON, aceitando também texto ("12"), que modelos às vezes devolvem. */
    private fun number(item: JSONObject, key: String): Double? = when (val value = item.opt(key)) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }

    // ---------- 5. refine ----------

    /**
     * Converte candidatos em [Highlight]s com tempos de frase, do melhor para o pior:
     * - corta frases do fim enquanto passar do máximo (preserva o gancho do início);
     * - anexa frases seguintes, depois anteriores, enquanto ficar abaixo do mínimo;
     * - uma frase única maior que o máximo é limitada ao máximo;
     * - folgas de [PAD_START_MS]/[PAD_END_MS] sem invadir a frase vizinha;
     * - descarta sobreposição acima de [MAX_OVERLAP] e conteúdo repetido acima de [MAX_TEXT_SIMILARITY].
     */
    fun refine(
        candidates: List<Candidate>,
        sentences: List<Sentence>,
        minDurationMs: Long,
        maxDurationMs: Long,
        maxCount: Int,
        projectDurationMs: Long,
    ): List<Highlight> {
        if (sentences.isEmpty()) return emptyList()
        val snapped = candidates.mapNotNull { snap(it, sentences, minDurationMs, maxDurationMs, projectDurationMs) }

        val chosen = mutableListOf<Highlight>()
        val chosenWords = mutableListOf<Set<String>>()
        for ((highlight, words) in snapped.sortedByDescending { it.first.score }) {
            if (chosen.any { overlap(it, highlight) > MAX_OVERLAP }) continue
            if (chosenWords.any { similar(it, words) }) continue // mesmo conteúdo dito de novo
            chosen += highlight
            chosenWords += words
            if (chosen.size >= maxCount) break
        }
        return chosen
    }

    private fun snap(
        c: Candidate,
        sentences: List<Sentence>,
        minDurationMs: Long,
        maxDurationMs: Long,
        projectDurationMs: Long,
    ): Pair<Highlight, Set<String>>? {
        var s = c.startSentence.coerceIn(0, sentences.lastIndex)
        var e = c.endSentence.coerceIn(s, sentences.lastIndex)
        fun dur(a: Int, b: Int) = sentences[b].endMs - sentences[a].startMs

        while (dur(s, e) > maxDurationMs && e > s) e--
        while (dur(s, e) < minDurationMs) {
            if (e + 1 < sentences.size && dur(s, e + 1) <= maxDurationMs) e++
            else if (s > 0 && dur(s - 1, e) <= maxDurationMs) s--
            else break
        }

        val prevEnd = if (s > 0) sentences[s - 1].endMs else 0L
        val nextStart = if (e + 1 < sentences.size) sentences[e + 1].startMs else projectDurationMs
        val start = max(prevEnd, sentences[s].startMs - PAD_START_MS).coerceAtLeast(0L)
        var end = min(nextStart, sentences[e].endMs + PAD_END_MS).coerceAtMost(projectDurationMs)
        end = min(end, start + maxDurationMs) // frase única mais longa que o máximo
        if (end - start < MIN_CLIP_MS) return null

        val words = (s..e).flatMapTo(mutableSetOf()) { tokens(sentences[it].text) }
        val highlight = Highlight(
            startMs = start,
            endMs = end,
            reason = c.reason.ifBlank { c.title }.ifBlank { "Momento de destaque" },
            score = c.score,
            source = HighlightSource.GEMINI,
            title = c.title,
            hook = c.hook,
            scores = c.scores,
        )
        return highlight to words
    }

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    private fun tokens(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(SPLIT).filter(String::isNotEmpty)

    private fun overlap(a: Highlight, b: Highlight): Float {
        val inter = min(a.endMs, b.endMs) - max(a.startMs, b.startMs)
        if (inter <= 0) return 0f
        return inter.toFloat() / max(1L, min(a.durationMs, b.durationMs))
    }

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val union = (a union b).size
        return (a intersect b).size.toFloat() / union > MAX_TEXT_SIMILARITY
    }
}
