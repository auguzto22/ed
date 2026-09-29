package com.termex.replay15.editor.domain

import kotlin.math.abs

/** Shapes supported by a non-destructive visual-layer mask. */
enum class MaskType(val label: String) {
    RECTANGLE("Retangulo"),
    CIRCLE("Circulo"),
    ELLIPSE("Elipse"),
    LINEAR("Linear"),
    MIRROR("Espelho"),
    HEART("Coracao"),
    STAR("Estrela"),
    CUSTOM_PATH("Caminho personalizado"),
}

/** One normalized control point inside a custom mask path. */
data class MaskPathPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite())
        require(x in 0f..1f && y in 0f..1f)
    }
}

/**
 * Complete values stored at one mask keyframe.
 *
 * Video masks use source time; text and sticker masks use time local to the layer. This keeps
 * animation stable when a video is trimmed, moved or speed-ramped.
 */
data class MaskKeyframe(
    val timeUs: Long,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val feather: Float,
    val expansion: Float,
    val easing: Easing = Easing.SMOOTH,
    val bezier: CubicBezier = CubicBezier(),
) {
    init {
        require(timeUs >= 0L)
        validateMaskGeometry(centerX, centerY, width, height, rotation, feather, expansion)
    }

    companion object {
        fun from(timeUs: Long, state: MaskState, easing: Easing = Easing.SMOOTH): MaskKeyframe =
            MaskKeyframe(
                timeUs = timeUs,
                centerX = state.centerX,
                centerY = state.centerY,
                width = state.width,
                height = state.height,
                rotation = state.rotation,
                feather = state.feather,
                expansion = state.expansion,
                easing = easing,
            )
    }
}

/**
 * Serializable, resolution-independent mask attached to a visual layer.
 *
 * Coordinates and sizes are normalized to the untransformed layer. Runtime code evaluates the
 * keyframes and forwards only the resulting values to persistent GPU programs.
 */
data class MaskState(
    val type: MaskType,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val feather: Float,
    val expansion: Float,
    val opacity: Float,
    val inverted: Boolean,
    val customPath: List<MaskPathPoint> = emptyList(),
    val keyframes: List<MaskKeyframe> = emptyList(),
    /** Optional internal tracking result used to move this mask without visible keyframes. */
    val trackingTrackId: String? = null,
) {
    init {
        validateMaskGeometry(centerX, centerY, width, height, rotation, feather, expansion)
        require(opacity.isFinite() && opacity in 0f..1f)
        require(customPath.size <= MAX_MASK_PATH_POINTS)
        require(keyframes.size <= MAX_MASK_KEYFRAMES)
        require(keyframes.zipWithNext().all { (left, right) -> left.timeUs < right.timeUs })
        require(trackingTrackId == null || trackingTrackId.matches(ID_PATTERN))
    }

    fun at(timeUs: Long, tracking: TrackingTrack? = null): MaskState {
        val evaluated = if (keyframes.isEmpty()) null else run {
            val upper = keyframes.upperBoundTime(timeUs.coerceAtLeast(0L), MaskKeyframe::timeUs)
            when {
            upper == 0 -> keyframes.first()
            upper == keyframes.size -> keyframes.last()
            else -> {
                val left = keyframes[upper - 1]
                val right = keyframes[upper]
                val span = (right.timeUs - left.timeUs).coerceAtLeast(1L)
                val fraction = left.easing.apply((timeUs - left.timeUs).toFloat() / span, left.bezier)
                fun mix(a: Float, b: Float): Float = a + (b - a) * fraction
                MaskKeyframe(
                    timeUs = timeUs,
                    centerX = mix(left.centerX, right.centerX),
                    centerY = mix(left.centerY, right.centerY),
                    width = mix(left.width, right.width),
                    height = mix(left.height, right.height),
                    rotation = mixAngle(left.rotation, right.rotation, fraction),
                    feather = mix(left.feather, right.feather),
                    expansion = mix(left.expansion, right.expansion),
                    easing = left.easing,
                    bezier = left.bezier,
                )
            }
            }
        }
        val animated = evaluated?.let {
            copy(centerX = it.centerX, centerY = it.centerY, width = it.width, height = it.height,
                rotation = it.rotation, feather = it.feather, expansion = it.expansion, keyframes = emptyList())
        } ?: this
        val point = tracking?.at(timeUs)
        return if (point == null) animated else animated.copy(
            centerX = point.centerX,
            centerY = point.centerY,
            width = point.width,
            height = point.height,
        )
    }

    fun upsertKeyframe(timeUs: Long, values: MaskState = at(timeUs), toleranceUs: Long = 25_000L): MaskState {
        val target = timeUs.coerceAtLeast(0L)
        val replacement = MaskKeyframe.from(target, values)
        val index = keyframes.indexOfFirst { abs(it.timeUs - target) <= toleranceUs }
        val updated = if (index >= 0) keyframes.mapIndexed { i, old ->
            if (i == index) replacement.copy(easing = old.easing, bezier = old.bezier) else old
        } else keyframes + replacement
        return copy(keyframes = updated.sortedBy(MaskKeyframe::timeUs))
    }

    fun removeKeyframe(timeUs: Long, toleranceUs: Long = 25_000L): MaskState = copy(
        keyframes = keyframes.filterNot { abs(it.timeUs - timeUs) <= toleranceUs },
    )

    companion object {
        const val MAX_MASK_PATH_POINTS = 12
        const val MAX_MASK_KEYFRAMES = 200
        private val ID_PATTERN = Regex("[a-zA-Z0-9-]{1,80}")

        fun default(type: MaskType = MaskType.RECTANGLE): MaskState = MaskState(
            type = type,
            centerX = .5f,
            centerY = .5f,
            width = .75f,
            height = .75f,
            rotation = 0f,
            feather = .04f,
            expansion = 0f,
            opacity = 1f,
            inverted = false,
            customPath = if (type == MaskType.CUSTOM_PATH) DEFAULT_CUSTOM_PATH else emptyList(),
        )

        val DEFAULT_CUSTOM_PATH = listOf(
            MaskPathPoint(.18f, .18f),
            MaskPathPoint(.82f, .18f),
            MaskPathPoint(.9f, .5f),
            MaskPathPoint(.72f, .85f),
            MaskPathPoint(.28f, .85f),
            MaskPathPoint(.1f, .5f),
        )
    }
}

fun VideoClip.maskAt(sourceTimeUs: Long): MaskState? {
    val activeTrack = mask?.trackingTrackId?.let { id -> trackingTracks.firstOrNull { it.id == id } }
    return mask?.at(sourceTimeUs, activeTrack)
}
fun TextClip.maskAt(projectTimeUs: Long): MaskState? = mask?.at((projectTimeUs - startUs).coerceAtLeast(0L))
fun StickerClip.maskAt(projectTimeUs: Long): MaskState? = mask?.at((projectTimeUs - startUs).coerceAtLeast(0L))

private fun validateMaskGeometry(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    rotation: Float,
    feather: Float,
    expansion: Float,
) {
    require(listOf(centerX, centerY, width, height, rotation, feather, expansion).all { it.isFinite() })
    require(centerX in 0f..1f && centerY in 0f..1f)
    require(width in .01f..2f && height in .01f..2f)
    require(rotation in -180f..180f)
    require(feather in 0f.. .5f)
    require(expansion in -1f..1f)
}

private fun mixAngle(left: Float, right: Float, fraction: Float): Float {
    var delta = (right - left) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    val value = left + delta * fraction
    return when {
        value > 180f -> value - 360f
        value < -180f -> value + 360f
        else -> value
    }
}
