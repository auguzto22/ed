package com.termex.replay15.editor.preview.engine.audio

import com.termex.replay15.editor.preview.engine.NativePreviewBridge
import kotlin.math.tanh
import com.termex.replay15.editor.preview.engine.ResolvedAudioSource

/**
 * Mixes multiple active audio streams in block chunks into normalized float PCM.
 * Reuses the decode scratch buffer; accumulation and limiting run in Rust.
 */
class AudioMixer(
    val sampleRate: Int = 48000,
    val blockFrames: Int = 1024
) {
    private val scratchBuffer = FloatArray(blockFrames * 16 * 2 + 4)

    /**
     * Active input source for a mix cycle.
     */
    data class Source(
        val resolved: ResolvedAudioSource,
        val session: AudioDecoderSession,
        val gain: Float,
        val speed: Float,
        val preservePitch: Boolean
    )

    fun mix(
        sources: List<Source>,
        projectStartUs: Long,
        generation: Long,
        projectRevision: Long = 0L,
    ): PcmBlock {
        val block = PcmBlock.obtain(blockFrames, 2)
        block.projectStartUs = projectStartUs
        block.frameCount = blockFrames
        block.sampleRate = sampleRate
        block.generation = generation
        block.projectRevision = projectRevision
        block.sourceId = sources.joinToString(",") { it.resolved.id }

        val mixed = block.samples
        mixed.fill(0f, 0, block.sampleCount)

        if (sources.isEmpty()) {
            return block
        }

        val totalSamples = block.sampleCount

        for (source in sources) {
            val gain = source.gain
            if (gain <= 0.001f) continue

            val readFrames = source.session.readTimelineFrames(scratchBuffer, blockFrames, source.speed, source.preservePitch, generation, source.resolved.enhance)
            if (readFrames <= 0) continue
            if (NativePreviewBridge.available) NativePreviewBridge.nativeMixSource(mixed, scratchBuffer, readFrames, gain)
            else for (index in 0 until readFrames * 2) mixed[index] += scratchBuffer[index] * gain
        }

        if (NativePreviewBridge.available) NativePreviewBridge.nativeLimitPcm(mixed, totalSamples)
        else for (index in 0 until totalSamples) if (kotlin.math.abs(mixed[index]) > 1f) mixed[index] = tanh(mixed[index])

        return block
    }
}
