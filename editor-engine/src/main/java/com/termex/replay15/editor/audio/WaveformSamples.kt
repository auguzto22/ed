package com.termex.replay15.editor.audio

import kotlin.math.abs

class WaveformSamples(val durationUs: Long, val peaks: FloatArray = FloatArray((durationUs / 50_000).toInt().coerceIn(1, 60_000))) {
    init { require(durationUs > 0 && peaks.size in 1..60_000) }
    fun add(timeUs: Long, amplitude: Float) {
        if (timeUs < 0 || timeUs >= durationUs || !amplitude.isFinite()) return
        val bucket = (timeUs.toDouble() / durationUs * peaks.size).toInt().coerceIn(peaks.indices)
        peaks[bucket] = maxOf(peaks[bucket], abs(amplitude).coerceAtMost(1f))
    }
    fun at(timeUs: Long): Float = if (timeUs !in 0 until durationUs) 0f else peaks[(timeUs.toDouble() / durationUs * peaks.size).toInt().coerceIn(peaks.indices)]
}
