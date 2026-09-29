package com.termex.replay15.editor.preview

import android.graphics.Bitmap
import android.util.LruCache
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.VideoFilter
import java.util.concurrent.atomic.AtomicInteger

data class FilterThumbnailCacheKey(
    val clipId: String,
    val sourceUri: String,
    val timestampBucket: Long,
    val filterOrdinal: Int,
    val filterVersion: Int = 1,
    val rotation: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)

object FilterThumbnailCache {
    // Current filter version - incrementing invalidates older cache entries
    const val CURRENT_FILTER_VERSION = 1
    private const val MAX_CACHE_BYTES = 4 * 1024 * 1024 // 4 MB

    private val hitsCount = AtomicInteger(0)
    private val missesCount = AtomicInteger(0)
    private val putsCount = AtomicInteger(0)

    private val cache = object : LruCache<FilterThumbnailCacheKey, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: FilterThumbnailCacheKey, value: Bitmap): Int {
            return value.allocationByteCount
        }
    }

    fun makeKey(
        clip: VideoClip,
        sessionTimestampUs: Long,
        filter: VideoFilter,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
    ): FilterThumbnailCacheKey {
        // Quantize video timestamp to 250ms buckets to avoid cache fragmentation
        val bucket = if (clip.image) 0L else sessionTimestampUs / 250_000L * 250_000L
        return FilterThumbnailCacheKey(
            clipId = clip.id,
            sourceUri = clip.uri,
            timestampBucket = bucket,
            filterOrdinal = filter.ordinal,
            filterVersion = CURRENT_FILTER_VERSION,
            rotation = clip.rotation,
            width = targetWidth,
            height = targetHeight,
        )
    }

    fun get(key: FilterThumbnailCacheKey): Bitmap? {
        val bitmap = cache.get(key)
        if (bitmap != null && !bitmap.isRecycled) {
            hitsCount.incrementAndGet()
            return bitmap
        }
        missesCount.incrementAndGet()
        return null
    }

    fun put(key: FilterThumbnailCacheKey, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            cache.put(key, bitmap)
            putsCount.incrementAndGet()
        }
    }

    fun clear() {
        cache.evictAll()
    }

    fun metrics(): String {
        val h = hitsCount.get()
        val m = missesCount.get()
        val total = h + m
        val rate = if (total > 0) (h * 100f / total) else 0f
        return "FilterThumbnailCache [hits=$h, misses=$m, hitRate=%.1f%%, cachedBytes=%d/%d]".format(
            rate, cache.size(), MAX_CACHE_BYTES,
        )
    }
}
