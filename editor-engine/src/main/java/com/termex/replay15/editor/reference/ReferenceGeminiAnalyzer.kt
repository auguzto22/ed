package com.termex.replay15.editor.reference

import android.content.Context
import android.net.Uri
import com.termex.replay15.editor.captions.GeminiHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val REFERENCE_MODEL = "gemini-3.8-flash"
private const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024
private const val ACTIVE_TIMEOUT_MS = 120_000L

/**
 * Semantic pass over the reference: the video goes to Gemini, which describes titles, cards,
 * overlays, B-roll, the caption track and punch-ins as JSON. Local detectors cannot tell a
 * news screenshot from a sticker; this pass can. The uploaded file is deleted afterwards and
 * nothing from the reference is ever copied into a project.
 *
 * Only built when a Gemini key exists (debug builds); otherwise the analysis is local only.
 */
class ReferenceGeminiAnalyzer(private val context: Context, apiKey: String, private val http: GeminiHttpClient = GeminiHttpClient(apiKey)) {
    init { require(apiKey.isNotBlank()) }

    fun analyze(info: ReferenceMediaInfo, cancellation: ReferenceAnalysisCancellation): ReferenceGeminiParser.Result {
        require(info.sizeBytes <= 0 || info.sizeBytes <= MAX_UPLOAD_BYTES) { "Video de referencia grande demais para a analise por IA" }
        val mime = context.contentResolver.getType(Uri.parse(info.uri))?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        val directory = File(context.cacheDir, "reference-upload").apply { check(isDirectory || mkdirs()) }
        val copy = File.createTempFile("reference-", ".video", directory)
        var uploadedName = ""
        try {
            context.contentResolver.openInputStream(Uri.parse(info.uri))!!.use { input ->
                copy.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        cancellation.check()
                        val read = input.read(buffer); if (read < 0) break
                        total += read; require(total <= MAX_UPLOAD_BYTES) { "Video de referencia grande demais para a analise por IA" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            val uploaded = http.withRetry(cancellation::check) { http.uploadFile(copy, mime, cancellation::check) }
            uploadedName = uploaded.name
            waitUntilActive(uploaded.name, cancellation)
            val prompt = ReferenceGeminiParser.PROMPT.trim() + "\nThe video lasts %.2f seconds and is %dx%d.".format(info.durationUs / 1e6, info.width, info.height)
            val payload = JSONObject()
                .put("model", REFERENCE_MODEL)
                .put("store", false)
                .put("input", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "video").put("uri", uploaded.uri).put("mime_type", mime)))
                .put("response_format", JSONObject().put("type", "text").put("mime_type", "application/json"))
            val response = http.withRetry(cancellation::check) { http.createInteraction(payload, cancellation::check) }
            cancellation.check()
            return ReferenceGeminiParser.parse(outputText(response), info.durationUs)
        } finally {
            copy.delete()
            if (uploadedName.isNotBlank()) http.deleteFile(uploadedName)
        }
    }

    private fun waitUntilActive(name: String, cancellation: ReferenceAnalysisCancellation) {
        val deadline = System.currentTimeMillis() + ACTIVE_TIMEOUT_MS
        var delay = 1_000L
        while (true) {
            cancellation.check()
            val file = http.getFile(name, cancellation::check)
            val state = (file.optJSONObject("file") ?: file).optString("state")
            when (state) {
                "ACTIVE", "" -> return
                "FAILED" -> error("O Gemini nao conseguiu processar o video de referencia")
            }
            check(System.currentTimeMillis() < deadline) { "O Gemini demorou demais para processar o video" }
            Thread.sleep(delay); delay = (delay * 3 / 2).coerceAtMost(4_000L)
        }
    }

    private fun outputText(response: JSONObject): String {
        response.optString("output_text").takeIf(String::isNotBlank)?.let { return it }
        // The last text part is the model's answer (earlier ones may echo the input).
        val texts = mutableListOf<String>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> { value.optString("text").takeIf(String::isNotBlank)?.let(texts::add); value.keys().forEach { walk(value.opt(it)) } }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(response.opt("outputs") ?: response.opt("output") ?: response)
        return texts.lastOrNull { it.contains('{') } ?: error("Resposta vazia do Gemini")
    }
}
