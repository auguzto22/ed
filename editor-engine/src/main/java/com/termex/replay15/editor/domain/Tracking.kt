package com.termex.replay15.editor.domain

import kotlin.math.abs

/** One normalized observation produced by a local tracker. */
data class TrackingPoint(
    val timeUs: Long,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
) {
    init {
        require(timeUs >= 0L)
        require(listOf(centerX, centerY, width, height, confidence).all { it.isFinite() })
        require(centerX in 0f..1f && centerY in 0f..1f)
        require(width in .001f..1f && height in .001f..1f)
        require(confidence in 0f..1f)
    }
}

/** Bounded internal track. It is not expanded into transform keyframes in the timeline UI. */
data class TrackingTrack(
    val id: String = newId(),
    val points: List<TrackingPoint>,
) {
    init {
        require(id.matches(ID_PATTERN))
        require(points.isNotEmpty() && points.size <= MAX_POINTS)
        require(points.zipWithNext().all { (a, b) -> a.timeUs < b.timeUs })
    }

    fun at(timeUs: Long): TrackingPoint {
        if (timeUs <= points.first().timeUs) return points.first()
        if (timeUs >= points.last().timeUs) return points.last()
        val index = points.indexOfFirst { it.timeUs >= timeUs }
        val left = points[index - 1]
        val right = points[index]
        val fraction = ((timeUs - left.timeUs).toFloat() / (right.timeUs - left.timeUs)).coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * fraction
        return TrackingPoint(timeUs, mix(left.centerX, right.centerX), mix(left.centerY, right.centerY),
            mix(left.width, right.width), mix(left.height, right.height), mix(left.confidence, right.confidence))
    }

    fun nearest(timeUs: Long): TrackingPoint = points.minBy { abs(it.timeUs - timeUs) }

    companion object {
        const val MAX_POINTS = 2_000
        private val ID_PATTERN = Regex("[a-zA-Z0-9-]{1,80}")
    }
}
