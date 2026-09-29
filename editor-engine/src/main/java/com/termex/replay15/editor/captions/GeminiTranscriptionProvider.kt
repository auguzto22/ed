package com.termex.replay15.editor.captions

import com.recly.editor.engine.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.math.roundToLong

private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
private const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe"
private const val CORRECTION_MODEL = "gemini-3.8-flash"

private data class RetryableGeminiHttpError(val status: Int) : RuntimeException()

/** Direct Gemini client. It is instantiated only by the debug default factory. */
class GeminiTranscriptionProvider(
    private val apiKey: String,
    private val http: GeminiHttpClient = GeminiHttpClient(apiKey),
) : TranscriptionProvider {
    init { require(apiKey.isNotBlank()) }

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult = withContext(Dispatchers.IO) {
        request.checkCancelled()
        val file = try {
            http.withRetry(request.checkCancelled) {
                http.uploadFile(request.audioFile, request.mimeType, request.checkCancelled)
            }
        } catch (error: CaptionGenerationException) { throw error }
        try {
            request.checkCancelled()
            val response = http.withRetry(request.checkCancelled) {
                http.createInteraction(buildTranscriptionPayload(file.uri, request), request.checkCancelled)
            }
            val words = GeminiResponseParser.parseWordTimestamps(response)
            if (words.isEmpty()) throw TranscriptionFailed("A transcrição não retornou timestamps por palavra.")
            TranscriptionResult(words, request.languageCode.takeUnless { it == "auto" })
        } catch (error: CaptionGenerationException) { throw error }
        catch (error: Throwable) { throw TranscriptionFailed(cause = error) }
        finally { http.deleteFile(file.name) }
    }

    private fun buildTranscriptionPayload(uri: String, request: TranscriptionRequest): JSONObject {
        val transcriptionConfig = JSONObject()
            .put("mode", JSONObject()
                .put("type", "verbatim")
                .put("timestamp_granularities", JSONArray().put("word")))
        if (request.languageCode != "auto") {
            transcriptionConfig.put("language_codes", JSONArray().put(request.languageCode))
        }
        // Do not add custom_vocabulary here: the official API rejects it together with word timestamps.
        return JSONObject()
            .put("model", TRANSCRIBE_MODEL)
            .put("store", false)
            .put("input", JSONArray().put(JSONObject()
                .put("type", "audio")
                .put("uri", uri)
                .put("mime_type", request.mimeType)))
            .put("generation_config", JSONObject().put("transcription_config", transcriptionConfig))
    }
}

class GeminiContextCorrector(
    private val apiKey: String,
    private val http: GeminiHttpClient = GeminiHttpClient(apiKey),
) : CaptionContextCorrector {
    init { require(apiKey.isNotBlank()) }

    override suspend fun correct(
        segments: List<TimedCaptionSegment>,
        terms: Set<String>,
        projectContext: String?,
        checkCancelled: () -> Unit,
    ): List<TimedCaptionSegment> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()
        checkCancelled()
        val rows = JSONArray()
        segments.forEachIndexed { index, segment ->
            rows.put(JSONObject()
                .put("id", segment.id)
                .put("text", segment.text)
                .put("previous", segments.getOrNull(index - 1)?.text ?: JSONObject.NULL)
                .put("next", segments.getOrNull(index + 1)?.text ?: JSONObject.NULL))
        }
        val prompt = """
            Você está corrigindo uma transcrição de português brasileiro destinada a legendas de vídeo.
            Corrija apenas erros claros de reconhecimento, ortografia, nomes próprios, marcas,
            aplicativos e palavras em inglês que tenham sido ouvidas incorretamente.
            Preserve gírias, informalidade, palavrões, estilo de fala e significado original.
            Nunca invente informação, resuma ou transforme fala em português acadêmico.
            Use frases vizinhas somente como contexto. Se não houver confiança suficiente, mantenha o texto.
            Os horários são imutáveis. Retorne somente JSON válido, um array com exatamente os mesmos IDs,
            cada item no formato {"id":"...","text":"..."}.

            Termos personalizados: ${terms.filter(String::isNotBlank).joinToString(", ").ifBlank { "nenhum" }}
            Contexto do projeto: ${projectContext.orEmpty().take(1_000)}
            Segmentos: ${rows}
        """.trimIndent()
        val payload = JSONObject()
            .put("model", CORRECTION_MODEL)
            .put("store", false)
            .put("input", prompt)
            .put("response_format", JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json"))
        try {
            val response = http.withRetry(checkCancelled) { http.createInteraction(payload, checkCancelled) }
            val corrections = GeminiResponseParser.parseCorrections(response)
            val byId = corrections.associateBy { it.first }
            require(corrections.size == segments.size && byId.keys == segments.map { it.id }.toSet()) {
                "Resposta de correção incompleta"
            }
            segments.map { segment -> segment.withText(requireNotNull(byId[segment.id]).second) }
        } catch (error: CaptionGenerationException) { throw error }
        catch (error: Throwable) { throw CorrectionFailed(cause = error) }
    }
}

