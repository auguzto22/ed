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
 * Gemini-powered highlight detection.
 *
 * Sends the project transcript (text only, not audio) and asks Gemini to identify
 * the most interesting, engaging, or important moments. Timestamps are preserved
 * from the original segments — Gemini does NOT reconstruct them.
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
        if (request.transcriptSegments.isEmpty()) {
            throw HighlightNoTranscript()
        }
        checkCancelled()

        val payload = buildPayload(request)
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
        val highlights = parseHighlights(response, request)

        HighlightResult(
            highlights = highlights.take(request.maxHighlights),
            source = HighlightSource.GEMINI,
        )
    }

    private fun buildPayload(request: HighlightRequest): JSONObject {
        // Build a compact transcript representation with IDs for traceability
        val segments = JSONArray()
        request.transcriptSegments.forEach { segment ->
            segments.put(JSONObject()
                .put("id", segment.id)
                .put("text", segment.text)
                .put("startMs", segment.startMs)
                .put("endMs", segment.endMs))
        }

        val languageName = when (request.languageCode) {
            "pt-BR" -> "português brasileiro"
            "en-US" -> "inglês"
            else -> request.languageCode
        }

        val prompt = """
            Você é um assistente de edição de vídeo. Analise a transcrição abaixo e identifique os melhores momentos do vídeo.

            Critérios para um bom momento:
            - Frases impactantes, engraçadas, emocionais ou informativas
            - Mudanças de assunto ou energia
            - Momentos-chave de uma narrativa
            - Trechos com potencial viral ou que funcionariam bem como cortes curtos

            Idioma: $languageName
            Duração total: ${request.projectDurationMs / 1_000}s
            Máximo de destaques: ${request.maxHighlights}
            Duração mínima de cada destaque: ${request.minDurationMs / 1_000}s
            Duração máxima de cada destaque: ${request.maxDurationMs / 1_000}s

            Regras:
            - Retorne APENAS JSON válido
            - Cada destaque deve ter startMs e endMs correspondentes aos segmentos
            - Não invente texto ou timestamps que não existam na transcrição
            - A razão deve ser curta (máximo 100 caracteres) e em $languageName
            - O score é de 0.0 a 1.0, onde 1.0 é extremamente interessante

            Formato de resposta (array JSON):
            [{"startMs": 1000, "endMs": 15000, "reason": "...", "score": 0.9}]

            Transcrição:
            $segments
        """.trimIndent()

        return JSONObject()
            .put("model", HIGHLIGHT_MODEL)
            .put("store", false)
            .put("input", prompt)
            .put("response_format", JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json"))
    }

    private fun parseHighlights(response: JSONObject, request: HighlightRequest): List<Highlight> {
        val text = extractOutputText(response).trim()
        val json = text
            .removePrefix("```").removePrefix("json")
            .removeSuffix("```").trim()

        val array = when {
            json.startsWith("[") -> JSONArray(json)
            json.startsWith("{") -> {
                val root = JSONObject(json)
                root.optJSONArray("highlights")
                    ?: root.optJSONArray("moments")
                    ?: root.optJSONArray("results")
                    ?: throw HighlightDetectionException("Resposta sem destaques")
            }
            else -> throw HighlightDetectionException("Resposta não é JSON válido")
        }

        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val startMs = item.optLong("startMs", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
            val endMs = item.optLong("endMs", -1L).takeIf { it > startMs } ?: return@mapNotNull null
            val reason = item.optString("reason").takeIf(String::isNotBlank)
                ?: item.optString("motivo").takeIf(String::isNotBlank)
                ?: "Momento de destaque"
            val score = item.optDouble("score", 0.5).toFloat().coerceIn(0f, 1f)

            // Validate against project bounds and duration limits
            val clampedStart = startMs.coerceIn(0, request.projectDurationMs)
            val clampedEnd = endMs.coerceIn(clampedStart + 1, request.projectDurationMs)
            if (clampedEnd - clampedStart < request.minDurationMs) return@mapNotNull null

            Highlight(
                startMs = clampedStart,
                endMs = clampedEnd.coerceAtMost(clampedStart + request.maxDurationMs),
                reason = reason.take(500),
                score = score,
                source = HighlightSource.GEMINI,
            )
        }
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

