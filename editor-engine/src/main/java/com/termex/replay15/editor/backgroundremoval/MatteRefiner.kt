package com.termex.replay15.editor.backgroundremoval

import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * CPU counterpart of [BackgroundMaskShader] for still bitmaps (stickers, photo overlays).
 *
 * The bitmap appliers looked the mask up with nearest-neighbour indexing, so a 256 px model mask
 * stretched over a large sticker produced stair-stepped, soft-cornered cut-outs. Here the mask is
 * sampled bilinearly and refined with the same joint bilateral filter as the GPU path, so the
 * cut follows the colour edges of the image.
 */
object MatteRefiner {
    private const val RADIUS = 2

    /**
     * Writes the matte into the alpha channel of [pixels] (ARGB, row-major [width] x [height]).
     * [mask] is a row-major confidence map of [maskWidth] x [maskHeight] covering the same image.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        mask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        effect: BackgroundRemovalEffect,
    ) {
        require(pixels.size == width * height && mask.size == maskWidth * maskHeight)
        val source = pixels.copyOf()
        val sigma = 0.06f + (0.20f - 0.06f) * effect.edgeSmoothing.coerceIn(0f, 1f)
        val inv = 1f / (2f * sigma * sigma)
        // One mask texel expressed in image pixels.
        val stepX = width.toFloat() / maskWidth
        val stepY = height.toFloat() / maskHeight
        val spatial = FloatArray((2 * RADIUS + 1) * (2 * RADIUS + 1)) { i ->
            val dx = i % (2 * RADIUS + 1) - RADIUS; val dy = i / (2 * RADIUS + 1) - RADIUS
            exp(-(dx * dx + dy * dy) * 0.22f)
        }
        val feather = effect.feather.coerceAtLeast(.001f)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val reference = source[y * width + x]
                val rr = (reference ushr 16 and 255) / 255f
                val rg = (reference ushr 8 and 255) / 255f
                val rb = (reference and 255) / 255f
                var sum = 0f; var weights = 0f
                for (dy in -RADIUS..RADIUS) for (dx in -RADIUS..RADIUS) {
                    val px = x + dx * stepX; val py = y + dy * stepY
                    val ix = px.roundToInt().coerceIn(0, width - 1)
                    val iy = py.roundToInt().coerceIn(0, height - 1)
                    val c = source[iy * width + ix]
                    val dr = (c ushr 16 and 255) / 255f - rr
                    val dg = (c ushr 8 and 255) / 255f - rg
                    val db = (c and 255) / 255f - rb
                    val w = spatial[(dy + RADIUS) * (2 * RADIUS + 1) + dx + RADIUS] * exp(-(dr * dr + dg * dg + db * db) * inv)
                    sum += sampleBilinear(mask, maskWidth, maskHeight, (px + .5f) / width, (py + .5f) / height) * w
                    weights += w
                }
                val confidence = if (weights > 0f) sum / weights else 0f
                val matte = smoothstep(effect.threshold - feather, effect.threshold + feather, confidence)
                val alpha = (matte * (reference ushr 24)).roundToInt().coerceIn(0, 255)
                pixels[y * width + x] = (reference and 0x00FFFFFF) or (alpha shl 24)
            }
        }
    }

    /** Bilinear lookup with normalized coordinates (texel centres at (i + .5) / size). */
    fun sampleBilinear(mask: FloatArray, w: Int, h: Int, u: Float, v: Float): Float {
        val x = (u * w - .5f).coerceIn(0f, (w - 1).toFloat())
        val y = (v * h - .5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = x - x0; val fy = y - y0
        val top = mask[y0 * w + x0] * (1f - fx) + mask[y0 * w + x1] * fx
        val bottom = mask[y1 * w + x0] * (1f - fx) + mask[y1 * w + x1] * fx
        return (top * (1f - fy) + bottom * fy).coerceIn(0f, 1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(.001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
