package com.termex.replay15.editor

import com.termex.replay15.editor.domain.VideoFilter
import com.termex.replay15.editor.preview.AtlasPreviewManager
import org.junit.Assert.*
import org.junit.Test

class AtlasPreviewTest {

    @Test
    fun testAll32VideoFiltersHavePreviewMapping() {
        val allFilters = VideoFilter.entries
        assertEquals(32, allFilters.size)

        for (filter in allFilters) {
            assertTrue("Filter $filter must have an atlas preview", AtlasPreviewManager.hasFilterPreview(filter))
        }
    }

    @Test
    fun testAll20EffectPresetsHavePreviewMapping() {
        val effectPresetIds = listOf(
            "cyber_signal", "impact_combo", "soft_dream", "broken_tape",
            "neon_arcade", "liquid_lens", "clean_film", "film_35mm",
            "vintage_film", "dark_cinema", "warm_cinema", "cold_cinema",
            "kill_impact", "headshot", "damage", "explosion",
            "speed", "beat_zoom", "victory", "film_look"
        )
        assertEquals(20, effectPresetIds.size)

        for (id in effectPresetIds) {
            assertTrue("Effect preset $id must have an atlas preview", AtlasPreviewManager.hasEffectPresetPreview(id))
        }
    }

    @Test
    fun testAll73TransitionsHavePreviewMapping() {
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
        assertEquals(73, transitionIds.size)

        for (id in transitionIds) {
            assertTrue("Transition $id must have an atlas preview", AtlasPreviewManager.hasTransitionPreview(id))
        }
    }

    @Test
    fun testAtlasCoordinateBounds() {
        val coord = AtlasPreviewManager.AtlasCoordinate("filters_atlas_01", 2, 3, 128, 128)

        assertEquals(256, coord.left)
        assertEquals(384, coord.top)
        assertEquals(384, coord.right)
        assertEquals(512, coord.bottom)
        assertEquals(128, coord.cellWidth)
        assertEquals(128, coord.cellHeight)
    }

    @Test
    fun testFallbackRecordingForUnknownPreset() {
        val unknownId = "non_existent_preset_xyz"
        assertFalse(AtlasPreviewManager.hasEffectPresetPreview(unknownId))
        assertFalse(AtlasPreviewManager.hasTransitionPreview(unknownId))
    }

    @Test
    fun testCacheMetricsAndClearing() {
        AtlasPreviewManager.clearCache()
        val metrics = AtlasPreviewManager.dumpMetrics()
        assertTrue(metrics.contains("AtlasPreviewManager"))
    }
}
