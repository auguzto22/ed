package com.termex.replay15.editor.autoedit

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One downloadable search result. Only https URLs are ever kept. */
data class StockImage(val url: String, val width: Int, val height: Int, val source: String) {
    init { require(url.startsWith("https://") && width >= 0 && height >= 0 && source.isNotBlank()) }
}

/** A free stock-photo library. Implementations run on a worker thread and never touch the UI. */
interface StockImageSearch {
    val name: String
    fun search(query: String, tall: Boolean, cancellation: AutoEditCancellation): List<StockImage>
}

/**
 * Pexels (free license, no attribution required). Needs an API key, read from the untracked
 * local.properties as PEXELS_API_KEY in debug builds (see docs/auto-edit.md).
 */
class PexelsImageSearch(private val apiKey: String) : StockImageSearch {
    init { require(apiKey.isNotBlank()) }
    override val name = "Pexels"
    override fun search(query: String, tall: Boolean, cancellation: AutoEditCancellation): List<StockImage> {
        val url = "https://api.pexels.com/v1/search?query=${StockHttp.encode(query)}" +
            "&orientation=${if (tall) "portrait" else "landscape"}&per_page=8"
        return StockImageParsers.pexels(StockHttp.getText(url, mapOf("Authorization" to apiKey), cancellation), tall)
    }
}

/**
 * Openverse (WordPress/Creative Commons). Works without a key; restricted here to CC0 and public
 * domain images so the exported video needs no credit line.
 */
class OpenverseImageSearch : StockImageSearch {
    override val name = "Openverse"
    override fun search(query: String, tall: Boolean, cancellation: AutoEditCancellation): List<StockImage> {
        val url = "https://api.openverse.org/v1/images/?q=${StockHttp.encode(query)}" +
            "&license=cc0,pdm&mature=false&page_size=12&aspect_ratio=${if (tall) "tall" else "wide"}"
        return StockImageParsers.openverse(StockHttp.getText(url, emptyMap(), cancellation))
    }
}

object StockImageParsers {
    private val freeLicenses = setOf("cc0", "pdm")

    fun pexels(body: String, tall: Boolean): List<StockImage> {
        val photos = JSONObject(body).optJSONArray("photos") ?: return emptyList()
        return (0 until photos.length()).mapNotNull { index ->
            val photo = photos.optJSONObject(index) ?: return@mapNotNull null
            val src = photo.optJSONObject("src") ?: return@mapNotNull null
            // large2x (~1880 px wide) leaves enough pixels for our own 9:16 / 16:9 crop; Pexels'
            // "portrait" crop is only 800x1200, so it is a fallback.
            val url = listOf("large2x", "large", if (tall) "portrait" else "landscape", "original")
                .map { src.optString(it) }.firstOrNull { it.startsWith("https://") } ?: return@mapNotNull null
            StockImage(url, photo.optInt("width").coerceAtLeast(0), photo.optInt("height").coerceAtLeast(0), "Pexels")
        }
    }

    fun openverse(body: String): List<StockImage> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            if (item.optBoolean("mature", false)) return@mapNotNull null
            if (item.optString("license").lowercase() !in freeLicenses) return@mapNotNull null
            val url = item.optString("url").takeIf { it.startsWith("https://") }
                ?: item.optString("thumbnail").takeIf { it.startsWith("https://") }
                ?: return@mapNotNull null
            if (url.substringAfterLast('.').lowercase() in setOf("svg", "gif")) return@mapNotNull null
            StockImage(url, item.optInt("width").coerceAtLeast(0), item.optInt("height").coerceAtLeast(0), "Openverse")
        }
    }
}

/** Small, bounded HTTPS client for the stock libraries. */
internal object StockHttp {
    const val MAX_JSON_BYTES = 2L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
    private const val USER_AGENT = "Recly/1.0 (Android; Auto Edit B-roll)"

    fun encode(query: String): String = URLEncoder.encode(query.trim().take(80), "UTF-8")

    fun getText(url: String, headers: Map<String, String>, cancellation: AutoEditCancellation): String =
        String(get(url, headers, MAX_JSON_BYTES, "application/json", cancellation), Charsets.UTF_8)

    fun getImage(url: String, cancellation: AutoEditCancellation): ByteArray = get(url, emptyMap(), MAX_IMAGE_BYTES, "image/*", cancellation)

    private fun get(url: String, headers: Map<String, String>, maxBytes: Long, accept: String, cancellation: AutoEditCancellation): ByteArray {
        require(url.startsWith("https://")) { "Somente HTTPS" }
        cancellation.check()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val status = connection.responseCode
            cancellation.check()
            check(status in 200..299) { "HTTP $status" }
            if (accept.startsWith("image/")) {
                val type = connection.contentType.orEmpty().lowercase()
                check(type.isEmpty() || type.startsWith("image/") || type.startsWith("application/octet-stream")) { "Resposta nao e imagem" }
            }
            val declared = connection.contentLengthLong
            check(declared <= maxBytes) { "Arquivo grande demais" }
            return connection.inputStream.use { readBounded(it, maxBytes, cancellation) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Long, cancellation: AutoEditCancellation): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            cancellation.check()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= maxBytes) { "Arquivo grande demais" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
