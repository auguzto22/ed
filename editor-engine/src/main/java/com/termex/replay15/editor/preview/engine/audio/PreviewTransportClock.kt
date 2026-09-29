package com.termex.replay15.editor.preview.engine.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Single master transport clock for the entire preview engine.
 * When audio playback is active and valid, the hardware AudioTrack acts as the MASTER CLOCK.
 * When audio is absent, muted, or preparing, falls back smoothly to a monotonic clock without jumps.
 */
class PreviewTransportClock(
    private val audioPositionProvider: () -> Long? = { null },
    private val nowNs: () -> Long = System::nanoTime
) {
    private var anchorNs = nowNs()
    private var anchorUs = 0L
    private var durationUs = 0L
    private var running = false
    private var lastAudioUs: Long? = null
    val transportGeneration = AtomicLong(0L)

    @Synchronized
    fun positionUs(): Long {
        if (!running) return anchorUs

        // 1. Audio Master Clock
        val fallbackUs = anchorUs + ((nowNs() - anchorNs) / 1_000L).coerceAtLeast(0)
        val audioPos = audioPositionProvider()?.takeIf { candidate ->
            candidate in 0..durationUs && candidate >= (lastAudioUs ?: Long.MIN_VALUE) &&
                kotlin.math.abs(candidate - fallbackUs) <= AUDIO_REACQUIRE_TOLERANCE_US
        }
        if (audioPos != null) {
            // Re-anchor monotonic fallback in real time so switching has zero jump
            anchorUs = audioPos
            anchorNs = nowNs()
            lastAudioUs = audioPos
            return audioPos
        }

        // 2. Monotonic fallback clock
        val elapsedUs = ((nowNs() - anchorNs) / 1_000L).coerceAtLeast(0)
        if (durationUs > 0 && elapsedUs >= durationUs - anchorUs) {
            anchorUs = durationUs
            anchorNs = nowNs()
            running = false
            return durationUs
        }
        return anchorUs + elapsedUs
    }

    @Synchronized
    fun isPlaying(): Boolean = running && positionUs() < durationUs

    @Synchronized
    fun setDuration(durationUs: Long) {
        require(durationUs >= 0)
        anchorUs = positionUs().coerceAtMost(durationUs)
        anchorNs = nowNs()
        this.durationUs = durationUs
        if (anchorUs >= durationUs) running = false
    }

    @Synchronized
    fun seek(projectTimeUs: Long) {
        anchorUs = projectTimeUs.coerceIn(0, durationUs)
        anchorNs = nowNs()
        lastAudioUs = null
        if (anchorUs >= durationUs) running = false
    }

    @Synchronized
    fun start() {
        if (running || anchorUs >= durationUs) return
        anchorNs = nowNs()
        running = true
    }

    @Synchronized
    fun pause() {
        anchorUs = positionUs()
        anchorNs = nowNs()
        running = false
    }

    @Synchronized fun nextGeneration(): Long = transportGeneration.incrementAndGet()

    companion object { private const val AUDIO_REACQUIRE_TOLERANCE_US = 250_000L }
}
