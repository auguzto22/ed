package com.termex.replay15.editor.domain

enum class Easing(val label: String) {
    LINEAR("Linear"), SMOOTH("Suave"), EASE_IN("Acelerar"), EASE_OUT("Desacelerar"), HOLD("Manter"),
    EASE_IN_OUT("Acelerar e desacelerar"), BEZIER("Bezier personalizada"), SPRING("Mola"),
    BOUNCE("Quique"), ELASTIC("Elastico"), BACK("Antecipacao");
    fun apply(value: Float, bezier: CubicBezier = CubicBezier()): Float {
        val t = value.coerceIn(0f, 1f)
        if (t == 0f || t == 1f) return t
        return when (this) {
            LINEAR -> t
            SMOOTH -> t * t * (3f - 2f * t)
            EASE_IN -> t * t
            EASE_OUT -> 1f - (1f - t) * (1f - t)
            HOLD -> if (t >= 1f) 1f else 0f
            EASE_IN_OUT -> if (t < .5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f
            BEZIER -> bezier.apply(t)
            SPRING -> {
                fun response(x: Double): Double = 1 - kotlin.math.exp(-7 * x) *
                    (kotlin.math.cos(12 * x) + 7.0 / 12.0 * kotlin.math.sin(12 * x))
                (response(t.toDouble()) / response(1.0)).toFloat()
            }
            BOUNCE -> {
                val n = 7.5625f; val d = 2.75f
                when {
                    t < 1f / d -> n * t * t
                    t < 2f / d -> (t - 1.5f / d).let { n * it * it + .75f }
                    t < 2.5f / d -> (t - 2.25f / d).let { n * it * it + .9375f }
                    else -> (t - 2.625f / d).let { n * it * it + .984375f }
                }
            }
            ELASTIC -> (Math.pow(2.0, -10.0 * t) * kotlin.math.sin((t * 10 - .75) * (2 * Math.PI / 3)) + 1).toFloat()
            BACK -> { val u = t - 1; 1f + 2.70158f * u * u * u + 1.70158f * u * u }
        }
    }
}

data class TransformKeyframe(
    val sourceUs: Long, val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f,
    val rotation: Float = 0f, val opacity: Float = 1f, val easing: Easing = Easing.SMOOTH,
    val bezier: CubicBezier = CubicBezier(),
    val z: Float = 0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val scaleZ: Float = 1f,
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
    val anchorZ: Float = 0f,
) {
    init {
        require(sourceUs >= 0 && zoom in .25f..4f && x in -.5f.. .5f && y in -.5f.. .5f)
        require(rotation in -180f..180f && opacity in 0f..1f)
        require(z.isFinite() && rotationX.isFinite() && rotationY.isFinite() && scaleZ.isFinite())
        require(anchorX.isFinite() && anchorY.isFinite() && anchorZ.isFinite())
    }
}

fun VideoClip.transformAt(sourceTimeUs: Long): TransformKeyframe {
    if (keyframes.isEmpty()) {
        val baseZ = if (is3D) transform3D.positionZ else 0f
        val baseRotX = if (is3D) transform3D.rotationX else 0f
        val baseRotY = if (is3D) transform3D.rotationY else 0f
        val baseScaleZ = if (is3D) transform3D.scaleZ else 1f
        val baseAnchorX = if (is3D) transform3D.anchorX else 0f
        val baseAnchorY = if (is3D) transform3D.anchorY else 0f
        val baseAnchorZ = if (is3D) transform3D.anchorZ else 0f
        val baseZoom = if (is3D && transform3D.scaleX != 1f) transform3D.scaleX else zoom
        val baseX = if (is3D && transform3D.positionX != 0f) transform3D.positionX else offsetX
        val baseY = if (is3D && transform3D.positionY != 0f) transform3D.positionY else offsetY
        val baseRot = if (is3D && transform3D.rotationZ != 0f) transform3D.rotationZ else fineRotation
        val baseOpacity = if (is3D && transform3D.opacity != 1f) transform3D.opacity else opacity

        return TransformKeyframe(
            sourceTimeUs.coerceAtLeast(0), baseZoom, baseX, baseY, baseRot, baseOpacity,
            Easing.SMOOTH, CubicBezier(),
            z = baseZ, rotationX = baseRotX, rotationY = baseRotY, scaleZ = baseScaleZ,
            anchorX = baseAnchorX, anchorY = baseAnchorY, anchorZ = baseAnchorZ,
        )
    }
    val upper = keyframes.upperBoundTime(sourceTimeUs, TransformKeyframe::sourceUs)
    if (upper == 0) return keyframes.first()
    if (upper == keyframes.size) return keyframes.last()
    val a = keyframes[upper - 1]; val b = keyframes[upper]
    val t = a.easing.apply((sourceTimeUs - a.sourceUs).toFloat() / (b.sourceUs - a.sourceUs), a.bezier)
    fun mix(x: Float, y: Float) = x + (y - x) * t
    return TransformKeyframe(
        sourceTimeUs,
        mix(a.zoom, b.zoom).coerceIn(.25f, 4f),
        mix(a.x, b.x).coerceIn(-.5f, .5f),
        mix(a.y, b.y).coerceIn(-.5f, .5f),
        mix(a.rotation, b.rotation).coerceIn(-180f, 180f),
        mix(a.opacity, b.opacity).coerceIn(0f, 1f),
        a.easing,
        a.bezier,
        z = mix(a.z, b.z),
        rotationX = mix(a.rotationX, b.rotationX),
        rotationY = mix(a.rotationY, b.rotationY),
        scaleZ = mix(a.scaleZ, b.scaleZ),
        anchorX = mix(a.anchorX, b.anchorX),
        anchorY = mix(a.anchorY, b.anchorY),
        anchorZ = mix(a.anchorZ, b.anchorZ),
    )
}

enum class MaskShape(val label: String) {
    NONE("Sem mascara"), CIRCLE("Circulo"), RECTANGLE("Retangulo"), CINEMA("Cinema 2.35:1"),
    LINEAR("Linear"), MIRROR("Espelho"), SPLIT("Faixa vertical"), OVAL("Oval"), DIAMOND("Losango")
}

enum class HslBand(val label: String) {
    RED("Vermelho"), ORANGE("Laranja"), YELLOW("Amarelo"), GREEN("Verde"),
    CYAN("Ciano"), BLUE("Azul"), PURPLE("Roxo"), MAGENTA("Magenta")
}
data class HslAdjustment(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f) {
    init { require(hue in -180f..180f && saturation in -1f..1f && luminance in -1f..1f) }
}

data class StudioGrade(
    val exposure: Float = 0f, val shadows: Float = 0f, val highlights: Float = 0f,
    val vignette: Float = 0f, val grain: Float = 0f, val sharpen: Float = 0f,
    val curve: List<Float> = listOf(0f, .25f, .5f, .75f, 1f),
    val mask: MaskShape = MaskShape.NONE, val maskSize: Float = .8f, val feather: Float = .05f,
    val invertMask: Boolean = false, val chromaEnabled: Boolean = false,
    val chromaColor: Int = 0xFF00FF00.toInt(), val chromaTolerance: Float = .25f,
    val lutPath: String = "", val lutStrength: Float = 1f,
    val maskX: Float = 0f, val maskY: Float = 0f, val maskRotation: Float = 0f,
    val maskAspect: Float = 1f, val maskOpacity: Float = 1f,
    val chromaSmoothness: Float = .08f, val chromaSpill: Float = 0f, val chromaEdge: Float = 0f,
    val hsl: List<HslAdjustment> = List(8) { HslAdjustment() },
    val channelCurves: List<List<Float>> = List(3) { listOf(0f, .25f, .5f, .75f, 1f) },
) {
    init {
        require(exposure in -3f..3f && shadows in -1f..1f && highlights in -1f..1f)
        require(vignette in 0f..1f && grain in 0f..1f && sharpen in 0f..1f)
        require(curve.size == 5 && curve.all { it in 0f..1f })
        require(maskSize in .1f..1f && feather in .001f.. .5f && chromaTolerance in .01f.. .8f)
        require(lutPath.length <= 1000 && lutStrength in 0f..1f)
        require(maskX in -.5f.. .5f && maskY in -.5f.. .5f && maskRotation in -180f..180f)
        require(maskAspect in .1f..5f && maskOpacity in 0f..1f)
        require(chromaSmoothness in .001f.. .5f && chromaSpill in 0f..1f && chromaEdge in -.2f.. .2f)
        require(hsl.size == 8 && channelCurves.size == 3 && channelCurves.all { channel -> channel.size == 5 && channel.all { it in 0f..1f } })
    }
    fun curveAt(input: Float): Float {
        val t = input.coerceIn(0f, 1f) * 4
        val index = t.toInt().coerceAtMost(3)
        return curve[index] + (curve[index + 1] - curve[index]) * (t - index)
    }
}

/** Avoids opening a custom GPU pipeline for clips that do not use Studio compositing. */
fun VideoClip.needsStudioEffect(): Boolean =
    filter != VideoFilter.ORIGINAL.ordinal || grade != StudioGrade() || opacity != 1f || keyframes.any { it.opacity != 1f }

data class TimelineMarker(val id: String = newId(), val timeUs: Long, val name: String = "Marcador", val color: Int = 0xFFFFC66D.toInt()) {
    init { require(timeUs >= 0 && name.length in 1..100) }
}

enum class CanvasFill(val label: String) { FIT("Ajustar"), FILL("Preencher") }

const val MAX_TEXTS = 2000
