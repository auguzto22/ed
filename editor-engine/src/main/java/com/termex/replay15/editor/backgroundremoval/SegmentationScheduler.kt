package com.termex.replay15.editor.backgroundremoval

import java.util.concurrent.atomic.AtomicLong

/**
 * Single-flight, latest-frame-wins scheduler. A slow model can never accumulate an inference
 * queue: a pending frame replaces the previous pending frame and releases its input buffer.
 */
class SegmentationScheduler(
    private val engine: SegmentationEngine,
    private val maxFrequencyHz: Int = 12,
) : AutoCloseable {
    init { require(maxFrequencyHz > 0) }

    private data class Pending(
        val frame: SegmentationFrame,
        val timestampUs: Long,
        val callback: (SegmentationResult?) -> Unit,
    )

    private val lock = Any()
    private var inFlight = false
    private var pending: Pending? = null
    private var lastStartedUs = Long.MIN_VALUE
    private var closed = false
    private val dropped = AtomicLong()
    private val completed = AtomicLong()

    fun submit(frame: SegmentationFrame, timestampUs: Long, callback: (SegmentationResult?) -> Unit): Boolean {
        synchronized(lock) {
            if (closed) {
                frame.release()
                return false
            }
            val intervalUs = 1_000_000L / maxFrequencyHz
            val sameDirectionRateLimited = lastStartedUs != Long.MIN_VALUE &&
                timestampUs >= lastStartedUs && timestampUs - lastStartedUs < intervalUs
            if (inFlight || sameDirectionRateLimited) {
                pending?.frame?.release()
                if (pending != null) dropped.incrementAndGet()
                pending = Pending(frame, timestampUs, callback)
                return false
            }
            inFlight = true
            lastStartedUs = timestampUs
        }
        start(Pending(frame, timestampUs, callback))
        return true
    }

    private fun start(request: Pending) {
        engine.process(request.frame, request.timestampUs) { result ->
            try {
                request.callback(result)
                completed.incrementAndGet()
            } finally {
                val next = synchronized(lock) {
                    if (closed) {
                        request.frame.release()
                        pending?.frame?.release()
                        pending = null
                        inFlight = false
                        null
                    } else {
                        request.frame.release()
                        val candidate = pending
                        pending = null
                        if (candidate == null) inFlight = false else lastStartedUs = candidate.timestampUs
                        candidate
                    }
                }
                if (next != null) start(next)
            }
        }
    }

    fun droppedFrames(): Long = dropped.get()
    fun completedFrames(): Long = completed.get()

    override fun close() {
        synchronized(lock) {
            closed = true
            pending?.frame?.release()
            pending = null
        }
        engine.close()
    }
}
