package com.termex.replay15.editor.tracking

import android.graphics.Bitmap
import com.termex.replay15.editor.domain.TrackingPoint
import com.termex.replay15.editor.domain.TrackingTrack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Normalized selection made in the preview. */
data class TrackingRegion(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(centerX in 0f..1f && centerY in 0f..1f)
        require(width in .01f..1f && height in .01f..1f)
    }
}

/** Caller-owned decoded frame. The tracker never retains the bitmap after [track] returns. */
data class TrackingFrame(val timeUs: Long, val bitmap: Bitmap) {
    init { require(timeUs >= 0L && bitmap.width > 0 && bitmap.height > 0) }
}

/**
 * Small local template tracker used for mask/text attachments. It performs bounded luminance
 * matching around the previous position, so it has no network/model dependency and never queues
 * an unbounded number of frames. Results stay normalized and are stored as one internal track.
 */
class TemplateMotionTracker(
    private val templateSize: Int = 24,
    private val searchFraction: Float = .18f,
    private val maxPoints: Int = TrackingTrack.MAX_POINTS,
) {
    init {
        require(templateSize in 8..64)
        require(searchFraction in .02f.. .5f)
        require(maxPoints in 2..TrackingTrack.MAX_POINTS)
    }

    fun track(frames: Iterable<TrackingFrame>, region: TrackingRegion, id: String? = null): TrackingTrack {
        val iterator = frames.iterator()
        require(iterator.hasNext()) { "At least one frame is required" }
        val first = iterator.next()
        val initial = Rect.from(first.bitmap, region)
        val template = sample(first.bitmap, initial)
        val points = ArrayList<TrackingPoint>(min(maxPoints, 256))
        points += TrackingPoint(first.timeUs, initial.cx.toFloat() / first.bitmap.width, initial.cy.toFloat() / first.bitmap.height,
            initial.w.toFloat() / first.bitmap.width, initial.h.toFloat() / first.bitmap.height, 1f)
        var previous = initial
        var previousTime = first.timeUs
        while (iterator.hasNext() && points.size < maxPoints) {
            val frame = iterator.next()
            if (frame.timeUs <= previousTime) continue
            val radiusX = max(2, (frame.bitmap.width * searchFraction).toInt())
            val radiusY = max(2, (frame.bitmap.height * searchFraction).toInt())
            val step = max(1, min(frame.bitmap.width, frame.bitmap.height) / 96)
            var best = previous
            var bestError = Float.POSITIVE_INFINITY
            for (dy in -radiusY..radiusY step step) for (dx in -radiusX..radiusX step step) {
                val candidate = previous.shifted(dx, dy, frame.bitmap)
                val error = difference(frame.bitmap, candidate, template)
                if (error < bestError) { bestError = error; best = candidate }
            }
            val confidence = (1f - bestError).coerceIn(0f, 1f)
            points += TrackingPoint(frame.timeUs, best.cx.toFloat() / frame.bitmap.width, best.cy.toFloat() / frame.bitmap.height,
                best.w.toFloat() / frame.bitmap.width, best.h.toFloat() / frame.bitmap.height, confidence)
            previous = best
            previousTime = frame.timeUs
        }
        return TrackingTrack(id = id ?: com.termex.replay15.editor.domain.newId(), points = points)
    }

    private fun sample(bitmap: Bitmap, rect: Rect): FloatArray = FloatArray(templateSize * templateSize) { index ->
        val x = index % templateSize
        val y = index / templateSize
        luminance(bitmap, rect.x + ((x + .5f) * rect.w / templateSize).toInt(),
            rect.y + ((y + .5f) * rect.h / templateSize).toInt())
    }

    private fun difference(bitmap: Bitmap, rect: Rect, template: FloatArray): Float {
        var total = 0f
        for (y in 0 until templateSize) for (x in 0 until templateSize) {
            val px = rect.x + ((x + .5f) * rect.w / templateSize).toInt()
            val py = rect.y + ((y + .5f) * rect.h / templateSize).toInt()
            total += abs(luminance(bitmap, px, py) - template[y * templateSize + x])
        }
        return total / template.size
    }

    private fun luminance(bitmap: Bitmap, x: Int, y: Int): Float {
        val pixel = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
        return (0.2126f * ((pixel ushr 16) and 0xFF) + 0.7152f * ((pixel ushr 8) and 0xFF) +
            0.0722f * (pixel and 0xFF)) / 255f
    }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val cx: Int get() = x + w / 2
        val cy: Int get() = y + h / 2
        fun shifted(dx: Int, dy: Int, bitmap: Bitmap): Rect = fromCenter(cx + dx, cy + dy, w, h, bitmap)

        companion object {
            fun from(bitmap: Bitmap, region: TrackingRegion): Rect = fromCenter(
                (region.centerX * bitmap.width).toInt(), (region.centerY * bitmap.height).toInt(),
                (region.width * bitmap.width).toInt().coerceAtLeast(2),
                (region.height * bitmap.height).toInt().coerceAtLeast(2), bitmap)

            fun fromCenter(cx: Int, cy: Int, w: Int, h: Int, bitmap: Bitmap): Rect {
                val width = w.coerceAtMost(bitmap.width).coerceAtLeast(2)
                val height = h.coerceAtMost(bitmap.height).coerceAtLeast(2)
                return Rect((cx - width / 2).coerceIn(0, bitmap.width - width),
                    (cy - height / 2).coerceIn(0, bitmap.height - height), width, height)
            }
        }
    }
}
