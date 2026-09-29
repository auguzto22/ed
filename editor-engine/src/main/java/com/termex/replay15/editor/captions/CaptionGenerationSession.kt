package com.termex.replay15.editor.captions

import com.termex.replay15.editor.domain.Project
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** A result can be applied only by the generation that produced it. */
class CaptionGenerationSession {
    private val activeGenerationId = AtomicLong(0)
    fun begin(): Long = activeGenerationId.incrementAndGet()
    fun cancel() { activeGenerationId.incrementAndGet() }
    fun cancellation(id: Long): CaptionCancellation = CaptionCancellation {
        if (id != activeGenerationId.get()) throw CancellationException("Geração de legendas cancelada")
    }
    fun apply(id: Long, current: Project, captions: CaptionProject, offsetMs: Long = 0): Project? {
        if (id != activeGenerationId.get()) return null
        val mapped = CaptionTimelineMapper().map(current, captions, offsetMs)
        return if (id == activeGenerationId.get()) mapped else null
    }
}
