package com.termex.replay15.editor.domain

import kotlin.math.roundToLong

/** The single conversion boundary between project time and media time. */
interface ClipTimeMapper {
    fun projectToSource(clip: VideoClip, clipStartUs: Long, projectTimeUs: Long): Long
    fun sourceToProject(clip: VideoClip, clipStartUs: Long, sourceTimeUs: Long): Long
}

/** Preview and export share [VideoClip.timeMap], including trims and speed ramps. */
object ProjectClipTimeMapper : ClipTimeMapper {
    override fun projectToSource(clip: VideoClip, clipStartUs: Long, projectTimeUs: Long): Long =
        // sourceAt already clamps into the trimmed window; clamping again would be redundant,
        // and for a reversed clip the endpoints are swapped, so the window is normalised.
        clip.timeMap.sourceAt(projectTimeUs - clipStartUs)
            .coerceIn(minOf(clip.inUs, clip.outUs), maxOf(clip.inUs, clip.outUs))

    override fun sourceToProject(clip: VideoClip, clipStartUs: Long, sourceTimeUs: Long): Long =
        clipStartUs + clip.timeMap.timelineAt(sourceTimeUs)
}

/** Easings que um segmento de velocidade aceita (os demais ultrapassam o alvo e sairiam da faixa). */
val SPEED_SEGMENT_EASINGS: Set<Easing> = linkedSetOf(
    Easing.LINEAR, Easing.SMOOTH, Easing.EASE_IN, Easing.EASE_OUT, Easing.EASE_IN_OUT, Easing.BEZIER, Easing.HOLD,
)

/**
 * Um ponto de controle da curva de velocidade, ancorado no tempo da mídia (cortes e splits
 * preservam a rampa).
 *
 * [easing] molda o segmento que sai deste ponto até o próximo ([Easing.LINEAR] é o comportamento
 * do schema 9, então projetos antigos tocam igual). [bezier] vale quando [easing] é
 * [Easing.BEZIER]. O easing do último ponto não tem efeito.
 */
data class SpeedPoint(
    val sourceUs: Long,
    val speed: Float,
    val easing: Easing = Easing.LINEAR,
    val bezier: CubicBezier = CubicBezier(),
) {
    init {
        require(sourceUs >= 0 && speed in MIN_CLIP_SPEED..MAX_CLIP_SPEED)
        require(easing in SPEED_SEGMENT_EASINGS)
    }

    companion object {
        private fun pack(low: Float, high: Int): Long = (high.toLong() shl 32) or (low.toRawBits().toLong() and 0xFFFF_FFFFL)
        private fun pack(low: Float, high: Float): Long = (high.toRawBits().toLong() shl 32) or (low.toRawBits().toLong() and 0xFFFF_FFFFL)

        /** Formato que o motor nativo lê: 4 `long` por ponto. */
        fun pack(points: List<SpeedPoint>): LongArray = LongArray(points.size * 4).also { out ->
            points.forEachIndexed { index, point ->
                out[index * 4] = point.sourceUs
                out[index * 4 + 1] = pack(point.speed, point.easing.ordinal)
                out[index * 4 + 2] = pack(point.bezier.x1, point.bezier.y1)
                out[index * 4 + 3] = pack(point.bezier.x2, point.bezier.y2)
            }
        }

        fun unpack(packed: LongArray): List<SpeedPoint> {
            require(packed.size % 4 == 0)
            fun low(value: Long) = Float.fromBits(value.toInt())
            fun high(value: Long) = Float.fromBits((value ushr 32).toInt())
            return List(packed.size / 4) { index ->
                val base = index * 4
                SpeedPoint(
                    packed[base], low(packed[base + 1]), Easing.entries[(packed[base + 1] ushr 32).toInt()],
                    CubicBezier(low(packed[base + 2]), high(packed[base + 2]), low(packed[base + 3]), high(packed[base + 3])),
                )
            }
        }
    }
}

/**
 * Mapa tempo da timeline ↔ tempo da mídia de um clipe, calculado pelo motor em Rust.
 *
 * O motor corta a curva em degraus numa grade fixa da mídia; cada degrau toca na média
 * harmônica da curva, então sua duração é a integral exata de `ds / v(s)` e a duração total do
 * clipe é exata com qualquer número de mudanças. Prévia e exportação (via
 * [com.termex.replay15.editor.render.ClipSpeedProvider]) usam os mesmos degraus.
 *
 * [table] é memória da JVM: não há objeto nativo para liberar.
 */
class ClipTimeMap(private val clip: VideoClip) {
    data class Segment(val sourceUs: Long, val endUs: Long, val speed: Float, val timelineUs: Long) {
        val durationUs: Long get() = ((endUs - sourceUs) / speed.toDouble()).roundToLong()
    }

    val table: LongArray = com.termex.replay15.editor.speed.NativeSpeed.buildTimeMap(
        clip.sourceUs, clip.inUs, clip.outUs, clip.speed, clip.reverse, SpeedPoint.pack(clip.speedCurve))

    val durationUs: Long = com.termex.replay15.editor.speed.NativeSpeed.duration(table)

    val segments: List<Segment> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val rows = com.termex.replay15.editor.speed.NativeSpeed.segments(table)
        List(rows.size / 4) { i -> Segment(rows[i * 4], rows[i * 4 + 1], Float.fromBits(rows[i * 4 + 3].toInt()), rows[i * 4 + 2]) }
    }

    fun sourceAt(timelineUs: Long): Long = com.termex.replay15.editor.speed.NativeSpeed.sourceAt(table, timelineUs)

    fun timelineAt(sourceUs: Long): Long = com.termex.replay15.editor.speed.NativeSpeed.timelineAt(table, sourceUs)

    /** Alças de trim podem recuperar mídia fora da janela visível. */
    fun extendedSourceAt(timelineUs: Long): Long = com.termex.replay15.editor.speed.NativeSpeed.extendedSourceAt(table, timelineUs)

    fun speedAtInput(relativeSourceUs: Long): Float = com.termex.replay15.editor.speed.NativeSpeed.speedAtInput(table, relativeSourceUs)

    fun nextInputChange(relativeSourceUs: Long): Long? =
        com.termex.replay15.editor.speed.NativeSpeed.nextInputChange(table, relativeSourceUs).takeIf { it >= 0 }

    /** Valor contínuo da curva (não do degrau) numa posição absoluta da mídia. */
    fun curveSpeedAtSource(sourceUs: Long): Float = com.termex.replay15.editor.speed.NativeSpeed.curveSpeedAtSource(table, sourceUs)

    /** Velocidade percebida num instante da timeline do clipe. */
    fun curveSpeedAtTimeline(timelineUs: Long): Float = com.termex.replay15.editor.speed.NativeSpeed.curveSpeedAtTimeline(table, timelineUs)

    /** Menor e maior velocidade instantânea dentro da janela visível. */
    fun speedRange(): ClosedFloatingPointRange<Float> =
        com.termex.replay15.editor.speed.NativeSpeed.speedRange(table).let { it[0]..it[1] }

    /** A curva amostrada pelo motor, para desenhar exatamente o que ele usa. */
    fun sampleCurve(fromSourceUs: Long, toSourceUs: Long, count: Int): FloatArray =
        com.termex.replay15.editor.speed.NativeSpeed.sampleCurve(table, fromSourceUs, toSourceUs, count)
}
