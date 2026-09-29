package com.termex.replay15.editor.font

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resilient HTTP downloader for remote font files.
 *
 * Downloads are written to a temporary file first, then validated (magic bytes + SHA-256)
 * before being committed to the [FontCache]. Partial or corrupted downloads are cleaned up
 * immediately.
 */
class FontDownloader(private val cache: FontCache) {

    /** Error types emitted by the downloader. */
    enum class DownloadError { NETWORK_OFFLINE, TIMEOUT, CORRUPTED_FILE, IO_ERROR }

    /** Result of a download attempt. */
    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Failure(val error: DownloadError, val message: String = "") : DownloadResult()
    }

    /**
     * Download the font identified by [metadata] and commit it to the disk cache.
     *
     * @param onProgress Callback invoked with progress in `[0.0, 1.0]`. The value is
     *                   approximate when the server doesn't send `Content-Length`.
     * @return [DownloadResult.Success] with the cached [File], or [DownloadResult.Failure].
     */
    fun download(
        metadata: FontMetadata,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult {
        if (metadata.remoteUrl.isBlank()) {
            return DownloadResult.Failure(DownloadError.IO_ERROR, "No remote URL for ${metadata.id}")
        }

        // Already cached?
        cache.diskFile(metadata.id)?.let { existing ->
            onProgress(1f)
            return DownloadResult.Success(existing)
        }

        val tempFile = File(cache.diskFile("")?.parentFile ?: return DownloadResult.Failure(
            DownloadError.IO_ERROR, "Cache directory unavailable"
        ), "${metadata.id}.tmp")

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(metadata.remoteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return DownloadResult.Failure(
                    DownloadError.IO_ERROR,
                    "HTTP $responseCode for ${metadata.remoteUrl}"
                )
            }

            val contentLength = connection.contentLengthLong
            var totalRead = 0L
            val buffer = ByteArray(8192)

            tempFile.outputStream().use { output ->
                connection.inputStream.use { input ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        if (contentLength > 0) {
                            onProgress((totalRead.toFloat() / contentLength).coerceAtMost(0.99f))
                        }
                    }
                }
            }

            if (totalRead == 0L) {
                tempFile.delete()
                return DownloadResult.Failure(DownloadError.CORRUPTED_FILE, "Empty download")
            }

            // Commit through the cache which validates magic bytes + SHA-256.
            val committed = cache.commitDownload(metadata.id, tempFile, metadata.sha256)
            return if (committed != null) {
                onProgress(1f)
                DownloadResult.Success(committed)
            } else {
                DownloadResult.Failure(DownloadError.CORRUPTED_FILE, "Validation failed for ${metadata.id}")
            }
        } catch (e: java.net.SocketTimeoutException) {
            tempFile.delete()
            Log.w(TAG, "Timeout downloading ${metadata.id}", e)
            return DownloadResult.Failure(DownloadError.TIMEOUT, e.message ?: "")
        } catch (e: java.net.UnknownHostException) {
            tempFile.delete()
            Log.w(TAG, "Offline: ${metadata.id}", e)
            return DownloadResult.Failure(DownloadError.NETWORK_OFFLINE, e.message ?: "")
        } catch (e: IOException) {
            tempFile.delete()
            Log.w(TAG, "IO error downloading ${metadata.id}", e)
            return DownloadResult.Failure(DownloadError.IO_ERROR, e.message ?: "")
        } finally {
            connection?.disconnect()
            // Ensure no orphan temp files
            if (tempFile.exists()) tempFile.delete()
        }
    }

    companion object {
        private const val TAG = "FontDownloader"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
