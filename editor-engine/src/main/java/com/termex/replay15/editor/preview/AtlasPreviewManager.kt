package com.termex.replay15.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.termex.replay15.editor.domain.VideoFilter
import kotlinx.coroutines.*
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance, memory-efficient manager for pre-rendered UI preview atlases.
 * Replaces dynamic per-preset video decoding and shader rendering with static WebP atlases.
 */
object AtlasPreviewManager {

    private const val TAG = "ReclyAtlasPreview"
    private const val PREVIEWS_ASSET_DIR = "editor/previews"

    // Max 4 atlases in memory (4 x 512x512 x 4 bytes = 4 MB)
    private const val MAX_CACHE_BYTES = 4 * 1024 * 1024

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Metrics counters
    val cacheHits = AtomicInteger(0)
    val cacheMisses = AtomicInteger(0)
    val atlasDecodes = AtomicInteger(0)

    val unmappedPresets: MutableSet<String> = ConcurrentHashMap.newKeySet()

    data class AtlasCoordinate(
        val atlasName: String,
        val col: Int,
        val row: Int,
        val cellWidth: Int = 128,
        val cellHeight: Int = 128,
    ) {
        val left: Int get() = col * cellWidth
        val top: Int get() = row * cellHeight
        val right: Int get() = left + cellWidth
        val bottom: Int get() = top + cellHeight

        fun toRect(): Rect {
            return Rect(left, top, right, bottom)
        }
    }

