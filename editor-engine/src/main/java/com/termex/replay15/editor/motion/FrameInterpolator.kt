package com.termex.replay15.editor.motion

import com.termex.replay15.editor.stabilize.LumaFrame
import com.termex.replay15.editor.stabilize.MotionEstimator
import kotlin.math.roundToInt

/**
 * Builds the frames that a slowed-down clip is missing.
 *
 * A 30fps clip played at 0.3x shows the same 30 frames stretched over 3.3 seconds, so the
 * result judders. This produces the intermediate frames instead. It is deliberately a
 * pre-pass over a small luminance reduction of the media: the renderer, the decoder and the
 * project are never touched, and the output is a derived file the preview hot-swaps in.
 *
 * Plain crossfading does not fix judder, it only hides it, because a crossfade shows both
 * moments at once and the moving content ends up doubled. The sampling here is
 * motion-compensated: both source frames are warped by the global displacement the Phase 9
 * [MotionEstimator] already measures, so each sample describes the same point in the scene.
 */
object FrameInterpolator {

    /**
     * Renders the frame at [t] between [previous] and [next].
     *
     * [velocityX]/[velocityY] are the content displacement from the previous frame to the next
     * one, in pixels. A pixel of the output samples the previous frame where that world point
     * started and the next frame where it will be, so the two agree and the blend is sharp.
     * At `t = 0` and `t = 1` the result is the respective source frame, unmodified.
     */
    fun interpolate(
        previous: LumaFrame,
        next: LumaFrame,
        velocityX: Float,
        velocityY: Float,
        t: Float,
    ): LumaFrame {
        require(previous.width == next.width && previous.height == next.height)
        require(t in 0f..1f)
        require(velocityX.isFinite() && velocityY.isFinite())

        val width = previous.width
        val height = previous.height
        val pixels = IntArray(width * height)

        // The world point behind output pixel (x, y) sat at (x - v*t) in the previous frame
        // and at (x + v*(1 - t)) in the next one.
        val backShiftX = velocityX * t
        val backShiftY = velocityY * t
        val forwardShiftX = velocityX * (1f - t)
        val forwardShiftY = velocityY * (1f - t)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = sample(previous, x - backShiftX, y - backShiftY)
                val b = sample(next, x + forwardShiftX, y + forwardShiftY)
                pixels[y * width + x] = a + ((b - a) * t).roundToInt()
            }
        }
        return LumaFrame(width, height, pixels)
    }

    /**
     * Mean absolute error against a reference, used by the tests to prove the compensation
     * actually helps. Exposed because it is the only honest way to score a blurred frame.
     */
    fun meanError(frame: LumaFrame, reference: LumaFrame): Float {
        require(frame.width == reference.width && frame.height == reference.height)
        var sum = 0L
        for (i in frame.pixels.indices) {
            val a = frame.pixels[i]
            val b = reference.pixels[i]
            sum += if (a > b) (a - b).toLong() else (b - a).toLong()
        }
        return sum.toFloat() / frame.pixels.size
    }

    /**
     * Renders the frame at [t] between two ARGB frames, using the same motion-compensated
     * sampling as [interpolate].
     *
     * The motion itself is measured on luminance, which is cheap and is what the eye is most
     * sensitive to; the warp is then applied to the full colour frames.
     */
    fun interpolateArgb(
        previous: IntArray,
        next: IntArray,
        width: Int,
        height: Int,
        velocityX: Float,
        velocityY: Float,
        t: Float,
    ): IntArray {
        require(previous.size == width * height && next.size == width * height)
        require(width > 1 && height > 1)
        require(t in 0f..1f)
        require(velocityX.isFinite() && velocityY.isFinite())

        val output = IntArray(width * height)
        val backShiftX = velocityX * t
        val backShiftY = velocityY * t
        val forwardShiftX = velocityX * (1f - t)
        val forwardShiftY = velocityY * (1f - t)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = sampleArgb(previous, width, height, x - backShiftX, y - backShiftY)
                val b = sampleArgb(next, width, height, x + forwardShiftX, y + forwardShiftY)
                output[y * width + x] = mixArgb(a, b, t)
            }
        }
        return output
    }

    /** Bilinear sample of one ARGB frame, with the borders clamped. */
    private fun sampleArgb(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        val maxX = width - 1
        val maxY = height - 1
        val fx = x.coerceIn(0f, maxX.toFloat())
        val fy = y.coerceIn(0f, maxY.toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = (x0 + 1).coerceAtMost(maxX)
        val y1 = (y0 + 1).coerceAtMost(maxY)
        val ax = fx - x0
        val ay = fy - y0

        val top = mixArgb(pixels[y0 * width + x0], pixels[y0 * width + x1], ax)
        val bottom = mixArgb(pixels[y1 * width + x0], pixels[y1 * width + x1], ax)
        return mixArgb(top, bottom, ay)
    }

    private fun mixArgb(a: Int, b: Int, weight: Float): Int {
        if (weight <= 0f) return a
        if (weight >= 1f) return b
        val inverse = 1f - weight
        val alpha = (a ushr 24 and 0xFF) * weight + (b ushr 24 and 0xFF) * inverse
        val red = (a shr 16 and 0xFF) * weight + (b shr 16 and 0xFF) * inverse
        val green = (a shr 8 and 0xFF) * weight + (b shr 8 and 0xFF) * inverse
        val blue = (a and 0xFF) * weight + (b and 0xFF) * inverse
        return (alpha.toInt() shl 24) or (red.toInt() shl 16) or (green.toInt() shl 8) or blue.toInt()
    }

    /** Extracts the luminance plane an [android.graphics.Bitmap]'s pixels can be reduced to. */
    fun luminanceOf(pixels: IntArray, width: Int, height: Int, size: Int = 64): LumaFrame {
        val longest = maxOf(width, height).coerceAtLeast(1)
        val scale = size.toFloat() / longest
        val outWidth = (width * scale).toInt().coerceAtLeast(2)
        val outHeight = (height * scale).toInt().coerceAtLeast(2)
        val stepX = (width / outWidth).coerceAtLeast(1)
        val stepY = (height / outHeight).coerceAtLeast(1)
        val values = IntArray(outWidth * outHeight)
        var index = 0
        for (y in 0 until outHeight) {
            val sampleY = (y * stepY).coerceAtMost(height - 1)
            for (x in 0 until outWidth) {
                val sampleX = (x * stepX).coerceAtMost(width - 1)
                values[index++] = LumaFrame.luminance(pixels[sampleY * width + sampleX])
            }
        }
        return LumaFrame(outWidth, outHeight, values)
    }

    /**
     * Measures how far the content moved between two frames, in pixels.
     *
     * [MotionEstimator] reports the camera displacement, meaning where a block of the previous
     * frame is found in the current one. Compensation needs the opposite: where the content
     * went. Negating here keeps that sign flip in one place instead of at every call site,
     * where forgetting it would silently smear the motion in the wrong direction.
     */
    fun measureVelocity(previous: LumaFrame, next: LumaFrame): Pair<Float, Float>? {
        val motion = MotionEstimator().estimate(previous, next, 0L) ?: return null
        return -motion.dx * previous.width to -motion.dy * previous.height
    }

    /** Bilinear sample with the borders clamped, so the result never samples outside the image. */
    private fun sample(frame: LumaFrame, x: Float, y: Float): Int {
        val maxX = frame.width - 1
        val maxY = frame.height - 1
        val fx = x.coerceIn(0f, maxX.toFloat())
        val fy = y.coerceIn(0f, maxY.toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = (x0 + 1).coerceAtMost(maxX)
        val y1 = (y0 + 1).coerceAtMost(maxY)
        val ax = fx - x0
        val ay = fy - y0

        val top = lerp(frame.pixels[y0 * frame.width + x0], frame.pixels[y0 * frame.width + x1], ax)
        val bottom = lerp(frame.pixels[y1 * frame.width + x0], frame.pixels[y1 * frame.width + x1], ax)
        return lerp(top, bottom, ay)
    }

    private fun lerp(a: Int, b: Int, weight: Float): Int =
        a + ((b - a) * weight).roundToInt()
}
