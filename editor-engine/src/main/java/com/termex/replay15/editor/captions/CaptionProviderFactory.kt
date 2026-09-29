package com.termex.replay15.editor.captions

import com.recly.editor.engine.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Production boundary. The APK never receives a Gemini key: the backend owns Gemini credentials
 * and returns the same provider-neutral word/timestamp contract.
 */
class ReclyBackendTranscriptionProvider(private val baseUrl: String) : TranscriptionProvider {
    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult = withContext(Dispatchers.IO) {
        requireBackend()
        val boundary = "----ReclyCaption${UUID.randomUUID()}"
        val connection = open("$baseUrl/v1/captions/transcribe", boundary)
        try {
            connection.outputStream.use { output ->
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"language_code\"\r\n\r\n${request.languageCode}\r\n".toByteArray())
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"audio\"; filename=\"${request.audioFile.name}\"\r\n".toByteArray())
                output.write("Content-Type: ${request.mimeType}\r\n\r\n".toByteArray())
                request.audioFile.inputStream().buffered(64 * 1024).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        request.checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw TranscriptionFailed("O backend de legendas recusou a solicitação.")
            val json = JSONObject(body)
            val words = json.optJSONArray("words") ?: throw TranscriptionFailed("Backend sem timestamps por palavra")
            TranscriptionResult((0 until words.length()).map { index ->
                val word = words.getJSONObject(index)
                WordTimestamp(word.getString("word"), word.getLong("startUs"), word.getLong("endUs"))
            }, json.optString("languageCode").takeIf(String::isNotBlank))
        } finally { connection.disconnect() }
    }

    private fun requireBackend() {
        if (!baseUrl.startsWith("https://") || baseUrl.removePrefix("https://").isBlank()) {
            throw CaptionBackendNotConfigured()
        }
    }

    private fun open(path: String, boundary: String): HttpURLConnection =
        (URL(path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
}

class ReclyBackendContextCorrector(private val baseUrl: String) : CaptionContextCorrector {
    override suspend fun correct(
        segments: List<TimedCaptionSegment>,
        terms: Set<String>,
        projectContext: String?,
        checkCancelled: () -> Unit,
    ): List<TimedCaptionSegment> = withContext(Dispatchers.IO) {
        if (!baseUrl.startsWith("https://")) throw CaptionBackendNotConfigured()
        val connection = (URL("$baseUrl/v1/captions/correct").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val rows = JSONArray()
        segments.forEach { rows.put(JSONObject().put("id", it.id).put("text", it.text)) }
        val termArray = JSONArray()
        terms.forEach { termArray.put(it) }
        connection.outputStream.use { output ->
            output.write(JSONObject().put("segments", rows).put("terms", termArray).toString().toByteArray())
        }
        try {
            checkCancelled()
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw CorrectionFailed()
            val corrected = GeminiResponseParser.parseCorrections(JSONObject().put("output_text", body))
                .associateBy { it.first }
            require(corrected.size == segments.size && corrected.keys == segments.map { it.id }.toSet())
            segments.map { it.withText(requireNotNull(corrected[it.id]).second) }
        } finally { connection.disconnect() }
    }
}

object CaptionProviderFactory {
    fun transcriptionProvider(): TranscriptionProvider {
        return if (BuildConfig.BUILD_TYPE == "debug") {
            val key = BuildConfig.GEMINI_API_KEY
            if (key.isBlank()) MissingGeminiApiKeyProvider() else GeminiTranscriptionProvider(key)
        } else ReclyBackendTranscriptionProvider(BuildConfig.RECLY_CAPTION_BACKEND_URL)
    }

    fun contextCorrector(): CaptionContextCorrector? {
        return if (BuildConfig.BUILD_TYPE == "debug") {
            BuildConfig.GEMINI_API_KEY.takeIf(String::isNotBlank)?.let(::GeminiContextCorrector)
        } else BuildConfig.RECLY_CAPTION_BACKEND_URL.takeIf(String::isNotBlank)?.let(::ReclyBackendContextCorrector)
    }
}

private class MissingGeminiApiKeyProvider : TranscriptionProvider {
    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult {
        throw InvalidApiKey("Gemini API key não configurada. Adicione GEMINI_API_KEY ao local.properties.")
    }
}
