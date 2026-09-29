package com.termex.replay15.editor.render

import android.graphics.Bitmap
import com.termex.replay15.editor.domain.VideoFilter
import kotlin.math.*

/**
 * Pure color math engine implementing the exact same formula as `studio_fragment.glsl`.
 * Guarantees 100% visual parity between preview thumbnails, playback pipeline, and export.
 */
object FilterColorMath {
    private val INV_SQRT3 = 1f / sqrt(3f)
    private const val ONE_THIRD = 1f / 3f

    data class FilterParameters(
        val brightness: Float,
        val contrast: Float,
        val saturation: Float,
        val lightness: Float,
        val hueRadians: Float,
        val red: Float,
        val green: Float,
        val blue: Float,
        val mode: Float,
    )

    fun parametersFor(preset: VideoFilter): FilterParameters {
        val saturation = if (preset == VideoFilter.MONO) -1f else preset.saturation / 100f
        return FilterParameters(
            brightness = preset.brightness,
            contrast = preset.contrast,
            saturation = saturation,
            lightness = preset.lightness / 100f,
            hueRadians = Math.toRadians(preset.hue.toDouble()).toFloat(),
            red = preset.red,
            green = preset.green,
            blue = preset.blue,
            mode = when (preset) {
                VideoFilter.NEGATIVE -> 2f
                VideoFilter.MONO -> 1f
                else -> 0f
            },
        )
    }

    /**
     * Applies filter to a single RGB color in normalized [0..1] range.
     * Matches `studio_fragment.glsl::applyFilter(vec3 source)`.
     */
    fun applyFilter(
        sourceR: Float,
        sourceG: Float,
        sourceB: Float,
        preset: VideoFilter,
        strength: Float = 1f,
    ): FloatArray {
        if (preset == VideoFilter.ORIGINAL || strength <= 0f) {
            return floatArrayOf(sourceR, sourceG, sourceB)
        }
        val p = parametersFor(preset)
        var fr: Float
        var fg: Float
        var fb: Float

        if (p.mode > 1.5f) {
            // VideoFilter.NEGATIVE
            fr = 1f - sourceR
            fg = 1f - sourceG
            fb = 1f - sourceB
        } else {
            // Brightness
            fr = sourceR + p.brightness
            fg = sourceG + p.brightness
            fb = sourceB + p.brightness

            // Contrast
            val contrastFactor = 1f + p.contrast
            fr = (fr - 0.5f) * contrastFactor + 0.5f
            fg = (fg - 0.5f) * contrastFactor + 0.5f
            fb = (fb - 0.5f) * contrastFactor + 0.5f

            // Luminance & Saturation (BT.709)
            val gray = fr * 0.2126f + fg * 0.7152f + fb * 0.0722f
            val satFactor = max(0f, 1f + p.saturation)
            fr = gray + (fr - gray) * satFactor
            fg = gray + (fg - gray) * satFactor
            fb = gray + (fb - gray) * satFactor

            // Rodrigues hue rotation around normalized vector (1, 1, 1)
            val c = cos(p.hueRadians)
            val s = sin(p.hueRadians)
            val crossX = INV_SQRT3 * (fg - fb)
            val crossY = INV_SQRT3 * (fb - fr)
            val crossZ = INV_SQRT3 * (fr - fg)
            val axisDotTerm = (fr + fg + fb) * ONE_THIRD * (1f - c)

            fr = fr * c + crossX * s + axisDotTerm
            fg = fg * c + crossY * s + axisDotTerm
            fb = fb * c + crossZ * s + axisDotTerm

            // Channel gain + Lightness
            fr = fr * p.red + p.lightness
            fg = fg * p.green + p.lightness
            fb = fb * p.blue + p.lightness
        }

        // Clamp [0..1]
        val clampedR = fr.coerceIn(0f, 1f)
        val clampedG = fg.coerceIn(0f, 1f)
        val clampedB = fb.coerceIn(0f, 1f)

        // Mix with source
        val outR = sourceR + (clampedR - sourceR) * strength
        val outG = sourceG + (clampedG - sourceG) * strength
        val outB = sourceB + (clampedB - sourceB) * strength

        return floatArrayOf(outR.coerceIn(0f, 1f), outG.coerceIn(0f, 1f), outB.coerceIn(0f, 1f))
    }

