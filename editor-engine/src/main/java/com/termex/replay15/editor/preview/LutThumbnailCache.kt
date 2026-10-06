package com.termex.replay15.editor.preview

import android.graphics.Bitmap
import android.util.LruCache
import com.termex.replay15.editor.domain.VideoClip
import java.io.File

data class LutThumbnailCacheKey(
    val path: String,
    val modified: Long,
    val fileSize: Long,
    val clipId: String,
    val sourceUri: String,
    val timestampBucket: Long,
    val rotation: Int,
    val strengthPercent: Int,
)

/** Small process cache for LUT browser previews. It never retains decoded video frames. */
object LutThumbnailCache {
    private const val MAX_CACHE_BYTES = 3 * 1024 * 1024
    private val cache = object : LruCache<LutThumbnailCacheKey, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: LutThumbnailCacheKey, value: Bitmap) = value.allocationByteCount
    }

    fun key(file: File, clip: VideoClip, timeUs: Long, strength: Float) = LutThumbnailCacheKey(
        path = file.absolutePath,
        modified = file.lastModified(),
        fileSize = file.length(),
        clipId = clip.id,
        sourceUri = clip.uri,
        timestampBucket = timeUs.coerceAtLeast(0L) / 500_000L * 500_000L,
        rotation = clip.rotation,
        strengthPercent = (strength.coerceIn(0f, 1f) * 100).toInt(),
    )

    fun get(key: LutThumbnailCacheKey): Bitmap? = cache.get(key)?.takeUnless { it.isRecycled }

    fun put(key: LutThumbnailCacheKey, bitmap: Bitmap) {
        if (!bitmap.isRecycled) cache.put(key, bitmap)
    }

    fun clear() = cache.evictAll()
}
