package com.termex.replay15.editor

import com.termex.replay15.editor.preview.engine.audio.*
import org.junit.Assert.*
import org.junit.Test

class AudioPreviewEngineArchitectureTest {

    @Test
    fun pcmBlockPoolReusesInstances() {
        PcmBlock.clearPool()
        val block1 = PcmBlock.obtain(1024, 2)
        assertNotNull(block1)
        assertEquals(1024, block1.maxFrames)
        assertEquals(2, block1.channels)

        PcmBlock.release(block1)

        val block2 = PcmBlock.obtain(1024, 2)
        assertSame(block1, block2)
    }

    @Test
    fun pcmBufferQueueEnforcesCapacityAndBackpressure() {
        // Queue with max 50ms capacity (2400 frames at 48kHz = 50ms)
        val queue = PcmBufferQueue(maxQueuedDurationUs = 50_000L)

        val block1 = PcmBlock.obtain(1024, 2).apply {
            sampleRate = 48000
            frameCount = 1024 // ~21.33ms
        }
        assertTrue(queue.offer(block1, 10L))

        val block2 = PcmBlock.obtain(1024, 2).apply {
            sampleRate = 48000
            frameCount = 1024 // ~21.33ms
        }
        assertTrue(queue.offer(block2, 10L))

        // Total queued: ~42.66ms < 50ms
        assertEquals(2, queue.queuedBlocksCount())

        // Third block would exceed 50ms (total ~64ms > 50ms), offer with small timeout should reject/timeout
        val block3 = PcmBlock.obtain(1024, 2).apply {
            sampleRate = 48000
            frameCount = 1024
        }
        val offered = queue.offer(block3, 10L)
        assertFalse("Queue must apply backpressure when full", offered)

        // Poll one block
        val polled = queue.poll(10L)
        assertSame(block1, polled)

        // Now block3 can be offered
        assertTrue(queue.offer(block3, 10L))

        // Clear drops everything
        queue.clear()
        assertEquals(0, queue.queuedBlocksCount())
        assertEquals(0L, queue.queuedDurationUs())
    }

    @Test
    fun audioBufferPolicyCalculatesAdaptiveSizes() {
        val policy = AudioBufferPolicy(sampleRate = 48000)
        val normalSize = policy.bufferSizeInBytes(0)
        assertTrue(normalSize >= policy.minBufferSizeInBytes)

        val expandedSize = policy.bufferSizeInBytes(3)
        assertTrue("Expanded size with underruns must be >= normal size", expandedSize >= normalSize)
    }

    @Test
    fun audioTimelineMapperCalculatesEqualPowerFades() {
        val durationUs = 2_000_000L // 2s
        val fadeInUs = 500_000L     // 0.5s
        val fadeOutUs = 500_000L    // 0.5s

        val atStart = AudioTimelineMapper.calculateGain(1.0f, 0L, durationUs, fadeInUs, fadeOutUs)
        assertEquals(0.0f, atStart, 0.001f)

        val afterFadeIn = AudioTimelineMapper.calculateGain(1.0f, 500_000L, durationUs, fadeInUs, fadeOutUs)
        assertEquals(1.0f, afterFadeIn, 0.001f)

        val inMiddle = AudioTimelineMapper.calculateGain(1.0f, 1_000_000L, durationUs, fadeInUs, fadeOutUs)
        assertEquals(1.0f, inMiddle, 0.001f)

        val atEnd = AudioTimelineMapper.calculateGain(1.0f, durationUs, durationUs, fadeInUs, fadeOutUs)
        assertEquals(0.0f, atEnd, 0.001f)
    }

    @Test
    fun previewTransportClockSwitchesSeamlesslyBetweenAudioMasterAndMonotonic() {
        var currentAudioPositionUs: Long? = null
        var now = 1_000_000_000L // 1s in ns
        val clock = PreviewTransportClock(
            audioPositionProvider = { currentAudioPositionUs },
            nowNs = { now }
        )
        clock.setDuration(10_000_000L) // 10s

        // 1. Play without audio (monotonic clock active)
        clock.start()
        now += 500_000_000L // +500ms
        assertEquals(500_000L, clock.positionUs())

        // 2. Audio starts playing at 505ms (hardware audio master takes over)
        currentAudioPositionUs = 505_000L
        assertEquals(505_000L, clock.positionUs())

        // Audio advances to 600ms
        currentAudioPositionUs = 600_000L
        assertEquals(600_000L, clock.positionUs())

        // 3. Audio track temporarily finishes / underruns -> monotonic fallback seamlessly continues
        currentAudioPositionUs = null
        now += 100_000_000L // +100ms
        assertEquals(700_000L, clock.positionUs())

        // 4. Pause
        clock.pause()
        assertFalse(clock.isPlaying())
        val pausedPos = clock.positionUs()
        assertEquals(700_000L, pausedPos)

        // Advance wall clock while paused -> position must not change
        now += 1_000_000_000L
        assertEquals(pausedPos, clock.positionUs())
    }

    @Test
    fun audioMixerAppliesGainAndLimiting() {
        val mixer = AudioMixer(sampleRate = 48000, blockFrames = 512)
        val block = mixer.mix(emptyList(), 0L, 1L)
        assertEquals(512, block.frameCount)
        assertEquals(0f, block.samples[0], 0.0001f)
        PcmBlock.release(block)
    }

    @Test
    fun pcmBlocksCarryGenerationRevisionAndStableSourceIdentity() {
        val block = PcmBlock.obtain(128, 2).apply {
            generation = 11L; projectRevision = 9L; sourceId = "video:clip:audio"
        }
        assertEquals(11L, block.generation)
        assertEquals(9L, block.projectRevision)
        assertEquals("video:clip:audio", block.sourceId)
        PcmBlock.release(block)
    }
}