    /**
     * Applies the filter in-place to an array of ARGB_8888 packed pixel ints.
     */
    fun applyFilterToPixels(
        pixels: IntArray,
        preset: VideoFilter,
        strength: Float = 1f,
    ) {
        if (preset == VideoFilter.ORIGINAL || strength <= 0f) return
        val p = parametersFor(preset)
        val isNegative = p.mode > 1.5f
        val contrastFactor = 1f + p.contrast
        val satFactor = max(0f, 1f + p.saturation)
        val c = cos(p.hueRadians)
        val s = sin(p.hueRadians)
        val inv255 = 1f / 255f

        for (i in pixels.indices) {
            val color = pixels[i]
            val a = color ushr 24
            var r = ((color ushr 16) and 0xFF) * inv255
            var g = ((color ushr 8) and 0xFF) * inv255
            var b = (color and 0xFF) * inv255

            val srcR = r
            val srcG = g
            val srcB = b

            if (isNegative) {
                r = 1f - r
                g = 1f - g
                b = 1f - b
            } else {
                r += p.brightness
                g += p.brightness
                b += p.brightness

                r = (r - 0.5f) * contrastFactor + 0.5f
                g = (g - 0.5f) * contrastFactor + 0.5f
                b = (b - 0.5f) * contrastFactor + 0.5f

                val gray = r * 0.2126f + g * 0.7152f + b * 0.0722f
                r = gray + (r - gray) * satFactor
                g = gray + (g - gray) * satFactor
                b = gray + (b - gray) * satFactor

                val crossX = INV_SQRT3 * (g - b)
                val crossY = INV_SQRT3 * (b - r)
                val crossZ = INV_SQRT3 * (r - g)
                val axisDotTerm = (r + g + b) * ONE_THIRD * (1f - c)

                r = r * c + crossX * s + axisDotTerm
                g = g * c + crossY * s + axisDotTerm
                b = b * c + crossZ * s + axisDotTerm

                r = r * p.red + p.lightness
                g = g * p.green + p.lightness
                b = b * p.blue + p.lightness
            }

            val clampedR = r.coerceIn(0f, 1f)
            val clampedG = g.coerceIn(0f, 1f)
            val clampedB = b.coerceIn(0f, 1f)

            val finalR = (srcR + (clampedR - srcR) * strength).coerceIn(0f, 1f)
            val finalG = (srcG + (clampedG - srcG) * strength).coerceIn(0f, 1f)
            val finalB = (srcB + (clampedB - srcB) * strength).coerceIn(0f, 1f)

            val ir = (finalR * 255f + 0.5f).toInt().coerceIn(0, 255)
            val ig = (finalG * 255f + 0.5f).toInt().coerceIn(0, 255)
            val ib = (finalB * 255f + 0.5f).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (ir shl 16) or (ig shl 8) or ib
        }
    }

    /**
     * Renders a filtered copy of the source Bitmap.
     */
    fun renderFilteredBitmap(
        source: Bitmap,
        preset: VideoFilter,
        strength: Float = 1f,
    ): Bitmap {
        if (preset == VideoFilter.ORIGINAL || strength <= 0f) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        applyFilterToPixels(pixels, preset, strength)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Compares two bitmaps to detect if a filter produced virtually no change.
     * Helpful for logging potential no-op filters during debugging.
     */
    fun isPotentialNoOp(original: Bitmap, filtered: Bitmap, tolerance: Float = 0.015f): Boolean {
        if (original.width != filtered.width || original.height != filtered.height) return false
        val width = original.width
        val height = original.height
        val origPixels = IntArray(width * height)
        val filtPixels = IntArray(width * height)
        original.getPixels(origPixels, 0, width, 0, 0, width, height)
        filtered.getPixels(filtPixels, 0, width, 0, 0, width, height)

        var totalDelta = 0.0
        val step = max(1, (width * height) / 200) // Sample ~200 points for speed
        var samples = 0

        for (i in origPixels.indices step step) {
            val c1 = origPixels[i]
            val c2 = filtPixels[i]
            val rDiff = abs(((c1 ushr 16) and 0xFF) - ((c2 ushr 16) and 0xFF)) / 255.0
            val gDiff = abs(((c1 ushr 8) and 0xFF) - ((c2 ushr 8) and 0xFF)) / 255.0
            val bDiff = abs((c1 and 0xFF) - (c2 and 0xFF)) / 255.0
            totalDelta += (rDiff + gDiff + bDiff) / 3.0
            samples++
        }

        val avgDelta = if (samples > 0) totalDelta / samples else 0.0
        return avgDelta < tolerance
    }
}
