package com.termex.replay15.editor.preview.engine

import com.termex.replay15.editor.domain.Project

/** One immutable project revision consumed by video, audio and overlays. */
data class ProjectSnapshot(val revision: Long, val project: Project)

/** Shared identity of one preview presentation decision. */
data class PreviewFrameContext(
    val generation: Long,
    val projectRevision: Long,
    val projectTimeUs: Long,
    val audioClockUs: Long? = null,
    val videoPresentedUs: Long? = null,
)
