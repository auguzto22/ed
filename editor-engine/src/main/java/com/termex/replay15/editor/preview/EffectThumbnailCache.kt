package com.termex.replay15.editor.preview

import android.graphics.Bitmap
import android.util.LruCache
import com.termex.replay15.editor.assets.EffectDefinition
import com.termex.replay15.editor.assets.EffectPreset
import com.termex.replay15.editor.domain.VideoClip
import java.util.concurrent.atomic.AtomicInteger

data class EffectThumbnailCacheKey(
    val clipId: String,
    val sourceUri: String,
    val timestampBucket: Long,
    val targetId: String,
    val targetVersion: Int = 1,
    val isPreset: Boolean = true,
    val intensityBucket: Int = 100,
    val rotation: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)

object EffectThumbnailCache {
    private const val MAX_CACHE_BYTES = 4 * 1024 * 1024 // 4 MB

    private val hitsCount = AtomicInteger(0)
    private val missesCount = AtomicInteger(0)
    private val putsCount = AtomicInteger(0)

    private val cache = object : LruCache<EffectThumbnailCacheKey, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: EffectThumbnailCacheKey, value: Bitmap): Int {
            return value.allocationByteCount
        }
    }

    fun makePresetKey(
        clip: VideoClip,
        sessionTimestampUs: Long,
        preset: EffectPreset,
        intensity: Float = 1.0f,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
    ): EffectThumbnailCacheKey {
        val bucket = if (clip.image) 0L else sessionTimestampUs / 250_000L * 250_000L
        val intensityBucket = (intensity * 10f).toInt() * 10 // quantized by 10%
        return EffectThumbnailCacheKey(
            clipId = clip.id,
            sourceUri = clip.uri,
            timestampBucket = bucket,
            targetId = preset.id,
            targetVersion = 1,
            isPreset = true,
            intensityBucket = intensityBucket,
            rotation = clip.rotation,
            width = targetWidth,
            height = targetHeight,
        )
    }

    fun makeEffectKey(
        clip: VideoClip,
        sessionTimestampUs: Long,
        definition: EffectDefinition,
        intensity: Float = 1.0f,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
    ): EffectThumbnailCacheKey {
        val bucket = if (clip.image) 0L else sessionTimestampUs / 250_000L * 250_000L
        val intensityBucket = (intensity * 10f).toInt() * 10
        return EffectThumbnailCacheKey(
            clipId = clip.id,
            sourceUri = clip.uri,
            timestampBucket = bucket,
            targetId = definition.id,
            targetVersion = definition.version,
            isPreset = false,
            intensityBucket = intensityBucket,
            rotation = clip.rotation,
            width = targetWidth,
            height = targetHeight,
        )
    }

    fun get(key: EffectThumbnailCacheKey): Bitmap? {
        val bitmap = cache.get(key)
        if (bitmap != null && !bitmap.isRecycled) {
            hitsCount.incrementAndGet()
            return bitmap
        }
        missesCount.incrementAndGet()
        return null
    }

    fun put(key: EffectThumbnailCacheKey, bitmap: Bitmap) {
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
        return "EffectThumbnailCache [hits=$h, misses=$m, hitRate=%.1f%%, cachedBytes=%d/%d]".format(
            rate, cache.size(), MAX_CACHE_BYTES,
        )
    }
}
