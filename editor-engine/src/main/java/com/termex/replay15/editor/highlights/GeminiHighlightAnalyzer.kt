package com.termex.replay15.editor.highlights

import com.termex.replay15.editor.captions.GeminiHttpClient
import com.termex.replay15.editor.captions.InvalidApiKey
import com.termex.replay15.editor.captions.NoInternet
import com.termex.replay15.editor.captions.QuotaExceeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val HIGHLIGHT_MODEL = "gemini-3.8-flash"

/**
 * Gemini-powered highlight detection, com a curadoria do ClipStudio ([HighlightCuration]).
 *
 * Envia a transcrição numerada por frase, com marcas de pausa, energia e troca de cena.
 * O Gemini devolve só os números das frases de início e fim; os tempos vêm das frases,
 * então ele não reconstrói nem inventa timestamps.
 *
 * When Gemini is unavailable, the caller falls back to [LocalHighlightAnalyzer].
 *
 * **IMPORTANT**: Only segment text and IDs are sent to Gemini.
 * No audio, video, or frames are transmitted.
 */
class GeminiHighlightAnalyzer(
    private val apiKey: String,
    private val http: GeminiHttpClient = GeminiHttpClient(apiKey),
) : HighlightAnalyzer {
    init { require(apiKey.isNotBlank()) }

    override suspend fun analyze(
        request: HighlightRequest,
        checkCancelled: () -> Unit,
    ): HighlightResult = withContext(Dispatchers.IO) {
        val sentences = HighlightCuration.sentences(request.transcriptSegments)
        if (sentences.isEmpty()) {
            throw HighlightNoTranscript()
        }
        checkCancelled()

        val payload = buildPayload(request, sentences)
        val response = try {
            http.withRetry(checkCancelled) {
                http.createInteraction(payload, checkCancelled)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InvalidApiKey) {
            throw HighlightGeminiUnavailable(error.message.orEmpty(), error)
        } catch (error: NoInternet) {
            throw HighlightGeminiUnavailable(error.message.orEmpty(), error)
        } catch (error: QuotaExceeded) {
            throw HighlightGeminiUnavailable(error.message.orEmpty(), error)
        } catch (error: Throwable) {
            throw HighlightGeminiUnavailable(cause = error)
        }

        checkCancelled()
        val candidates = HighlightCuration.parseCandidates(extractOutputText(response), sentences.size)
        val highlights = HighlightCuration.refine(
            candidates, sentences, request.minDurationMs, request.maxDurationMs,
            request.maxHighlights, request.projectDurationMs,
        )
        if (highlights.isEmpty()) {
            // Sem corte válido da IA: o HighlightDetector cai para a análise local.
            throw HighlightDetectionException("A IA não sugeriu nenhum trecho válido para a duração pedida")
        }
        val warnings = if (highlights.size < request.maxHighlights && candidates.size > highlights.size) {
            listOf("A IA sugeriu ${highlights.size} trecho(s) válido(s) de ${request.maxHighlights} pedidos")
        } else {
            emptyList()
        }

        HighlightResult(highlights = highlights, source = HighlightSource.GEMINI, warnings = warnings)
    }

    private fun buildPayload(request: HighlightRequest, sentences: List<HighlightCuration.Sentence>): JSONObject {
        val marks = HighlightCuration.marks(sentences, request.audioEnergy, request.sceneCutsMs)
        // Pede o dobro: o refine descarta sobreposições e repetições e fica com os melhores.
        val wanted = (request.maxHighlights * 2).coerceAtMost(50)
        val prompt = HighlightCuration.prompt(
            sentences, marks, wanted, request.minDurationMs, request.maxDurationMs, request.languageCode,
        )
        return JSONObject()
            .put("model", HIGHLIGHT_MODEL)
            .put("store", false)
            .put("input", prompt)
            .put("response_format", JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json"))
    }

    private fun extractOutputText(response: JSONObject): String {
        val direct = response.optString("output_text").takeIf(String::isNotBlank)
        if (direct != null) return direct
        // Walk the response tree to find text content
        return walkForText(response).orEmpty()
    }

    private fun walkForText(obj: Any?): String? {
        when (obj) {
            is JSONObject -> {
                val text = obj.optString("text").takeIf(String::isNotBlank)
                if (text != null) return text
                for (key in obj.keys()) {
                    val found = walkForText(obj.opt(key))
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    val found = walkForText(obj.opt(i))
                    if (found != null) return found
                }
            }
        }
        return null
    }
}

