package com.termex.replay15.editor.tracking

import com.termex.replay15.editor.domain.CanvasFill
import com.termex.replay15.editor.domain.CropRect
import com.termex.replay15.editor.domain.TrackingPoint
import com.termex.replay15.editor.domain.TrackingTrack
import kotlin.math.cos
import kotlin.math.sin

/**
 * Coordinate conversions for motion tracking.
 *
 * The target is chosen on the preview, i.e. in project-canvas space, but frames are decoded in
 * source space and the resulting track drives the clip mask, which is evaluated on the clip
 * texture. Tracking used the canvas selection directly as a source position, so whenever the clip
 * was fitted, filled, zoomed, moved or rotated the tracker followed some other part of the image.
 * The mask also samples the GL texture, whose v axis grows upwards, while decoded frames grow
 * downwards, so the attached mask moved vertically opposite to the tracked object.
 *
 * The forward model mirrors GpuPreviewRenderer.drawLayer exactly.
 */
data class LayerGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val crop: CropRect,
    val fixedRotation: Int,
    val flip: Boolean,
    val zoom: Float,
    val x: Float,
    val y: Float,
    val rotation: Float,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val fill: CanvasFill,
) {
    private val m00: Float; private val m01: Float; private val m10: Float; private val m11: Float
    private val tx: Float; private val ty: Float

    init {
        val cropW = (crop.right - crop.left).coerceAtLeast(.05f)
        val cropH = (crop.bottom - crop.top).coerceAtLeast(.05f)
        val rotated = (fixedRotation / 90) % 2 != 0
        val sw = sourceWidth.coerceAtLeast(1).toFloat(); val sh = sourceHeight.coerceAtLeast(1).toFloat()
        val sourceAspect = if (rotated) sh * cropH / (sw * cropW) else sw * cropW / (sh * cropH)
        val outputAspect = canvasWidth.coerceAtLeast(1).toFloat() / canvasHeight.coerceAtLeast(1)
        var sx = 1f; var sy = 1f
        if (fill == CanvasFill.FIT) {
            if (sourceAspect > outputAspect) sy = outputAspect / sourceAspect else sx = sourceAspect / outputAspect
        } else {
            if (sourceAspect > outputAspect) sx = sourceAspect / outputAspect else sy = outputAspect / sourceAspect
        }
        val radians = Math.toRadians((rotation + fixedRotation).toDouble())
        val c = cos(radians).toFloat(); val s = sin(radians).toFloat()
        val xScale = sx * zoom * if (flip) -1f else 1f
        val yScale = sy * zoom
        // NDC = M * quad + t, with M = R(theta) * diag(xScale, yScale) (GL, y up).
        m00 = xScale * c; m01 = -yScale * s; m10 = xScale * s; m11 = yScale * c
        tx = x * 2f; ty = -y * 2f
    }

    /** Canvas point (0..1, y down) -> source frame point (0..1, y down); may fall outside 0..1. */
    fun canvasToSource(canvasX: Float, canvasY: Float): Pair<Float, Float> {
        val nx = canvasX * 2f - 1f - tx
        val ny = 1f - canvasY * 2f - ty
        val det = m00 * m11 - m01 * m10
        if (kotlin.math.abs(det) < 1e-6f) return canvasX to canvasY
        val qx = (m11 * nx - m01 * ny) / det
        val qy = (-m10 * nx + m00 * ny) / det
        // Quad uv is y-up; decoded frames are y-down.
        return (qx + 1f) / 2f to 1f - (qy + 1f) / 2f
    }

    /** Bounding box of the selection mapped into the source frame, clamped to valid values. */
    fun regionToSource(region: TrackingRegion): TrackingRegion {
        val corners = listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).map { (dx, dy) ->
            canvasToSource(region.centerX + dx * region.width / 2f, region.centerY + dy * region.height / 2f)
        }
        val center = canvasToSource(region.centerX, region.centerY)
        val minX = corners.minOf { it.first }; val maxX = corners.maxOf { it.first }
        val minY = corners.minOf { it.second }; val maxY = corners.maxOf { it.second }
        return TrackingRegion(
            centerX = center.first.coerceIn(0f, 1f),
            centerY = center.second.coerceIn(0f, 1f),
            width = (maxX - minX).coerceIn(.01f, 1f),
            height = (maxY - minY).coerceIn(.01f, 1f),
        )
    }

    companion object {
        /** Frame space (y down) -> clip mask space (texture v, y up). */
        fun toMaskSpace(track: TrackingTrack): TrackingTrack = track.copy(points = track.points.map(::toMaskSpace))

        fun toMaskSpace(point: TrackingPoint): TrackingPoint =
            point.copy(centerY = (1f - point.centerY).coerceIn(0f, 1f))
    }
}
