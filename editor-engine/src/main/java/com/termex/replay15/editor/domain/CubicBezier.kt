package com.termex.replay15.editor.domain

data class CubicBezier(val x1: Float = .25f, val y1: Float = .1f, val x2: Float = .25f, val y2: Float = 1f) {
    init { require(x1 in 0f..1f && x2 in 0f..1f && y1 in -2f..3f && y2 in -2f..3f) }
    fun apply(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        fun coordinate(t: Float, a: Float, b: Float): Float {
            val u = 1f - t
            return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
        }
        var low = 0f; var high = 1f
        repeat(24) {
            val mid = (low + high) * .5f
            if (coordinate(mid, x1, x2) < x) low = mid else high = mid
        }
        return coordinate((low + high) * .5f, y1, y2)
    }
}
