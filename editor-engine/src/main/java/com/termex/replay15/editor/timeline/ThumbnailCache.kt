package com.termex.replay15.editor.timeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.termex.replay15.editor.domain.VideoClip
import java.util.concurrent.Executors

class ThumbnailCache(context: Context, private val changed: () -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ThumbnailWorker").apply { priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private var closed = false
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    fun get(clip: VideoClip, timeUs: Long): Bitmap? {
        val bucket = if (clip.image) 0 else timeUs / 500_000 * 500_000
        val key = "${clip.uri}:$bucket"
        cache.get(key)?.let { return it }
        if (closed || key in pending || key in failed || pending.size >= 12) return null
        pending += key
        worker.execute {
            val result = runCatching {
                if (clip.image) ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, Uri.parse(clip.uri))) { decoder, info, _ ->
                    val scale = minOf(160f / info.size.width, 100f / info.size.height, 1f)
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                } else {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(app, Uri.parse(clip.uri))
                        retriever.getScaledFrameAtTime(bucket, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 100)
                    } finally { retriever.release() }
                }
            }.getOrNull()
            main.post {
                pending -= key
                if (closed) { result?.recycle(); return@post }
                if (result != null) cache.put(key, result) else failed += key
                changed()
            }
        }
        return null
    }
    fun clear() {
        cache.evictAll()
        pending.clear()
        failed.clear()
    }
    override fun close() { closed = true; worker.shutdownNow(); cache.evictAll(); pending.clear(); failed.clear() }
}
