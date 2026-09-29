package com.termex.replay15.editor.animation

import com.termex.replay15.editor.domain.*
import kotlin.math.roundToLong

enum class AnimationCategory(val label: String) { IN("Entrada"), OUT("Saida"), LOOP("Repeticao") }
data class AnimationPoint(val time: Float, val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f,
    val rotation: Float = 0f, val opacity: Float = 1f, val easing: Easing = Easing.SMOOTH,
    val bezier: CubicBezier = CubicBezier()) {
    init {
        require(time in 0f..1f && zoom in .1f..4f && x in -1f..1f && y in -1f..1f)
        require(rotation in -360f..360f && opacity in 0f..1f)
    }
}
data class AnimationPreset(val id: String, val version: Int, val name: String, val category: AnimationCategory,
    val points: List<AnimationPoint>, val license: String) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{0,63}")) && version in 1..100000 && name.length in 1..100 && license.length in 1..2000)
        require(points.size in 2..32 && points.first().time == 0f && points.last().time == 1f)
        require(points.zipWithNext().all { (a, b) -> a.time < b.time })
        if (category == AnimationCategory.LOOP) require(points.first().copy(time = 1f, easing = points.last().easing, bezier = points.last().bezier) == points.last()) {
            "Uma animacao em loop precisa terminar na mesma pose em que comecou"
        }
    }

    /** Presets compile into editable keyframes, so projects do not depend on installed packs. */
    fun applyTo(clip: VideoClip, durationUs: Long): VideoClip {
        require(durationUs >= 50_000L) { "A duracao minima e 0.05 segundo" }
        require(clip.durationUs >= 50_000L) { "Clipe muito curto para esta animacao" }
        val duration = minOf(durationUs, clip.durationUs)
        val start = if (category == AnimationCategory.OUT) clip.durationUs - duration else 0L
        val end = if (category == AnimationCategory.IN) duration else clip.durationUs
        val repeats = if (category == AnimationCategory.LOOP) (clip.durationUs + duration - 1) / duration else 1L
        require(repeats * (points.size - 1) + 3 <= 200) { "Aumente o periodo da repeticao para usar ate 200 keyframes" }
        val sourceStart = clip.timeMap.sourceAt(start); val sourceEnd = clip.timeMap.sourceAt(end)
        val base = TransformKeyframe(sourceStart, clip.zoom, clip.offsetX, clip.offsetY, clip.fineRotation, clip.opacity)
        val result = clip.keyframes.filterNot { it.sourceUs in sourceStart..sourceEnd }.toMutableList()
        if (category == AnimationCategory.OUT && result.none { it.sourceUs == clip.inUs }) result += base.copy(sourceUs = clip.inUs, easing = Easing.HOLD)
        for (cycle in 0 until repeats.toInt()) {
            val cycleStart = start + cycle * duration
            // A partial last cycle is stretched to finish at the original pose, not cut mid-motion.
            val cycleDuration = minOf(duration, end - cycleStart)
            points.forEach { point ->
                val time = cycleStart + (point.time * cycleDuration.toDouble()).roundToLong()
                val source = clip.timeMap.sourceAt(time)
                val key = TransformKeyframe(source,
                    (base.zoom * point.zoom).coerceIn(.25f, 4f), (base.x + point.x).coerceIn(-.5f, .5f),
                    (base.y + point.y).coerceIn(-.5f, .5f), (base.rotation + point.rotation).coerceIn(-180f, 180f),
                    (base.opacity * point.opacity).coerceIn(0f, 1f), point.easing, point.bezier)
                result.removeAll { it.sourceUs == source }; result += key
            }
        }
        require(result.size <= 200) { "O clipe ja possui muitos keyframes. Remova pontos ou aumente o periodo." }
        return clip.copy(keyframes = result.sortedBy { it.sourceUs })
    }
}
