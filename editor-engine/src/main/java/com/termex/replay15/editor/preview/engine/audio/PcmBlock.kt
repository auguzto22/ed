package com.termex.replay15.editor.preview.engine.audio

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Encapsulates a slice of interleaved floating-point PCM samples anchored to project time.
 */
class PcmBlock(
    val maxFrames: Int,
    val channels: Int = 2
) {
    var projectStartUs: Long = 0L
    var frameCount: Int = 0
    var sampleRate: Int = 48000
    var generation: Long = 0L
    var projectRevision: Long = 0L
    var sourceId: String = ""
    val samples: FloatArray = FloatArray(maxFrames * channels)

    val durationUs: Long
        get() = if (sampleRate > 0) (frameCount.toLong() * 1_000_000L) / sampleRate else 0L

    val sampleCount: Int
        get() = frameCount * channels

    fun clear() {
        projectStartUs = 0L
        frameCount = 0
        generation = 0L
        projectRevision = 0L
        sourceId = ""
        samples.fill(0f)
    }

    companion object {
        private const val POOL_SIZE = 32
        private val pool = ConcurrentLinkedQueue<PcmBlock>()

        fun obtain(maxFrames: Int = 1024, channels: Int = 2): PcmBlock {
            val block = pool.poll()
            if (block != null && block.maxFrames >= maxFrames && block.channels == channels) {
                block.clear()
                return block
            }
            return PcmBlock(maxFrames, channels)
        }

        fun release(block: PcmBlock) {
            if (pool.size < POOL_SIZE) {
                block.clear()
                pool.offer(block)
            }
        }

        fun clearPool() {
            pool.clear()
        }
    }
}
