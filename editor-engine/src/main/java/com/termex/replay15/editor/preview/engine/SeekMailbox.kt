package com.termex.replay15.editor.preview.engine

/** One pending seek, not one queued worker per pointer event. Thread safe. */
class SeekMailbox(private val nowNs: () -> Long = System::nanoTime) {
    enum class Mode { FAST_SCRUB, PRECISE }
    data class Request(val generation: Long, val projectTimeUs: Long, val mode: Mode, val requestedNs: Long)
    private var generation = 0L
    private var pending: Request? = null
    private var closed = false
    var coalescedCount = 0L
        private set

    @Synchronized fun submit(projectTimeUs: Long, mode: Mode): Request {
        check(!closed)
        require(projectTimeUs >= 0)
        if (pending != null) coalescedCount++
        return Request(++generation, projectTimeUs, mode, nowNs()).also { pending = it }
    }

    @Synchronized fun submit(projectTimeUs: Long, mode: Mode, transportGeneration: Long): Request {
        check(!closed)
        require(projectTimeUs >= 0 && transportGeneration > generation)
        if (pending != null) coalescedCount++
        generation = transportGeneration
        return Request(generation, projectTimeUs, mode, nowNs()).also { pending = it }
    }

    @Synchronized fun take(): Request? = pending.also { pending = null }
    @Synchronized fun isCurrent(token: Long): Boolean = !closed && token == generation
    @Synchronized fun currentGeneration(): Long = generation

    /**
     * Serialize acceptance of a consumed frame with submit(). Keep publish short: commit frame
     * identity only, never decode or EGL swap while holding this lock. The renderer must also
     * check the generation before submitting a draw queued after acceptance, because an old
     * SurfaceTexture callback/render task can arrive after a new seek.
     */
    @Synchronized fun publishIfCurrent(token: Long, publish: () -> Unit): Boolean {
        if (!isCurrent(token)) return false
        publish()
        return true
    }

    @Synchronized fun invalidate() { generation++; pending = null }
    @Synchronized fun close() { closed = true; invalidate() }
}
