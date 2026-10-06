package com.termex.replay15.editor.motion

import android.content.Context
import android.net.Uri
import android.util.Log
import com.termex.replay15.editor.domain.VideoClip
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Produces frame-interpolated previews for slowed clips, in the background.
 *
 * The original file is never rewritten. Each render lands in the app cache and is handed to
 * the editor as a preview-only URI, exactly the way [com.termex.replay15.editor.media
 * .AdaptiveProxyManager] hands over a proxy. Export ignores it completely.
 *
 * Work is deliberately serialized on a single low-priority thread: interpolation is already
 * the most expensive thing the editor can ask for, and running two at once on a low-RAM
 * phone is what makes the whole app stutter.
 */
object SmoothSlowMoCache {
    private const val TAG = "ReclySmoothSlowMo"
    private const val CACHE_DIR_NAME = "editor-slowmo"
    private const val MAX_CACHE_BYTES = 400L * 1024L * 1024L
    private const val MAX_CACHE_AGE_MS = 3L * 24L * 3600L * 1000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Recly-SmoothSlowMo").apply { priority = Thread.MIN_PRIORITY }
    }

    private val running = ConcurrentHashMap<String, AtomicBoolean>()

    fun isRendering(clipId: String): Boolean = running.containsKey(clipId)

    /**
     * Renders [clip] if the profile asks for it and the work is not already done or in
     * flight. [onReady] is called on a background thread.
     */
    fun requestIfNeeded(
        context: Context,
        clip: VideoClip,
        profile: SmoothSlowMoProfile,
        onReady: (clipId: String, derivedUri: Uri) -> Unit,
    ) {
        val request = SmoothSlowMoPlanner.plan(clip, profile) ?: return
        if (running.containsKey(request.clipId)) return

        val directory = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        prune(directory)
        val output = File(directory, "${request.cacheKey}.mp4")
        if (output.exists() && output.length() > 0) {
            onReady(request.clipId, Uri.fromFile(output))
            return
        }

        val cancellation = AtomicBoolean(false)
        running[request.clipId] = cancellation
        executor.execute {
            try {
                val encoder = InterpolatedClipEncoder(request, clip.width, clip.height, cancellation)
                val ok = encoder.encodeTo(output)
                encoder.close()
                if (ok && output.length() > 0) {
                    Log.i(TAG, "Interpolated preview ready for ${clip.id} (${output.length() / 1024}KB)")
                    onReady(request.clipId, Uri.fromFile(output))
                } else {
                    runCatching { output.delete() }
                    Log.w(TAG, "Interpolated preview produced nothing for ${clip.id}")
                }
            } catch (failure: Throwable) {
                runCatching { output.delete() }
                Log.w(TAG, "Interpolated preview failed for ${clip.id}", failure)
            } finally {
                running.remove(request.clipId)
            }
        }
    }

    /** Stops work for every clip in flight; called when the editor is closed. */
    fun cancelAll() {
        running.values.forEach { it.set(true) }
        running.clear()
    }

    fun clear() {
        cancelAll()
    }

    private fun prune(directory: File) {
        runCatching {
            val files = directory.listFiles() ?: return
            val now = System.currentTimeMillis()
            var totalBytes = 0L
            val oldest = files.sortedBy { it.lastModified() }
            for (file in oldest) {
                if (now - file.lastModified() > MAX_CACHE_AGE_MS) file.delete() else totalBytes += file.length()
            }
            if (totalBytes > MAX_CACHE_BYTES) {
                for (file in oldest) {
                    if (totalBytes <= MAX_CACHE_BYTES) break
                    val size = file.length()
                    if (file.delete()) totalBytes -= size
                }
            }
        }
    }
}