data class UploadedGeminiFile(val name: String, val uri: String)

class GeminiHttpClient(private val apiKey: String) {
    fun <T> withRetry(checkCancelled: () -> Unit, operation: () -> T): T {
        var delayMs = 1_000L
        repeat(3) { attempt ->
            try {
                checkCancelled()
                return operation()
            } catch (error: RetryableGeminiHttpError) {
                if (attempt == 2) {
                    if (error.status == 429) throw QuotaExceeded()
                    throw TranscriptionFailed("O serviço de transcrição está temporariamente indisponível.")
                }
                Thread.sleep(delayMs)
                delayMs *= 2
            } catch (error: UnknownHostException) {
                throw NoInternet(cause = error)
            } catch (error: ConnectException) {
                throw NoInternet(cause = error)
            } catch (error: SocketTimeoutException) {
                if (attempt == 2) throw TranscriptionFailed("O serviço de transcrição está temporariamente indisponível.", error)
                Thread.sleep(delayMs)
                delayMs *= 2
            }
        }
        error("retry loop did not return")
    }

    fun uploadFile(file: File, mimeType: String, checkCancelled: () -> Unit): UploadedGeminiFile {
        if (!file.isFile || file.length() <= 0L) throw UploadFailed("Arquivo de áudio vazio")
        val start = open("$GEMINI_BASE_URL/upload/v1beta/files", "POST")
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
        start.setRequestProperty("X-Goog-Upload-Command", "start")
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", file.length().toString())
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mimeType)
        start.setRequestProperty("Content-Type", "application/json")
        start.doOutput = true
        start.outputStream.use { it.write(JSONObject().put("file", JSONObject().put("display_name", file.name)).toString().toByteArray()) }
        val uploadUrlHeader = start.getHeaderField("X-Goog-Upload-URL")
            ?: start.getHeaderField("x-goog-upload-url")
        val startBody = readChecked(start, checkCancelled)
        val uploadUrl = uploadUrlHeader
            ?: runCatching { JSONObject(startBody).optString("upload_url").takeIf(String::isNotBlank) }.getOrNull()
            ?: throw UploadFailed("O serviço não retornou o endereço de upload")
        val upload = open(uploadUrl, "POST")
        upload.setRequestProperty("Content-Length", file.length().toString())
        upload.setRequestProperty("X-Goog-Upload-Offset", "0")
        upload.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        upload.setRequestProperty("Content-Type", mimeType)
        upload.doOutput = true
        file.inputStream().buffered(64 * 1024).use { input ->
            upload.outputStream.use { output -> copyStreaming(input, output, file.length(), checkCancelled) }
        }
        val response = readChecked(upload, checkCancelled)
        return runCatching {
            val fileJson = JSONObject(response).optJSONObject("file") ?: JSONObject(response)
            UploadedGeminiFile(fileJson.optString("name"), fileJson.optString("uri"))
        }.getOrElse { throw UploadFailed(cause = it) }
            .also { require(it.name.isNotBlank() && it.uri.isNotBlank()) { "Upload sem URI" } }
    }

    fun createInteraction(payload: JSONObject, checkCancelled: () -> Unit): JSONObject {
        val connection = open("$GEMINI_BASE_URL/v1beta/interactions", "POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(payload.toString().toByteArray()) }
        return JSONObject(readChecked(connection, checkCancelled))
    }

    fun deleteFile(name: String) {
        if (name.isBlank()) return
        runCatching { open("$GEMINI_BASE_URL/v1beta/$name", "DELETE").inputStream.close() }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        try {
            return (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 180_000
                useCaches = false
                setRequestProperty("x-goog-api-key", apiKey)
            }
        } catch (error: UnknownHostException) { throw NoInternet(cause = error) }
    }

    private fun readChecked(connection: HttpURLConnection, checkCancelled: () -> Unit): String {
        try {
            val status = connection.responseCode
            checkCancelled()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw mapHttpError(status, body)
            return body
        } finally { connection.disconnect() }
    }

    private fun copyStreaming(input: BufferedInputStream, output: OutputStream, total: Long, checkCancelled: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            if (copied == total || copied % (4L * 1024L * 1024L) < read) checkCancelled()
        }
    }

    private fun mapHttpError(status: Int, body: String): RuntimeException = when (status) {
        401, 403 -> InvalidApiKey()
        429 -> RetryableGeminiHttpError(status)
        400, 404, 413, 415 -> TranscriptionFailed("Requisição de transcrição inválida.")
        in 500..599 -> RetryableGeminiHttpError(status)
        else -> TranscriptionFailed("Falha de comunicação com o serviço de transcrição.")
    }
}

