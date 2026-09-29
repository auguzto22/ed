package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.engine.audio.AudioTimelineMapper
import com.termex.replay15.editor.transform.RealtimeProjectState
import com.termex.replay15.editor.transform.TransformKeyframe
import com.termex.replay15.editor.transform.TransformState
import com.termex.replay15.editor.ui.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class OverlayAndAudioHandlesInteractionTest {

    @Test
    fun `stickerClip keyframe interpolation correctly blends transform states`() {
        val kf1 = TransformKeyframe(
            timeUs = 1 * SECOND,
            transform = TransformState(x = 0.2f, y = 0.2f, scaleX = 1.0f, scaleY = 1.0f, rotation = 0f, opacity = 1.0f)
        )
        val kf2 = TransformKeyframe(
            timeUs = 3 * SECOND,
            transform = TransformState(x = 0.8f, y = 0.8f, scaleX = 2.0f, scaleY = 2.0f, rotation = 90f, opacity = 0.5f)
        )

        val sticker = StickerClip(
            id = "sticker_anim_1",
            uri = "content://images/sample.png",
            name = "Sticker",
            startUs = 0L,
            endUs = 5 * SECOND,
            x = 0.5f,
            y = 0.5f,
            scale = 1.0f,
            transformKeyframes = listOf(kf1, kf2)
        )

        // Before first keyframe: clamped to kf1
        val before = sticker.transformAt(500_000L)
        assertEquals(0.2f, before.x, 0.001f)
        assertEquals(1.0f, before.scaleX, 0.001f)

        // Midpoint at 2 seconds: linear interpolation between kf1 and kf2
        val mid = sticker.transformAt(2 * SECOND)
        assertEquals(0.5f, mid.x, 0.01f)
        assertEquals(0.5f, mid.y, 0.01f)
        assertEquals(1.5f, mid.scaleX, 0.01f)
        assertEquals(45f, mid.rotation, 0.1f)
        assertEquals(0.75f, mid.opacity, 0.01f)

        // At second keyframe
        val atEnd = sticker.transformAt(3 * SECOND)
        assertEquals(0.8f, atEnd.x, 0.001f)
        assertEquals(2.0f, atEnd.scaleX, 0.001f)
        assertEquals(90f, atEnd.rotation, 0.001f)
    }

    @Test
    fun `audioClip volume keyframe interpolation and endUs calculation`() {
        val vk1 = VolumeKeyframe(timeUs = 1 * SECOND, volume = 0.2f)
        val vk2 = VolumeKeyframe(timeUs = 3 * SECOND, volume = 1.8f)

        val audio = AudioClip(
            id = "audio_1",
            uri = "content://audio/track.mp3",
            name = "Music",
            sourceUs = 10 * SECOND,
            startUs = 2 * SECOND,
            inUs = 1 * SECOND,
            outUs = 6 * SECOND,
            volume = 1.0f,
            fadeInUs = 500_000L,
            fadeOutUs = 1 * SECOND,
            volumeKeyframes = listOf(vk1, vk2)
        )

        assertEquals(5 * SECOND, audio.durationUs)
        assertEquals(7 * SECOND, audio.endUs)

        // Volume interpolation:
        // Before first keyframe
        assertEquals(0.2f, audio.volumeAt(500_000L), 0.001f)
        // Midpoint between 1s (0.2) and 3s (1.8) -> 2s is exactly 1.0
        assertEquals(1.0f, audio.volumeAt(2 * SECOND), 0.01f)
        // After second keyframe
        assertEquals(1.8f, audio.volumeAt(4 * SECOND), 0.001f)
    }

    @Test
    fun `audioTimelineMapper combines fade curves and volume keyframes`() {
        val audio = AudioClip(
            id = "audio_combined",
            uri = "content://audio/bg.mp3",
            name = "Audio",
            sourceUs = 10 * SECOND,
            startUs = 0L,
            inUs = 0L,
            outUs = 4 * SECOND,
            volume = 1.0f,
            fadeInUs = 1 * SECOND,
            fadeOutUs = 1 * SECOND,
            volumeKeyframes = listOf(
                VolumeKeyframe(timeUs = 0L, volume = 1.0f),
                VolumeKeyframe(timeUs = 4 * SECOND, volume = 1.0f)
            )
        )

        // At start (0s): fade-in multiplier is 0.0 -> gain is 0.0
        val gainStart = AudioTimelineMapper.calculateGain(audio, 0L)
        assertEquals(0.0f, gainStart, 0.01f)

        // At 500ms: sinusoidal fade-in multiplier is sin(0.5 * PI / 2) ≈ 0.707
        val expectedMidFade = sin(0.5 * PI / 2.0).toFloat()
        val gainMidFadeIn = AudioTimelineMapper.calculateGain(audio, 500_000L)
        assertEquals(expectedMidFade, gainMidFadeIn, 0.02f)

        // At 2s: fully past fade-in and before fade-out -> gain is 1.0
        val gainFull = AudioTimelineMapper.calculateGain(audio, 2 * SECOND)
        assertEquals(1.0f, gainFull, 0.01f)

        // At 3.5s: 500ms into 1s fade-out -> sinusoidal fade multiplier is sin(0.5 * PI / 2) ≈ 0.707
        val gainMidFadeOut = AudioTimelineMapper.calculateGain(audio, 3_500_000L)
        assertEquals(expectedMidFade, gainMidFadeOut, 0.02f)
    }

    @Test
    fun `realtimeProjectState cleanly isolates transient sticker and audio updates`() {
        val stickerId = "sticker_transient"
        val audioId = "audio_transient"

        RealtimeProjectState.updateTransform(
            stickerId,
            TransformState(x = 0.7f, y = 0.3f, scaleX = 1.2f, scaleY = 1.2f, rotation = -10f)
        )
        RealtimeProjectState.updateTiming(stickerId, 1 * SECOND, 4 * SECOND)
        RealtimeProjectState.updateTiming(audioId, 2 * SECOND, 7 * SECOND)

        val stickerTransform = RealtimeProjectState.transform(stickerId)
        assertNotNull(stickerTransform)
        assertEquals(0.7f, stickerTransform!!.x, 0.001f)
        assertEquals(-10f, stickerTransform.rotation, 0.001f)

        val stickerTiming = RealtimeProjectState.timing(stickerId)
        assertNotNull(stickerTiming)
        assertEquals(1 * SECOND, stickerTiming!!.startUs)
        assertEquals(4 * SECOND, stickerTiming.endUs)

        val audioTiming = RealtimeProjectState.timing(audioId)
        assertNotNull(audioTiming)
        assertEquals(2 * SECOND, audioTiming!!.startUs)

        // Clear only sticker
        RealtimeProjectState.clear(stickerId)
        assertNull(RealtimeProjectState.transform(stickerId))
        assertNull(RealtimeProjectState.timing(stickerId))
        assertNotNull(RealtimeProjectState.timing(audioId))

        // Clear audio
        RealtimeProjectState.clear(audioId)
        assertNull(RealtimeProjectState.timing(audioId))
    }

    @Test
    fun `magneticSnapper detects sticker audio and keyframe boundaries`() {
        val snapper = MagneticSnapper()
        val targets = listOf(
            2 * SECOND, // Audio start
            7 * SECOND, // Audio end
            3 * SECOND, // Sticker start
            6 * SECOND, // Sticker end
            4 * SECOND  // Keyframe diamond
        )

        // Time close to keyframe (within threshold) snaps directly to 4s
        val pps = 56f
        val density = 2f
        val nearKeyframe = 4_050_000L // 50ms away
        val snapped = snapper.snap(nearKeyframe, targets.asSequence(), pps, density) {}
        assertEquals(4 * SECOND, snapped)

        // Time far from any target does not snap
        val farTime = 8_500_000L
        val notSnapped = snapper.snap(farTime, targets.asSequence(), pps, density) {}
        assertEquals(farTime, notSnapped)
    }
}
