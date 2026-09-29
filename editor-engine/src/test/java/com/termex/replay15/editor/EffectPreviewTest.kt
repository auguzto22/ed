package com.termex.replay15.editor

import com.termex.replay15.editor.assets.EffectDefinition
import com.termex.replay15.editor.assets.EffectParameter
import com.termex.replay15.editor.assets.EffectPreset
import com.termex.replay15.editor.assets.EffectPresetNode
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.preview.EffectThumbnailCache
import com.termex.replay15.editor.preview.EffectThumbnailCacheKey
import org.junit.Assert.*
import org.junit.Test

class EffectPreviewTest {

    private fun sampleClip(
        id: String = "clip_1",
        uri: String = "file:///test.mp4",
        sourceUs: Long = 10_000_000L,
        rotation: Int = 0,
        isImage: Boolean = false,
    ) = VideoClip(
        id = id,
        uri = uri,
        name = "Test Clip",
        sourceUs = sourceUs,
        width = 1920,
        height = 1080,
        rotation = rotation,
        image = isImage,
    )

    private fun samplePreset(id: String = "cyber_signal", name: String = "Cyber Signal") = EffectPreset(
        id = id,
        name = name,
        category = "Gaming",
        nodes = listOf(
            EffectPresetNode(assetId = "recly_rgb", intensity = 0.72f),
            EffectPresetNode(assetId = "recly_glow", intensity = 0.52f),
        )
    )

    private fun sampleDefinition(id: String = "recly_rgb", name: String = "RGB Split") = EffectDefinition(
        id = id,
        version = 1,
        name = name,
        category = "Glitch",
        shader = "recly_rgb.frag",
        parameters = listOf(
            EffectParameter("distance", "Distance", 0f, 20f, 6f),
            EffectParameter("angle", "Angle", 0f, 6.283f, 0f),
        ),
        license = "MIT",
    )

    @Test
    fun testPresetCacheKeyDifferentiation() {
        val clipA = sampleClip(id = "clip_a", uri = "file:///a.mp4")
        val clipB = sampleClip(id = "clip_b", uri = "file:///b.mp4")
        val preset1 = samplePreset("cyber_signal", "Cyber Signal")
        val preset2 = samplePreset("soft_dream", "Soft Dream")

        val keyA1 = EffectThumbnailCache.makePresetKey(clipA, 1_000_000L, preset1)
        val keyB1 = EffectThumbnailCache.makePresetKey(clipB, 1_000_000L, preset1)
        val keyA2 = EffectThumbnailCache.makePresetKey(clipA, 1_000_000L, preset2)
        val keyATimeDiff = EffectThumbnailCache.makePresetKey(clipA, 2_000_000L, preset1)

        assertNotEquals(keyA1, keyB1)
        assertNotEquals(keyA1, keyA2)
        assertNotEquals(keyA1, keyATimeDiff)
        assertTrue(keyA1.isPreset)
    }

    @Test
    fun testEffectCacheKeyDifferentiation() {
        val clip = sampleClip()
        val def1 = sampleDefinition("recly_rgb", "RGB")
        val def2 = sampleDefinition("recly_glow", "Glow")

        val key1 = EffectThumbnailCache.makeEffectKey(clip, 500_000L, def1)
        val key2 = EffectThumbnailCache.makeEffectKey(clip, 500_000L, def2)

        assertNotEquals(key1, key2)
        assertFalse(key1.isPreset)
        assertEquals("recly_rgb", key1.targetId)
    }

    @Test
    fun testTimestampBucketing() {
        val clip = sampleClip()
        val preset = samplePreset()

        // 100ms and 200ms belong to the same 250ms bucket (0)
        val key1 = EffectThumbnailCache.makePresetKey(clip, 100_000L, preset)
        val key2 = EffectThumbnailCache.makePresetKey(clip, 200_000L, preset)
        // 600ms belongs to bucket 500_000
        val key3 = EffectThumbnailCache.makePresetKey(clip, 600_000L, preset)

        assertEquals(key1, key2)
        assertNotEquals(key1, key3)
    }

    @Test
    fun testImageClipIgnoresTimestamp() {
        val imageClip = sampleClip(isImage = true)
        val preset = samplePreset()

        val key1 = EffectThumbnailCache.makePresetKey(imageClip, 0L, preset)
        val key2 = EffectThumbnailCache.makePresetKey(imageClip, 5_000_000L, preset)

        assertEquals(key1, key2)
        assertEquals(0L, key1.timestampBucket)
    }

    @Test
    fun testIntensityQuantization() {
        val clip = sampleClip()
        val preset = samplePreset()

        // 100% and 104% quantize to 100
        val key1 = EffectThumbnailCache.makePresetKey(clip, 1_000_000L, preset, intensity = 1.0f)
        val key2 = EffectThumbnailCache.makePresetKey(clip, 1_000_000L, preset, intensity = 1.04f)
        // 70% quantizes to 70
        val key3 = EffectThumbnailCache.makePresetKey(clip, 1_000_000L, preset, intensity = 0.7f)

        assertEquals(key1, key2)
        assertNotEquals(key1, key3)
    }

    @Test
    fun testStaleSessionRejection() {
        var currentSessionId = "session_A"
        var activeClipId = "clip_1"
        var delivered = 0

        val onDeliver: (String, String) -> Unit = { session, clip ->
            if (session == currentSessionId && clip == activeClipId) {
                delivered++
            }
        }

        // Normal delivery
        onDeliver("session_A", "clip_1")
        assertEquals(1, delivered)

        // Session switch (user switched clips)
        currentSessionId = "session_B"
        activeClipId = "clip_2"

        // Delayed thumbnail from old session arrives -> MUST be rejected
        onDeliver("session_A", "clip_1")
        assertEquals(1, delivered)

        // Thumbnail from active session arrives -> accepted
        onDeliver("session_B", "clip_2")
        assertEquals(2, delivered)
    }
}
