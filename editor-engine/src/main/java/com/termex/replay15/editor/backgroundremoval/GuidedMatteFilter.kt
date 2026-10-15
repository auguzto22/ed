package com.termex.replay15.editor.backgroundremoval

/**
 * Guided filter (He, Sun & Tang) used to snap a segmentation matte onto the real edges of the
 * photo. Inside each small window the matte is modelled as a linear function of the image
 * brightness, so where the image has an edge (hair against a wall) the matte gets the same edge,
 * and where the image is flat the matte is smoothed. This removes the stair-stepping of masks
 * that were computed at a lower resolution and trims halos of background that the model glued
 * to the subject. Pure Kotlin (no Android) so it can be unit-tested; runs once per photo.
 */
object GuidedMatteFilter {
    /**
     * @param guide row-major luminance in 0..1, [width] x [height].
     * @param matte row-major confidence in 0..1, same size. Not modified.
     * @param radius window radius in pixels.
     * @param epsilon regularisation; smaller = edges follow the image more aggressively.
     */
    fun filter(
        guide: FloatArray,
        matte: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        epsilon: Float = 1e-3f,
    ): FloatArray {
        require(width > 0 && height > 0)
        require(guide.size == width * height && matte.size == width * height)
        require(radius >= 1 && epsilon > 0f)
        val n = width * height
        // Memory matters on phones (a 1536 px photo is ~1.3 M pixels): one summed-area table and
        // a handful of float planes are reused instead of allocating fresh buffers per pass.
        val sat = DoubleArray((width + 1) * (height + 1))
        val scratch = FloatArray(n)
        val meanI = boxMean(guide, width, height, radius, sat)
        val meanP = boxMean(matte, width, height, radius, sat)
        for (i in 0 until n) scratch[i] = guide[i] * matte[i]
        val a = boxMean(scratch, width, height, radius, sat) // corr(I, p), becomes a below
        for (i in 0 until n) scratch[i] = guide[i] * guide[i]
        val b = boxMean(scratch, width, height, radius, sat) // corr(I, I), becomes b below
        for (i in 0 until n) {
            val variance = b[i] - meanI[i] * meanI[i]
            val covariance = a[i] - meanI[i] * meanP[i]
            val slope = covariance / (variance + epsilon)
            a[i] = slope
            b[i] = meanP[i] - slope * meanI[i]
        }
        val meanA = boxMean(a, width, height, radius, sat, out = meanI)
        val meanB = boxMean(b, width, height, radius, sat, out = meanP)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    /**
     * Separable Gaussian blur of a matte. Run after the guided filter on photos: the subject
     * model's contour wobbles by a pixel from one pixel to the next, and once the shader turns
     * the matte into a hard cut that wobble shows as a serrated edge ("rebarba"). A blur of about
     * one output pixel straightens the contour without moving it noticeably.
     */
    fun gaussianBlur(values: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        require(width > 0 && height > 0 && values.size == width * height)
        if (sigma <= 0f) return values.copyOf()
        val radius = kotlin.math.ceil(sigma * 3f).toInt().coerceAtLeast(1)
        val kernel = FloatArray(2 * radius + 1) { i -> val d = (i - radius).toFloat(); kotlin.math.exp(-d * d / (2f * sigma * sigma)) }
        val total = kernel.sum(); for (i in kernel.indices) kernel[i] /= total
        val horizontal = FloatArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in -radius..radius) sum += values[row + (x + k).coerceIn(0, width - 1)] * kernel[k + radius]
                horizontal[row + x] = sum
            }
        }
        val out = FloatArray(values.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0f
            for (k in -radius..radius) sum += horizontal[(y + k).coerceIn(0, height - 1) * width + x] * kernel[k + radius]
            out[y * width + x] = sum.coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * Keeps a refined matte within [maxShift] of the model's own confidence. The guided filter
     * and the blur may nudge an edge (snap it to the photo, straighten its wobble), but they must
     * not overrule what the model was sure about. Without this limit, wherever subject and
     * background have the same brightness (white stripes of a shirt on a white backdrop) the
     * filter just averaged the window: narrow gaps of background between an arm and the body
     * were filled in ("a manga gruda no corpo"), thin light details (collar tips, cuffs, hems)
     * dropped below the threshold and vanished, and the contour moved by a few pixels from one
     * stripe to the next, which showed as burrs ("rebarbas").
     */
    fun limitShift(refined: FloatArray, original: FloatArray, maxShift: Float): FloatArray {
        require(refined.size == original.size)
        require(maxShift >= 0f)
        return FloatArray(refined.size) { i ->
            val base = original[i]
            (base + (refined[i] - base).coerceIn(-maxShift, maxShift)).coerceIn(0f, 1f)
        }
    }

    /** Converts ARGB pixels to 0..1 luminance (Rec. 601). */
    fun luminance(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val c = argb[i]
        ((c ushr 16 and 255) * 0.299f + (c ushr 8 and 255) * 0.587f + (c and 255) * 0.114f) / 255f
    }

    /** Mean over a (2r+1)^2 window clamped to the image, via a summed-area table. O(n). */
    fun boxMean(
        values: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        sat: DoubleArray = DoubleArray((width + 1) * (height + 1)),
        out: FloatArray = FloatArray(width * height),
    ): FloatArray {
        require(sat.size >= (width + 1) * (height + 1) && out.size >= width * height)
        require(out !== values)
        val stride = width + 1
        // Row 0 and column 0 of the table must be zero; they are never written below.
        java.util.Arrays.fill(sat, 0, stride, 0.0)
        for (y in 1..height) sat[y * stride] = 0.0
        for (y in 0 until height) {
            var row = 0.0
            for (x in 0 until width) {
                row += values[y * width + x]
                sat[(y + 1) * stride + x + 1] = sat[y * stride + x + 1] + row
            }
        }
        for (y in 0 until height) {
            val y0 = maxOf(0, y - radius); val y1 = minOf(height - 1, y + radius)
            for (x in 0 until width) {
                val x0 = maxOf(0, x - radius); val x1 = minOf(width - 1, x + radius)
                val sum = sat[(y1 + 1) * stride + x1 + 1] - sat[y0 * stride + x1 + 1] -
                    sat[(y1 + 1) * stride + x0] + sat[y0 * stride + x0]
                out[y * width + x] = (sum / ((x1 - x0 + 1) * (y1 - y0 + 1))).toFloat()
            }
        }
        return out
    }
}
