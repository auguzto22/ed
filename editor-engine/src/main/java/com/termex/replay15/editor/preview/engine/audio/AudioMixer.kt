package com.termex.replay15.editor.preview.engine.audio

import kotlin.math.tanh
import com.termex.replay15.editor.preview.engine.ResolvedAudioSource

/**
 * Mixes multiple active audio streams in block chunks into normalized float PCM.
 * Reuses internal buffers to ensure zero allocations during the mix loop.
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
            for (frame in 0 until readFrames) {
                mixed[frame * 2] += scratchBuffer[frame * 2] * gain
                mixed[frame * 2 + 1] += scratchBuffer[frame * 2 + 1] * gain
            }
        }

        // Apply soft limiter / clamp to avoid harsh digital clipping
        for (i in 0 until totalSamples) {
            val s = mixed[i]
            mixed[i] = if (s > 1.0f || s < -1.0f) {
                // Soft saturation curve when exceeding unity gain
                tanh(s)
            } else {
                s
            }
        }

        return block
    }
}
