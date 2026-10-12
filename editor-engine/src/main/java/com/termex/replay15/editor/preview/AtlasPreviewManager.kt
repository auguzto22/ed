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

    // 3. Coordinates for every built-in transition, in catalog order (transitions.json).
    // The atlases and editor/previews/preview_registry.json are generated together with this
    // list by rendering each shader at mid-progress; keep the three in the same order.
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
            "glass_trans", "smooth_fade", "color_fade_dip", "cross_fade_soft",
            "rgb_split", "rgb_shift", "chromatic_drift", "channel_shear",
            "prism_wipe", "film_roll", "leader_flash", "sprocket_slip",
            "burn_dissolve", "anamorphic_streak", "letterbox_reveal", "flare_sweep",
            "cine_shutter", "social_swipe_up", "stories_flip", "heart_iris",
            "bubble_pop_wipe", "notification_slide", "pixel_dissolve_arcade", "scanline_wipe",
            "energy_blast", "level_clear_wipe", "cyber_grid_reveal", "gl_advanced_mosaic",
            "gl_block_dissolve", "gl_book_flip", "gl_bounce", "gl_bow_tie_horizontal",
            "gl_bow_tie_vertical", "gl_bow_tie_with_parameter", "gl_box", "gl_butterfly_wave_scrawler",
            "gl_circle_crop", "gl_colour_distance", "gl_crazy_parametric_fun", "gl_cross_zoom",
            "gl_defocus_blur", "gl_directional", "gl_directional_scaled", "gl_doom_screen_transition",
            "gl_dreamy", "gl_dreamy_zoom", "gl_drop_zone_flicker", "gl_edge_transition",
            "gl_film_burn", "gl_fold", "gl_glitch_displace", "gl_glitch_memories",
            "gl_grid_flip", "gl_hsvfade", "gl_horizontal_close", "gl_horizontal_open",
            "gl_inverted_page_curl", "gl_left_right", "gl_linear_blur", "gl_mosaic",
            "gl_overexposure", "gl_polka_dots_curtain", "gl_puzzle_right", "gl_radial",
            "gl_rectangle", "gl_rectangle_crop", "gl_revolve_left", "gl_rolls",
            "gl_rotate_scale_vanish", "gl_simple_flip", "gl_simple_zoom", "gl_simple_zoom_out",
            "gl_slides", "gl_star_wipe", "gl_static_fade", "gl_stereo_viewer",
            "gl_strip_datamosh_glitch", "gl_swirl", "gl_tvstatic", "gl_tiles_wave",
            "gl_top_bottom", "gl_vertical_close", "gl_vertical_open", "gl_water_drop",
            "gl_zoom_in_circles", "gl_zoom_left_wipe", "gl_zoom_rigth_wipe", "gl_angular",
            "gl_burn", "gl_burn0", "gl_chessboard", "gl_circle",
            "gl_circleopen", "gl_colorphase", "gl_coord_from_in", "gl_crosshatch",
            "gl_crosswarp", "gl_cube", "gl_directional_easing", "gl_directionalwarp",
            "gl_directionalwipe", "gl_dissolve", "gl_doorway", "gl_fade",
            "gl_fadecolor", "gl_fadegrayscale", "gl_flyeye", "gl_fragment",
            "gl_heart", "gl_hexagonalize", "gl_kaleidoscope", "gl_luminance_melt",
            "gl_morph", "gl_mosaic_transition", "gl_multiply_blend", "gl_old_tv_lost_signal",
            "gl_parametric_glitch", "gl_perlin", "gl_pinwheel", "gl_pixelize",
            "gl_polar_function", "gl_power_kaleido", "gl_random_noisex", "gl_randomsquares",
            "gl_ripple", "gl_rotate_transition", "gl_rotate_scale_fade", "gl_scale_in",
            "gl_split_slide_in_horizontal", "gl_split_slide_in_out_horizontal", "gl_split_slide_in_out_vertical", "gl_split_slide_in_vertical",
            "gl_split_slide_out_horizontal", "gl_split_slide_out_vertical", "gl_squareswire", "gl_squeeze",
            "gl_static_wipe", "gl_swap", "gl_tangent_motion_blur", "gl_undulating_burn_out",
            "gl_wind", "gl_windowblinds", "gl_windowslice", "gl_wipe_down",
            "gl_wipe_left", "gl_wipe_right", "gl_wipe_up", "gl_x_axis_translation",
            "gl_zoom_in_out",
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
