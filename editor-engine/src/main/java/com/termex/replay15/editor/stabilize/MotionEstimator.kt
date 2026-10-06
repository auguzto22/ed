package com.termex.replay15.editor.stabilize

import android.graphics.Bitmap

/**
 * A small, caller-independent luminance image used for motion estimation.
 *
 * Stabilization must not retain decoded frames: the pipeline decodes at most a bounded number
 * of preview-sized bitmaps at a time and immediately reduces them to one of these.
 */
class LumaFrame(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(width > 1 && height > 1)
        require(pixels.size == width * height)
    }

    companion object {
        /**
         * Box-downsamples a bitmap to at most [size] on its longest side. Aspect is preserved
         * so that a displacement measured here maps back to the full frame by the same ratio.
         */
        fun from(bitmap: Bitmap, size: Int = 64): LumaFrame {
            val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
            val scale = size.toFloat() / longest
            val width = (bitmap.width * scale).toInt().coerceAtLeast(2)
            val height = (bitmap.height * scale).toInt().coerceAtLeast(2)
            val stepX = (bitmap.width / width).coerceAtLeast(1)
            val stepY = (bitmap.height / height).coerceAtLeast(1)
            val pixels = IntArray(width * height)
            var index = 0
            for (y in 0 until height) {
                val sampleY = (y * stepY).coerceAtMost(bitmap.height - 1)
                for (x in 0 until width) {
                    val sampleX = (x * stepX).coerceAtMost(bitmap.width - 1)
                    pixels[index++] = luminance(bitmap.getPixel(sampleX, sampleY))
                }
            }
            return LumaFrame(width, height, pixels)
        }

        /** ITU-R BT.601 luma in 0..255. */
        fun luminance(color: Int): Int {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }
    }
}

/**
 * Global camera displacement between two frames, normalized to 0..1 of the frame.
 *
 * A positive dx means the camera panned right, so the content slid left inside the frame.
 */
data class CameraMotion(
    val timeUs: Long,
    val dx: Float,
    val dy: Float,
    val confidence: Float,
) {
    init {
        require(timeUs >= 0L)
        require(dx.isFinite() && dy.isFinite() && confidence.isFinite())
        require(confidence in 0f..1f)
    }
}

/**
 * Estimates global translation with block matching.
 *
 * Each grid cell is matched independently and the median displacement is taken, so a person
 * walking across the frame cannot drag the estimate: only the majority of the image decides.
 * No model, no network, and the cost is bounded by the downsampled size rather than by the
 * video resolution.
 */
class MotionEstimator(
    private val blockSize: Int = 8,
    private val searchRadius: Int = 6,
    /**
     * Mean per-pixel error above which two frames are treated as a cut rather than as motion.
     * 0.35 of the luma range is generous: a real pan still matches the same blocks exactly.
     */
    private val maxMeanError: Float = .35f,
) {
    init {
        require(blockSize in 4..32)
        require(searchRadius in 1..16)
        require(maxMeanError in 0f..1f)
    }

    fun estimate(previous: LumaFrame, current: LumaFrame, timeUs: Long): CameraMotion? {
        if (previous.width != current.width || previous.height != current.height) return null
        val width = current.width
        val height = current.height
        val radius = searchRadius

        val dxSamples = ArrayList<Int>(256)
        val dySamples = ArrayList<Int>(256)
        var matched = 0
        var total = 0
        var errorSum = 0L

        // Stride by the block size so cells do not overlap; overlapping cells only add cost.
        var y = blockSize
        while (y < height - blockSize) {
            var x = blockSize
            while (x < width - blockSize) {
                total++
                val best = search(x, y, width, height, radius, previous, current)
                if (best != null) {
                    dxSamples.add(best[0])
                    dySamples.add(best[1])
                    errorSum += best[2].toLong()
                    matched++
                }
                x += blockSize
            }
            y += blockSize
        }

        if (total == 0 || matched < 3) return null

        // A scene cut shares no content with the previous frame, so no block can match well.
        // Mean error over the whole frame separates a cut from a real pan, which the bounded
        // search radius alone could never detect.
        val meanError = errorSum.toFloat() / matched / MAX_LUMA_SPREAD
        if (meanError > maxMeanError) return null

        val dx = median(dxSamples) / width.toFloat()
        val dy = median(dySamples) / height.toFloat()

        val confidence = (matched.toFloat() / total).coerceIn(0f, 1f)
        return CameraMotion(timeUs, dx, dy, confidence)
    }

    private fun search(
        x: Int, y: Int, width: Int, height: Int, radius: Int,
        previous: LumaFrame, current: LumaFrame,
    ): IntArray? {
        var bestCost = Int.MAX_VALUE
        var bestX = 0
        var bestY = 0
        for (dy in -radius..radius) {
            val ny = y + dy
            if (ny < 0 || ny >= height - blockSize) continue
            for (dx in -radius..radius) {
                val nx = x + dx
                if (nx < 0 || nx >= width - blockSize) continue
                val cost = cost(x, y, nx, ny, previous, current)
                if (cost < bestCost) {
                    bestCost = cost
                    bestX = dx
                    bestY = dy
                }
            }
        }
        return if (bestCost == Int.MAX_VALUE) null else intArrayOf(bestX, bestY, bestCost)
    }

    /** Sum of absolute differences over the block at the candidate offset. */
    private fun cost(x: Int, y: Int, nx: Int, ny: Int, previous: LumaFrame, current: LumaFrame): Int {
        var sum = 0
        var index = 0
        for (row in 0 until blockSize) {
            val currentBase = (y + row) * current.width + x
            val previousBase = (ny + row) * previous.width + nx
            for (column in 0 until blockSize) {
                val a = current.pixels[currentBase + column]
                val b = previous.pixels[previousBase + column]
                sum += if (a > b) a - b else b - a
                index++
            }
        }
        return sum / index.coerceAtLeast(1)
    }

    private fun median(samples: List<Int>): Int {
        val sorted = samples.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        const val MAX_LUMA_SPREAD = 255
    }
}
