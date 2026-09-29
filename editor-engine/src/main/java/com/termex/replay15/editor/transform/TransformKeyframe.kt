package com.termex.replay15.editor.transform

import com.termex.replay15.editor.domain.CubicBezier
import com.termex.replay15.editor.domain.Easing

/**
 * Generic timestamped keyframe holding a TransformState with easing.
 *
 * @param timeUs Timestamp in microseconds along the clip or timeline.
 * @param transform Target TransformState at this keyframe.
 * @param easing Easing type applied towards the NEXT keyframe.
 * @param bezier Custom cubic bezier control points if easing == Easing.BEZIER.
 */
data class TransformKeyframe(
    val timeUs: Long,
    val transform: TransformState,
    val easing: Easing = Easing.SMOOTH,
    val bezier: CubicBezier = CubicBezier(),
) {
    init {
        require(timeUs >= 0L) { "Keyframe timeUs must be non-negative" }
    }
}
