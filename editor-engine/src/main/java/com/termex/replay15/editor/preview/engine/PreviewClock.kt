package com.termex.replay15.editor.preview.engine

import com.termex.replay15.editor.preview.engine.audio.PreviewTransportClock

/** Compatibility facade; there is only one clock implementation. */
@Deprecated("Use PreviewTransportClock")
class PreviewClock(nowNs: () -> Long = System::nanoTime) {
    private val transport = PreviewTransportClock(nowNs = nowNs)
    fun positionUs(): Long = transport.positionUs()
    fun isPlaying(): Boolean = transport.isPlaying()
    fun setDuration(durationUs: Long) = transport.setDuration(durationUs)
    fun seek(projectTimeUs: Long) = transport.seek(projectTimeUs)
    fun start() = transport.start()
    fun pause() = transport.pause()
}
