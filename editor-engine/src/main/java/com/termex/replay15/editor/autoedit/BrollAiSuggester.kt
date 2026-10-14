package com.termex.replay15.editor.autoedit

import com.termex.replay15.editor.captions.GeminiHttpClient
import org.json.JSONArray
import org.json.JSONObject

private const val BROLL_MODEL = "gemini-3.8-flash"

/**
 * Asks Gemini which spoken words deserve a picture. Only the transcript text and word times are
 * sent; no audio or frames. A failure returns null (and an empty answer an empty list) so the
 * caller falls back to the local dictionary ([BrollLexicon]) instead of failing the Auto Edit.
 */
class BrollAiSuggester(apiKey: String, private val http: GeminiHttpClient = GeminiHttpClient(apiKey)) {
    init { require(apiKey.isNotBlank()) }

    /** Why the last [suggest] returned null. */
    var lastError: String? = null
        private set

    /** Suggestions, or null when the AI pass could not run (the reason is in [lastError]). */
    fun suggest(words: List<TimedWord>, durationUs: Long, languageCode: String, cancellation: AutoEditCancellation): List<BrollSuggestion>? {
        if (words.isEmpty()) return emptyList()
        cancellation.check()
        val payload = JSONObject()
            .put("model", BROLL_MODEL)
            .put("store", false)
            .put("input", BrollSuggestionParser.prompt(words, durationUs, BrollPlanner.budget(durationUs) * 2, languageCode))
            .put("response_format", JSONObject().put("type", "text").put("mime_type", "application/json"))
        return try {
            val response = http.withRetry(cancellation::check) { http.createInteraction(payload, cancellation::check) }
            cancellation.check()
            BrollSuggestionParser.parse(outputText(response), words, durationUs)
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            lastError = error.message ?: error.javaClass.simpleName
            null
        }
    }

    private fun outputText(response: JSONObject): String {
        response.optString("output_text").takeIf(String::isNotBlank)?.let { return it }
        val texts = mutableListOf<String>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optString("text").takeIf(String::isNotBlank)?.let(texts::add)
                    value.keys().forEach { walk(value.opt(it)) }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(response.opt("outputs") ?: response.opt("output") ?: response)
        // The last JSON-looking text is the answer; earlier parts may echo the prompt.
        return texts.lastOrNull { it.contains('{') || it.contains('[') } ?: error("Resposta vazia do Gemini")
    }
}
