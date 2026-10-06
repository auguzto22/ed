package com.termex.replay15.editor.scopes

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import java.util.concurrent.atomic.AtomicLong

/**
 * Pulls one small frame from a source file so the scopes have something to draw.
 *
 * The sample is deliberately tiny (96x54) and cached per source time bucket. A scope panel
 * refreshing while the user scrubs would otherwise decode a full frame per update, which is the
 * one thing this project must never do on the preview path.
 */
class ScopeFrameSampler(private val context: Context) {

    private val cache = LruCache<String, ScopeFrame>(24)
    private val generation = AtomicLong(0)

    /** How far the playhead may move before a cached sample is considered stale. */
    var toleranceUs: Long = 250_000L

    fun invalidate() {
        generation.incrementAndGet()
        cache.evictAll()
    }

    /**
     * Returns the scope data for the frame nearest [timeUs], or null when the source has no
     * decodable video. A null is not an error the panel should surface: it just means there is
     * nothing to measure at this position.
     */
    fun sample(uri: String, timeUs: Long, sourceDurationUs: Long): ScopeFrame? {
        val bucket = (timeUs / toleranceUs) * toleranceUs
        val key = "$uri@$bucket#${generation.get()}"
        cache.get(key)?.let { return it }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context.applicationContext, Uri.parse(uri))
            val bitmap = retriever.getScaledFrameAtTime(
                bucket.coerceIn(0L, (sourceDurationUs - 1).coerceAtLeast(0L)),
                MediaMetadataRetriever.OPTION_CLOSEST, SAMPLE_WIDTH, SAMPLE_HEIGHT,
            ) ?: return null
            val frame = try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                ScopeAnalyzer.analyze(pixels, bitmap.width, bitmap.height)
            } finally {
                bitmap.recycle()
            }
            cache.put(key, frame)
            frame
        } catch (error: RuntimeException) {
            // A source the platform cannot open for a still frame is a missing measurement, not
            // a crash: the panel simply keeps the last state it had.
            null
        } finally {
            retriever.release()
        }
    }

    companion object {
        const val SAMPLE_WIDTH = 96
        const val SAMPLE_HEIGHT = 54
    }
}
