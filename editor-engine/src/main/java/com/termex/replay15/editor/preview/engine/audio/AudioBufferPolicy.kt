package com.termex.replay15.editor.preview.engine.audio

import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Calculates adaptive, low-latency buffer configuration for AudioTrack.
 */
class AudioBufferPolicy(
    val sampleRate: Int = 48000,
    val channelMask: Int = AudioFormat.CHANNEL_OUT_STEREO,
    val encoding: Int = AudioFormat.ENCODING_PCM_FLOAT,
) {
    private val channelCount = 2
    private val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
    private val bytesPerFrame = channelCount * bytesPerSample

    val minBufferSizeInBytes: Int = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        .coerceAtLeast(bytesPerFrame * 1024)

    private var currentCapacityFrames = calculateTargetFrames(0)

    fun bufferSizeInBytes(underrunCount: Int = 0): Int {
        val targetFrames = calculateTargetFrames(underrunCount)
        currentCapacityFrames = targetFrames
        val bytes = targetFrames * bytesPerFrame
        return maxOf(minBufferSizeInBytes, bytes)
    }

    private fun calculateTargetFrames(underrunCount: Int): Int {
        // Base low-latency target: ~60ms (2880 frames at 48kHz)
        val baseFrames = (sampleRate * 60L / 1000L).toInt()
        // If underruns occur repeatedly, adaptively expand up to ~120ms (5760 frames at 48kHz)
        val penalty = (underrunCount * (sampleRate * 15L / 1000L)).toInt()
        val maxFrames = (sampleRate * 120L / 1000L).toInt()
        return (baseFrames + penalty).coerceAtMost(maxFrames)
    }

    fun framesToUs(frames: Long): Long =
        if (sampleRate > 0) (frames * 1_000_000L) / sampleRate else 0L

    fun usToFrames(us: Long): Long =
        if (sampleRate > 0) (us * sampleRate) / 1_000_000L else 0L
}