object GeminiResponseParser {
    fun parseWordTimestamps(response: JSONObject): List<WordTimestamp> {
        val result = mutableListOf<WordTimestamp>()
        walk(response) { value ->
            if (value !is JSONObject) return@walk
            val type = value.optString("type")
            val word = value.optString("word").ifBlank { value.optString("text") }
            if (type == "word_info" || (word.isNotBlank() && value.has("start_offset") && value.has("end_offset"))) {
                val start = parseDurationUs(value.opt("start_offset") ?: value.opt("startOffset"))
                val end = parseDurationUs(value.opt("end_offset") ?: value.opt("endOffset"))
                if (start != null && end != null && end > start && word.isNotBlank()) result += WordTimestamp(word, start, end)
            }
        }
        return result.distinctBy { Triple(it.word, it.startUs, it.endUs) }.sortedBy { it.startUs }
    }

    fun parseCorrections(response: JSONObject): List<Pair<String, String>> {
        val text = outputText(response).trim()
        val json = text.removePrefix("```").removePrefix("json").removeSuffix("```").trim()
        val array = when {
            json.startsWith("[") -> JSONArray(json)
            json.startsWith("{") -> JSONObject(json).optJSONArray("segments")
                ?: JSONObject(json).optJSONArray("corrections")
                ?: throw CorrectionFailed("Resposta de correção sem segmentos")
            else -> throw CorrectionFailed("Resposta de correção não é JSON")
        }
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: throw CorrectionFailed("Item de correção inválido")
            val id = item.optString("id")
            val corrected = item.optString("text").trim()
            if (id.isBlank() || corrected.isBlank()) throw CorrectionFailed("Correção sem ID ou texto")
            id to corrected
        }
    }

    private fun outputText(response: JSONObject): String {
        val direct = response.optString("output_text").takeIf(String::isNotBlank)
        if (direct != null) return direct
        var found: String? = null
        walk(response) { value ->
            if (found == null && value is JSONObject) {
                found = value.optString("text").takeIf(String::isNotBlank)
            }
        }
        return found.orEmpty()
    }

    private fun walk(value: Any?, visit: (Any?) -> Unit) {
        visit(value)
        when (value) {
            is JSONObject -> value.keys().forEach { key -> walk(value.opt(key), visit) }
            is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index), visit)
        }
    }

    private fun parseDurationUs(value: Any?): Long? = when (value) {
        is Number -> (value.toDouble() * 1_000_000.0).roundToLong()
        is String -> value.removeSuffix("s").toDoubleOrNull()?.let { (it * 1_000_000.0).roundToLong() }
        is JSONObject -> {
            val seconds = value.optLong("seconds", Long.MIN_VALUE)
            val nanos = value.optLong("nanos", 0L)
            if (seconds == Long.MIN_VALUE) null else seconds * 1_000_000L + nanos / 1_000L
        }
        else -> null
    }
}