    private val atlasCache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount
        }
    }

    // 1. Static coordinates for 32 VideoFilters
    private val filterCoordinates: Map<VideoFilter, AtlasCoordinate> = buildMap {
        val entries = VideoFilter.entries
        for (i in entries.indices) {
            val atlasName = if (i < 16) "filters_atlas_01" else "filters_atlas_02"
            val localIdx = i % 16
            put(entries[i], AtlasCoordinate(atlasName, localIdx % 4, localIdx / 4))
        }
    }

    // 2. Static coordinates for 20 built-in Effect Presets
    private val effectPresetCoordinates: Map<String, AtlasCoordinate> = mapOf(
        "cyber_signal" to AtlasCoordinate("effects_atlas_01", 0, 0),
        "impact_combo" to AtlasCoordinate("effects_atlas_01", 1, 0),
        "soft_dream" to AtlasCoordinate("effects_atlas_01", 2, 0),
        "broken_tape" to AtlasCoordinate("effects_atlas_01", 3, 0),
        "neon_arcade" to AtlasCoordinate("effects_atlas_01", 0, 1),
        "liquid_lens" to AtlasCoordinate("effects_atlas_01", 1, 1),
        "clean_film" to AtlasCoordinate("effects_atlas_01", 2, 1),
        "film_35mm" to AtlasCoordinate("effects_atlas_01", 3, 1),
        "vintage_film" to AtlasCoordinate("effects_atlas_01", 0, 2),
        "dark_cinema" to AtlasCoordinate("effects_atlas_01", 1, 2),
        "warm_cinema" to AtlasCoordinate("effects_atlas_01", 2, 2),
        "cold_cinema" to AtlasCoordinate("effects_atlas_01", 3, 2),
        "kill_impact" to AtlasCoordinate("effects_atlas_01", 0, 3),
        "headshot" to AtlasCoordinate("effects_atlas_01", 1, 3),
        "damage" to AtlasCoordinate("effects_atlas_01", 2, 3),
        "explosion" to AtlasCoordinate("effects_atlas_01", 3, 3),
        "speed" to AtlasCoordinate("effects_atlas_02", 0, 0),
        "beat_zoom" to AtlasCoordinate("effects_atlas_02", 1, 0),
        "victory" to AtlasCoordinate("effects_atlas_02", 2, 0),
        "film_look" to AtlasCoordinate("effects_atlas_02", 3, 0),
    )

    // 3. Static coordinates for 73 built-in Transitions
    private val transitionCoordinates: Map<String, AtlasCoordinate> = buildMap {
        val transitionIds = listOf(
            "cross_dissolve", "fade", "dip_to_black", "dip_to_white",
            "fade_through_black", "fade_through_white", "slide_left", "slide_right",
            "slide_up", "slide_down", "push_left", "push_right",
            "push_up", "push_down", "wipe_left", "wipe_right",
            "wipe_up", "wipe_down", "diagonal_wipe", "circle_wipe",
            "radial_wipe", "soft_wipe", "zoom_in", "zoom_out",
            "zoom_through", "punch_zoom", "smooth_zoom", "zoom_blur",
            "zoom_rotate", "whip_left", "whip_right", "whip_up",
            "whip_down", "motion_swipe", "fast_pan", "blur_dissolve",
            "gaussian_blur_trans", "directional_blur_trans", "radial_blur_trans", "white_flash",
            "color_flash", "light_leak", "film_burn", "glow_flash",
            "digital_glitch", "rgb_glitch", "signal_glitch", "vhs_glitch",
            "luma_fade", "luma_wipe", "ripple", "wave",
            "fisheye_trans", "warp_trans", "lens_distortion_trans", "spin",
            "spin_zoom", "rotate_push", "flip_horizontal", "flip_vertical",
            "cube_left", "cube_right", "page_turn", "perspective_slide",
            "split_reveal", "split_horizontal", "split_vertical", "mosaic_reveal",
            "pixel_dissolve", "prism_trans", "mirror_trans", "kaleidoscope_trans",
            "glass_trans"
        )
        for (i in transitionIds.indices) {
            val atlasNum = (i / 16) + 1
            val atlasName = String.format(java.util.Locale.US, "transitions_atlas_%02d", atlasNum)
            val localIdx = i % 16
            put(transitionIds[i], AtlasCoordinate(atlasName, localIdx % 4, localIdx / 4))
        }
    }

    private fun getOrDecodeAtlas(context: Context, atlasName: String): Bitmap? {
        synchronized(atlasCache) {
            atlasCache.get(atlasName)?.let {
                cacheHits.incrementAndGet()
                return it
            }
        }
        cacheMisses.incrementAndGet()

        val assetPath = "$PREVIEWS_ASSET_DIR/$atlasName.webp"
        val bitmap = runCatching {
            var stream: InputStream? = null
            try {
                stream = context.assets.open(assetPath)
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, opts)
            } finally {
                stream?.close()
            }
        }.getOrNull()

        if (bitmap != null) {
            atlasDecodes.incrementAndGet()
            synchronized(atlasCache) {
                atlasCache.put(atlasName, bitmap)
            }
            return bitmap
        } else {
            Log.e(TAG, "Failed to decode atlas asset from $assetPath")
            return null
        }
    }

    private fun createDrawable(bitmap: Bitmap, coord: AtlasCoordinate): Drawable {
        return AtlasRegionDrawable(bitmap, coord.toRect())
    }

    private fun fallbackDrawable(): Drawable {
        return ColorDrawable(0xFF222226.toInt())
    }

    fun getFilterPreview(context: Context, filter: VideoFilter, callback: (Drawable) -> Unit) {
        val coord = filterCoordinates[filter]
        if (coord == null) {
            unmappedPresets.add("filter:${filter.name}")
            callback(fallbackDrawable())
            return
        }
        loadCoordinate(context, coord, callback)
    }

    fun getEffectPresetPreview(context: Context, presetId: String, callback: (Drawable) -> Unit) {
        val coord = effectPresetCoordinates[presetId]
        if (coord == null) {
            unmappedPresets.add("effect:$presetId")
            callback(fallbackDrawable())
            return
        }
        loadCoordinate(context, coord, callback)
    }

    fun getTransitionPreview(context: Context, transitionId: String, callback: (Drawable) -> Unit) {
        val coord = transitionCoordinates[transitionId]
        if (coord == null) {
            unmappedPresets.add("transition:$transitionId")
            callback(fallbackDrawable())
            return
        }
        loadCoordinate(context, coord, callback)
    }

    private fun loadCoordinate(context: Context, coord: AtlasCoordinate, callback: (Drawable) -> Unit) {
        // Fast path: cached in memory
        val cachedBitmap = synchronized(atlasCache) { atlasCache.get(coord.atlasName) }
        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
            cacheHits.incrementAndGet()
            callback(createDrawable(cachedBitmap, coord))
            return
        }

        // Slow path: background decode from asset
        scope.launch {
            val bitmap = getOrDecodeAtlas(context.applicationContext, coord.atlasName)
            val drawable = if (bitmap != null) createDrawable(bitmap, coord) else fallbackDrawable()
            mainHandler.post {
                callback(drawable)
            }
        }
    }

    fun hasFilterPreview(filter: VideoFilter): Boolean = filterCoordinates.containsKey(filter)

    fun hasEffectPresetPreview(presetId: String): Boolean = effectPresetCoordinates.containsKey(presetId)

    fun hasTransitionPreview(transitionId: String): Boolean = transitionCoordinates.containsKey(transitionId)

    fun clearCache() {
        synchronized(atlasCache) {
            atlasCache.evictAll()
        }
    }

    fun dumpMetrics(): String {
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val total = hits + misses
        val hitRate = if (total > 0) (hits * 100f / total) else 0f
        return "AtlasPreviewManager [hits=$hits, misses=$misses, hitRate=%.1f%%, decodes=${atlasDecodes.get()}, unmapped=${unmappedPresets.size}]".format(hitRate)
    }
}
