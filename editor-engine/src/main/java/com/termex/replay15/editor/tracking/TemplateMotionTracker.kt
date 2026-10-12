package com.termex.replay15.editor.tracking

import android.graphics.Bitmap
import com.termex.replay15.editor.domain.TrackingPoint
import com.termex.replay15.editor.domain.TrackingTrack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * Local template tracker used for mask/text attachments (no network or model dependency).
 *
 * The first version compared a fixed template from the first frame with raw luminance and
 * re-read every pixel through Bitmap.getPixel: it lost the target as soon as lighting, scale or
 * appearance changed and was far too slow to search a useful radius. This version:
 *  - reads each frame once into a luminance plane;
 *  - matches zero-mean patches (robust to exposure changes);
 *  - predicts the position with the previous velocity and searches coarse-to-fine around it;
 *  - tests small scale changes so the box follows an object moving towards/away from camera;
 *  - adapts the template slowly while confidence is high, anchored to the original appearance
 *    to avoid drifting onto the background;
 *  - holds the last good position (with low confidence) when the target is occluded.
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
        val session = Session(iterator.next(), region)
        while (iterator.hasNext() && session.size < maxPoints) session.next(iterator.next())
        return session.result(id)
    }

    /** Incremental API: frames can be pushed as they are decoded, without buffering them. */
    inner class Session(first: TrackingFrame, region: TrackingRegion) {
        private val points = ArrayList<TrackingPoint>(256)
        private val original: FloatArray
        private val template: FloatArray
        private var cx: Float
        private var cy: Float
        private var w: Float
        private var h: Float
        private var vx = 0f
        private var vy = 0f
        private var previousTime = first.timeUs
        private var frameW = first.bitmap.width
        private var frameH = first.bitmap.height
        private var lost = 0
        val size: Int get() = points.size

        init {
            val plane = Plane.of(first.bitmap)
            w = (region.width * plane.width).coerceIn(4f, plane.width.toFloat())
            h = (region.height * plane.height).coerceIn(4f, plane.height.toFloat())
            cx = region.centerX * plane.width
            cy = region.centerY * plane.height
            original = sample(plane, cx, cy, w, h)
            template = original.copyOf()
            add(first.timeUs, 1f)
        }

        fun next(frame: TrackingFrame): TrackingPoint? {
            if (points.size >= maxPoints || frame.timeUs <= previousTime) return null
            val plane = Plane.of(frame.bitmap)
            if (plane.width != frameW || plane.height != frameH) {
                // Keep the normalized position if the decoder changed the frame size.
                cx = cx / frameW * plane.width; cy = cy / frameH * plane.height
                w = w / frameW * plane.width; h = h / frameH * plane.height
                frameW = plane.width; frameH = plane.height
            }
            val predictedX = cx + vx
            val predictedY = cy + vy
            val radiusX = max(3f, plane.width * searchFraction)
            val radiusY = max(3f, plane.height * searchFraction)
            var bestX = predictedX; var bestY = predictedY; var bestScale = 1f
            var bestError = Float.POSITIVE_INFINITY
            fun test(x: Float, y: Float, scale: Float) {
                val error = min(
                    difference(plane, x, y, w * scale, h * scale, template),
                    // The original appearance is a second, slightly penalised reference: it pulls
                    // the box back after a partial occlusion instead of following the occluder.
                    difference(plane, x, y, w * scale, h * scale, original) * 1.08f,
                )
                if (error < bestError) { bestError = error; bestX = x; bestY = y; bestScale = scale }
            }
            // Coarse grid around the prediction, then two refinement rounds.
            var step = max(2f, min(radiusX, radiusY) / 6f)
            var y = predictedY - radiusY
            while (y <= predictedY + radiusY) {
                var x = predictedX - radiusX
                while (x <= predictedX + radiusX) { test(x, y, 1f); x += step }
                y += step
            }
            // Also consider "no motion", which the velocity prediction can overshoot.
            test(cx, cy, 1f)
            repeat(3) {
                step = max(.5f, step / 2.5f)
                val baseX = bestX; val baseY = bestY; val baseScale = bestScale
                for (dy in -2..2) for (dx in -2..2) test(baseX + dx * step, baseY + dy * step, baseScale)
                for (scale in floatArrayOf(.94f, .97f, 1.03f, 1.06f)) test(baseX, baseY, (baseScale * scale).coerceIn(.3f, 3f))
            }
            val confidence = (1f - bestError * 2.2f).coerceIn(0f, 1f)
            val dt = (frame.timeUs - previousTime).coerceAtLeast(1L)
            if (confidence >= MIN_CONFIDENCE) {
                val newW = (w * bestScale).coerceIn(4f, plane.width.toFloat())
                val newH = (h * bestScale).coerceIn(4f, plane.height.toFloat())
                // Smoothed velocity in pixels per frame interval.
                vx = vx * .5f + (bestX - cx) * .5f
                vy = vy * .5f + (bestY - cy) * .5f
                cx = bestX.coerceIn(newW / 2f, plane.width - newW / 2f)
                cy = bestY.coerceIn(newH / 2f, plane.height - newH / 2f)
                w = newW; h = newH
                lost = 0
                if (confidence >= ADAPT_CONFIDENCE) {
                    val patch = sample(plane, cx, cy, w, h)
                    for (i in template.indices) template[i] = template[i] * (1f - ADAPT_RATE) + patch[i] * ADAPT_RATE
                }
            } else {
                // Occluded / lost: hold position and decay the velocity.
                lost++
                vx *= .5f; vy *= .5f
            }
            previousTime = frame.timeUs
            return add(frame.timeUs, if (lost > 0) confidence.coerceAtMost(MIN_CONFIDENCE) else confidence, dt)
        }

        fun result(id: String? = null): TrackingTrack =
            TrackingTrack(id = id ?: com.termex.replay15.editor.domain.newId(), points = points.toList())

        private fun add(timeUs: Long, confidence: Float, @Suppress("UNUSED_PARAMETER") dt: Long = 0L): TrackingPoint {
            val point = TrackingPoint(timeUs,
                (cx / frameW).coerceIn(0f, 1f), (cy / frameH).coerceIn(0f, 1f),
                (w / frameW).coerceIn(.01f, 1f), (h / frameH).coerceIn(.01f, 1f), confidence)
            points += point
            return point
        }
    }

    /** Zero-mean template sample: values are luminance minus the patch mean, divided by its spread. */
    private fun sample(plane: Plane, cx: Float, cy: Float, w: Float, h: Float): FloatArray {
        val values = FloatArray(templateSize * templateSize)
        var sum = 0f
        for (y in 0 until templateSize) for (x in 0 until templateSize) {
            val v = plane.at(cx - w / 2f + (x + .5f) * w / templateSize, cy - h / 2f + (y + .5f) * h / templateSize)
            values[y * templateSize + x] = v; sum += v
        }
        normalize(values, sum)
        return values
    }

    private fun normalize(values: FloatArray, sum: Float) {
        val mean = sum / values.size
        var spread = 0f
        for (i in values.indices) { values[i] -= mean; spread += abs(values[i]) }
        val scale = 1f / max(spread / values.size, .02f)
        for (i in values.indices) values[i] *= scale
    }

    private val scratch = ThreadLocal.withInitial { FloatArray(64 * 64) }

    private fun difference(plane: Plane, cx: Float, cy: Float, w: Float, h: Float, template: FloatArray): Float {
        val patch = scratch.get()
        var sum = 0f
        val n = templateSize * templateSize
        for (y in 0 until templateSize) for (x in 0 until templateSize) {
            val v = plane.at(cx - w / 2f + (x + .5f) * w / templateSize, cy - h / 2f + (y + .5f) * h / templateSize)
            patch[y * templateSize + x] = v; sum += v
        }
        val mean = sum / n
        var spread = 0f
        for (i in 0 until n) spread += abs(patch[i] - mean)
        val scale = 1f / max(spread / n, .02f)
        var total = 0f
        for (i in 0 until n) total += abs((patch[i] - mean) * scale - template[i])
        // Normalized zero-mean patches differ by ~0..2 per sample; map to ~0..1.
        return total / n / 2f
    }

    /** Luminance plane in [0,1], read once per frame. Sampling outside clamps to the border. */
    private class Plane(val width: Int, val height: Int, val luma: FloatArray) {
        fun at(x: Float, y: Float): Float {
            val ix = x.roundToInt().coerceIn(0, width - 1)
            val iy = y.roundToInt().coerceIn(0, height - 1)
            return luma[iy * width + ix]
        }

        companion object {
            fun of(bitmap: Bitmap): Plane {
                val w = bitmap.width; val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                val luma = FloatArray(pixels.size) { i ->
                    val p = pixels[i]
                    (0.2126f * ((p ushr 16) and 0xFF) + 0.7152f * ((p ushr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
                }
                return Plane(w, h, luma)
            }
        }
    }

    private companion object {
        const val MIN_CONFIDENCE = .35f
        const val ADAPT_CONFIDENCE = .6f
        const val ADAPT_RATE = .12f
    }
}
