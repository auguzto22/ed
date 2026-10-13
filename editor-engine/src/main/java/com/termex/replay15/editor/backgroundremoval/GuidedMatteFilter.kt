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
        val ip = FloatArray(n) { guide[it] * matte[it] }
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val meanI = boxMean(guide, width, height, radius)
        val meanP = boxMean(matte, width, height, radius)
        val corrIp = boxMean(ip, width, height, radius)
        val corrII = boxMean(ii, width, height, radius)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val variance = corrII[i] - meanI[i] * meanI[i]
            val covariance = corrIp[i] - meanI[i] * meanP[i]
            a[i] = covariance / (variance + epsilon)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxMean(a, width, height, radius)
        val meanB = boxMean(b, width, height, radius)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    /** Converts ARGB pixels to 0..1 luminance (Rec. 601). */
    fun luminance(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val c = argb[i]
        ((c ushr 16 and 255) * 0.299f + (c ushr 8 and 255) * 0.587f + (c and 255) * 0.114f) / 255f
    }

    /** Mean over a (2r+1)^2 window clamped to the image, via a summed-area table. O(n). */
    fun boxMean(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val stride = width + 1
        val sat = DoubleArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0.0
            for (x in 0 until width) {
                row += values[y * width + x]
                sat[(y + 1) * stride + x + 1] = sat[y * stride + x + 1] + row
            }
        }
        val out = FloatArray(width * height)
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
