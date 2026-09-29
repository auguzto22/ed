package com.termex.replay15.editor.domain

import java.util.Locale
import kotlin.math.abs

const val MIN_CLIP_SPEED = .1f
const val MAX_CLIP_SPEED = 16f

fun formatSpeed(speed: Float): String {
    require(speed.isFinite() && speed > 0f)
    val rounded = speed.toInt()
    if (abs(speed - rounded) < .001f) return "$rounded×"
    return String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"
}

val VideoClip.speedBadge: String?
    get() = when {
        speedCurve.isNotEmpty() -> "Curva"
        kotlin.math.abs(speed - 1f) < .001f -> null
        else -> formatSpeed(speed)
    }
