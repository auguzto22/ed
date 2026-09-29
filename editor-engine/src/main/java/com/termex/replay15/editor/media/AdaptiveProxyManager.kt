package com.termex.replay15.editor.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import com.termex.replay15.editor.core.PreviewPerformanceController
import com.termex.replay15.editor.domain.VideoClip
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Manages lightweight, non-blocking background proxy generation for preview.
 *
 * Proxies are generated ONLY when media complexity (4K, heavy HEVC, high FPS, multi-layer)
 * exceeds hardware decoder capabilities.
 *
 * The editor opens immediately using the original media with adaptive preview resolution;
 * when the 720p H.264 proxy is ready, it is hot-swapped into the preview pipeline.
 * Export ALWAYS uses the original source media.
 */
@UnstableApi
object AdaptiveProxyManager {
    private const val TAG = "ReclyProxyManager"
    private const val PROXY_DIR_NAME = "editor-proxies"
    private const val MAX_PROXY_CACHE_BYTES = 500L * 1024L * 1024L // 500MB
    private const val MAX_PROXY_AGE_MS = 7L * 24L * 3600L * 1000L // 7 days

    private val executor = Executors.newFixedThreadPool(1) { r ->
        Thread(r, "ReclyProxyWorker").apply { priority = Thread.MIN_PRIORITY }
    }

    private val activeJobs = ConcurrentHashMap<String, String>() // clipId -> outputPath
    private val completedProxies = ConcurrentHashMap<String, Uri>() // clipId -> proxyUri

    fun getProxyUri(clipId: String): Uri? = completedProxies[clipId]

    fun isProxyGenerating(clipId: String): Boolean = activeJobs.containsKey(clipId)

    /**
     * Checks if proxy is necessary based on clip properties and device decoder capabilities.
     */
    fun isProxyNeeded(context: Context, clip: VideoClip, simultaneousLayers: Int = 1): Boolean {
        if (clip.image) return false
        val isHevcOrAv1 = clip.mimeType.contains("hevc", ignoreCase = true) ||
            clip.mimeType.contains("h265", ignoreCase = true) ||
            clip.mimeType.contains("av01", ignoreCase = true)

        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        val isLowRam = activityManager?.isLowRamDevice ?: false

        // Check if decoder probe confirms realtime decode support
        val canDecode = DecoderCapabilityProbe.canDecodeRealtime(
            clip.width,
            clip.height,
            clip.fps.toDouble(),
            if (clip.mimeType.isNotBlank()) clip.mimeType else "video/avc",
        )

        val recommend = PreviewPerformanceController.shouldRecommendProxy(
            clip.width,
            clip.height,
            clip.fps,
            isHevcOrAv1,
            simultaneousLayers,
            isLowRam,
        )

        return !canDecode || recommend
    }

    /**
     * Enqueues proxy generation in the background without blocking the UI.
     */
    fun requestProxyIfNeeded(
        context: Context,
        clip: VideoClip,
        simultaneousLayers: Int = 1,
        onProxyReady: (clipId: String, proxyUri: Uri) -> Unit,
    ) {
        if (!isProxyNeeded(context, clip, simultaneousLayers)) {
            Log.d(TAG, "Proxy NOT needed for clip=${clip.id} (${clip.width}x${clip.height} ${clip.fps}fps ${clip.mimeType})")
            return
        }

        // Already completed
        completedProxies[clip.id]?.let { existing ->
            onProxyReady(clip.id, existing)
            return
        }

        // Already in progress
        if (activeJobs.containsKey(clip.id)) return

        val proxyDir = File(context.cacheDir, PROXY_DIR_NAME).apply { mkdirs() }
        pruneOldProxies(proxyDir)

        val proxyFile = File(proxyDir, "proxy_${clip.id.hashCode()}_720p.mp4")
        if (proxyFile.exists() && proxyFile.length() > 0) {
            val uri = Uri.fromFile(proxyFile)
            completedProxies[clip.id] = uri
            onProxyReady(clip.id, uri)
            return
        }

        activeJobs[clip.id] = proxyFile.absolutePath
        Log.i(TAG, "Starting background proxy generation for clip=${clip.id} -> ${proxyFile.name}")

        executor.execute {
            try {
                val inputMediaItem = MediaItem.fromUri(clip.uri)
                val targetShortSide = 720
                val scale = targetShortSide.toFloat() / minOf(clip.width, clip.height).coerceAtLeast(1)
                val outWidth = ((clip.width * scale).toInt() / 2) * 2
                val outHeight = ((clip.height * scale).toInt() / 2) * 2

                val presentation = Presentation.createForWidthAndHeight(outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT)
                val editedItem = EditedMediaItem.Builder(inputMediaItem)
                    .setEffects(Effects(emptyList<androidx.media3.common.audio.AudioProcessor>(), listOf<androidx.media3.common.Effect>(presentation)))
                    .setRemoveAudio(false)
                    .build()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .build()

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        activeJobs.remove(clip.id)
                        val uri = Uri.fromFile(proxyFile)
                        completedProxies[clip.id] = uri
                        Log.i(TAG, "Background proxy ready for clip=${clip.id} size=${proxyFile.length() / 1024}KB")
                        onProxyReady(clip.id, uri)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        activeJobs.remove(clip.id)
                        runCatching { proxyFile.delete() }
                        Log.w(TAG, "Proxy generation failed for clip=${clip.id}", exportException)
                    }
                }

                transformer.addListener(listener)
                transformer.start(editedItem, proxyFile.absolutePath)
            } catch (t: Throwable) {
                activeJobs.remove(clip.id)
                runCatching { proxyFile.delete() }
                Log.w(TAG, "Failed to start proxy transformer for clip=${clip.id}", t)
            }
        }
    }

    private fun pruneOldProxies(proxyDir: File) {
        runCatching {
            val files = proxyDir.listFiles() ?: return
            val now = System.currentTimeMillis()
            var totalBytes = 0L

            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                if (now - f.lastModified() > MAX_PROXY_AGE_MS) {
                    f.delete()
                } else {
                    totalBytes += f.length()
                }
            }

            if (totalBytes > MAX_PROXY_CACHE_BYTES) {
                for (f in sorted) {
                    if (totalBytes <= MAX_PROXY_CACHE_BYTES) break
                    val len = f.length()
                    if (f.delete()) totalBytes -= len
                }
            }
        }
    }

    fun clear() {
        completedProxies.clear()
        activeJobs.clear()
    }
}
