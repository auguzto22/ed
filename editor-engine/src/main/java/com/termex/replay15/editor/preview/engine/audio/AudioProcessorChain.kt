package com.termex.replay15.editor.preview.engine.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles real-time speed adjustments and pitch preservation using Sonic.
 *
 * Sonic is still marked unstable by Media3, so the opt-in is declared here once rather than at
 * every call site that touches the processor chain.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class AudioProcessorChain(
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 2
) {
    private val sonic = SonicAudioProcessor()
    private var currentSpeed = 1f
    private var currentPitch = 1f
    private var isConfigured = false

    private val inputFormat = AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_16BIT)
    private var inputPcm = ByteBuffer.allocateDirect(1024 * 16 * channelCount * 2).order(ByteOrder.nativeOrder())

    init {
        configureSonic(1f, 1f)
    }

    private fun configureSonic(speed: Float, pitch: Float) {
        sonic.setSpeed(speed.coerceIn(0.1f, 16f))
        sonic.setPitch(pitch.coerceIn(0.1f, 16f))
        if (!isConfigured) {
            runCatching {
                sonic.configure(inputFormat)
                sonic.flush()
                isConfigured = true
            }
        }
    }

    fun updateParameters(speed: Float, preservePitch: Boolean) {
        val targetSpeed = speed.coerceIn(0.1f, 16f)
        val targetPitch = if (preservePitch) 1.0f else targetSpeed
        if (targetSpeed != currentSpeed || targetPitch != currentPitch) {
            currentSpeed = targetSpeed
            currentPitch = targetPitch
            configureSonic(targetSpeed, targetPitch)
        }
    }

    fun isBypassed(): Boolean = currentSpeed == 1f && currentPitch == 1f

    fun process(input: ByteBuffer): ByteBuffer {
        if (isBypassed() || !isConfigured) {
            return input
        }
        sonic.queueInput(input)
        return sonic.output
    }

    /** Converts float PCM through Sonic while retaining its streaming state between blocks. */
    fun processFloatPcm(input: FloatArray, inputFrames: Int, output: FloatArray, outputFrames: Int): Int {
        if (isBypassed()) {
            val frames = minOf(inputFrames, outputFrames)
            input.copyInto(output, 0, 0, frames * channelCount)
            if (frames < outputFrames) output.fill(0f, frames * channelCount, outputFrames * channelCount)
            return frames
        }
        val sampleCount = inputFrames * channelCount
        val needed = sampleCount * 2
        if (inputPcm.capacity() < needed) inputPcm = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
        inputPcm.clear()
        repeat(sampleCount) { inputPcm.putShort((input[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        inputPcm.flip()
        sonic.queueInput(inputPcm)
        var produced = 0
        while (produced < outputFrames * channelCount) {
            val ready = sonic.output
            if (!ready.hasRemaining()) break
            while (ready.remaining() >= 2 && produced < outputFrames * channelCount) {
                output[produced++] = ready.order(ByteOrder.nativeOrder()).short / 32768f
            }
        }
        if (produced < outputFrames * channelCount) output.fill(0f, produced, outputFrames * channelCount)
        return produced / channelCount
    }

    fun flush() {
        if (isConfigured) {
            sonic.flush()
        }
    }

    fun reset() {
        sonic.reset()
        isConfigured = false
        configureSonic(currentSpeed, currentPitch)
    }
}
