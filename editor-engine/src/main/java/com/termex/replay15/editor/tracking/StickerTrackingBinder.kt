package com.termex.replay15.editor.tracking

import com.termex.replay15.editor.domain.Easing
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.TrackingTrack
import com.termex.replay15.editor.transform.TransformKeyframe

/**
 * Faz um sticker seguir um objeto rastreado no vídeo (Rastreamento do CapCut).
 *
 * O rastreio sai no espaço do quadro de origem; o sticker vive no espaço do canvas. Cada ponto é
 * levado ao canvas pela geometria do clipe naquele instante e vira um keyframe de transform do
 * sticker. O sticker anda *relativo* a onde a pessoa o deixou: o primeiro ponto é a âncora, então
 * um sticker colocado ao lado do objeto continua ao lado dele.
 */
object StickerTrackingBinder {
    /** O transform de sticker aceita até 200 keyframes. */
    const val MAX_KEYS = 200

    /**
     * @param timelineAt converte o tempo do ponto (arquivo de origem, µs) para o tempo da timeline;
     *   devolve null quando aquele trecho do arquivo não aparece no projeto.
     * @param geometryAt geometria do clipe no tempo de origem (zoom/posição podem ter keyframes).
     * @param followScale o sticker cresce/diminui junto com o objeto.
     */
    fun bind(
        sticker: StickerClip,
        track: TrackingTrack,
        timelineAt: (Long) -> Long?,
        geometryAt: (Long) -> LayerGeometry,
        followScale: Boolean = true,
    ): StickerClip? {
        data class Sample(val timelineUs: Long, val x: Float, val y: Float, val size: Float)
        val samples = track.points.mapNotNull { point ->
            val timelineUs = timelineAt(point.timeUs) ?: return@mapNotNull null
            if (timelineUs < sticker.startUs || timelineUs > sticker.endUs) return@mapNotNull null
            val geometry = geometryAt(point.timeUs)
            val (cx, cy) = geometry.sourceToCanvas(point.centerX, point.centerY)
            // Tamanho do alvo no canvas: diagonal da caixa levada ao canvas (invariante a rotação).
            val (ax, ay) = geometry.sourceToCanvas(point.centerX - point.width / 2f, point.centerY - point.height / 2f)
            val (bx, by) = geometry.sourceToCanvas(point.centerX + point.width / 2f, point.centerY + point.height / 2f)
            val size = kotlin.math.hypot(bx - ax, by - ay)
            if (!cx.isFinite() || !cy.isFinite() || !size.isFinite()) null else Sample(timelineUs, cx, cy, size)
        }.distinctBy { it.timelineUs }.sortedBy { it.timelineUs }
        if (samples.size < 2) return null

        // Até MAX_KEYS amostras igualmente espaçadas, sempre com a primeira e a última.
        val picked = if (samples.size <= MAX_KEYS) samples else List(MAX_KEYS) { i ->
            samples[(i.toLong() * (samples.size - 1) / (MAX_KEYS - 1)).toInt()]
        }.distinctBy { it.timelineUs }

        val anchor = picked.first()
        val base = sticker.transformAt(anchor.timelineUs)
        val keys = picked.map { sample ->
            val ratio = if (followScale && anchor.size > 1e-4f) (sample.size / anchor.size).coerceIn(.2f, 5f) else 1f
            val transform = base.copy(
                x = base.x + (sample.x - anchor.x),
                y = base.y + (sample.y - anchor.y),
                scaleX = base.scaleX * ratio,
                scaleY = base.scaleY * ratio,
            ).bounded()
            TransformKeyframe(sample.timelineUs, transform, Easing.LINEAR)
        }
        return sticker.copy(transformKeyframes = keys)
    }
}
