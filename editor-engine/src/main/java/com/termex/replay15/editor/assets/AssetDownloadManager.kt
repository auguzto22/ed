package com.termex.replay15.editor.assets

import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

class AssetDownloadManager {
    val cancelled = AtomicBoolean(false)
    private fun connection(url: String): HttpsURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https" && parsed.userInfo == null && parsed.host.isNotBlank()) { "Use um endereco HTTPS" }
        val connection = (parsed.openConnection() as HttpsURLConnection)
        try { return connection.apply {
            instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 15_000
            require(responseCode == 200) { "Servidor retornou erro. Redirecionamentos nao sao aceitos." }
        } } catch (e: Exception) { connection.disconnect(); throw e }
    }
    fun catalog(url: String): List<OnlineAsset> {
        val conn = connection(url)
        try {
            val bytes = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16_384)
                while (true) { check(!cancelled.get()) { "Cancelado" }; val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 1_000_000); output.write(buffer, 0, n) }; output.toByteArray()
            }
            val root = JSONObject(String(bytes, Charsets.UTF_8)); require(root.getInt("engineVersion") == 1)
            val entries = root.getJSONArray("assets"); require(entries.length() <= 500)
            return List(entries.length()) { n -> val item = entries.getJSONObject(n)
                require(item.getString("type") == "EFFECT" && item.getInt("engineVersion") == 1)
                require(!item.optBoolean("premium", false)) { "Catalogo inclui pacotes pagos sem licenca configurada" }
                OnlineAsset(item.getString("id"), item.getInt("version"), item.getString("name"), item.getString("downloadUrl"), item.getLong("size"), item.getString("sha256")) }
        } finally { conn.disconnect() }
    }
    fun download(asset: OnlineAsset, destination: File, progress: (Int) -> Unit) {
        require(asset.bytes in 1..AssetValidator.MAX_PACKAGE && asset.sha256.matches(Regex("[A-Fa-f0-9]{64}")))
        val conn = connection(asset.url)
        try {
            conn.inputStream.use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(32_768); var total = 0L; var reported = -1
                while (true) { check(!cancelled.get()) { "Cancelado" }; val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= asset.bytes); output.write(buffer, 0, n)
                    val percent = (100 * total / asset.bytes).toInt()
                    if (percent != reported) { reported = percent; progress(percent) }
                }
            } }
            AssetValidator.verify(destination, asset.bytes, asset.sha256)
        } catch (e: Exception) { destination.delete(); throw e } finally { conn.disconnect() }
    }
}
