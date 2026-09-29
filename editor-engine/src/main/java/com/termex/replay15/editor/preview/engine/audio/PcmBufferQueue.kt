package com.termex.replay15.editor.preview.engine.audio

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded queue of PCM blocks with backpressure.
 * Prevents the decoder/mixer from advancing seconds ahead of the real output,
 * strictly keeping latency bounded.
 */
class PcmBufferQueue(
    private val maxQueuedDurationUs: Long = 100_000L // 100ms max latency buffer
) {
    private val lock = ReentrantLock()
    private val notFull = lock.newCondition()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<PcmBlock>()

    @Volatile private var totalQueuedUs = 0L
    @Volatile private var isClosed = false

    fun offer(block: PcmBlock, timeoutMs: Long = 20L): Boolean {
        lock.withLock {
            if (isClosed) return false
            val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
            while ((queue.isNotEmpty() && totalQueuedUs + block.durationUs > maxQueuedDurationUs) && !isClosed) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0) return false
                notFull.awaitNanos(remainingNs)
            }
            if (isClosed) return false
            queue.addLast(block)
            totalQueuedUs += block.durationUs
            notEmpty.signal()
            return true
        }
    }

    fun poll(timeoutMs: Long = 10L): PcmBlock? {
        lock.withLock {
            val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
            while (queue.isEmpty() && !isClosed) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0) return null
                notEmpty.awaitNanos(remainingNs)
            }
            if (queue.isEmpty()) return null
            val block = queue.removeFirst()
            totalQueuedUs = (totalQueuedUs - block.durationUs).coerceAtLeast(0L)
            notFull.signal()
            return block
        }
    }

    /** Returns a block polled concurrently with pause without changing its playback order. */
    fun offerFirst(block: PcmBlock): Boolean = lock.withLock {
        if (isClosed || totalQueuedUs + block.durationUs > maxQueuedDurationUs) return false
        queue.addFirst(block); totalQueuedUs += block.durationUs; notEmpty.signal(); true
    }

    fun clear() {
        lock.withLock {
            while (queue.isNotEmpty()) {
                val block = queue.removeFirst()
                PcmBlock.release(block)
            }
            totalQueuedUs = 0L
            notFull.signalAll()
        }
    }

    fun queuedDurationUs(): Long = totalQueuedUs

    fun queuedBlocksCount(): Int = lock.withLock { queue.size }
    fun peek(): PcmBlock? = lock.withLock { queue.firstOrNull() }

    /** Sample-accurately removes prebuffer that fell behind the transport before output started. */
    fun trimHeadTo(projectTimeUs: Long) = lock.withLock {
        val block = queue.firstOrNull() ?: return@withLock
        val deltaUs = projectTimeUs - block.projectStartUs
        if (deltaUs <= 0L || deltaUs >= block.durationUs) return@withLock
        val oldDuration = block.durationUs
        val skipFrames = ((deltaUs * block.sampleRate) / 1_000_000L).toInt().coerceIn(0, block.frameCount - 1)
        val skipSamples = skipFrames * block.channels
        block.samples.copyInto(block.samples, 0, skipSamples, block.sampleCount)
        block.frameCount -= skipFrames
        block.projectStartUs += skipFrames.toLong() * 1_000_000L / block.sampleRate
        totalQueuedUs = (totalQueuedUs - oldDuration + block.durationUs).coerceAtLeast(0L)
    }

    fun close() {
        lock.withLock {
            isClosed = true
            clear()
            notFull.signalAll()
            notEmpty.signalAll()
        }
    }
}
