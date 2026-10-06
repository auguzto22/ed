package com.termex.replay15.editor.color

import android.graphics.Bitmap
import com.termex.replay15.editor.domain.StudioGrade
import com.termex.replay15.editor.domain.VideoClip
import kotlin.math.max
import kotlin.math.min

/**
 * Adjustment parameters produced by AutoColorEngine.
 * Directly maps to existing VideoClip adjustment parameters and StudioGrade.
 */
data class AutoColorAdjustment(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val exposure: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
) {
    init {
        require(brightness in -1f..1f)
        require(contrast in -.9f.. .9f)
        require(saturation in -100f..100f)
        require(temperature in -1f..1f)
        require(exposure in -3f..3f)
        require(shadows in -1f..1f)
        require(highlights in -1f..1f)
    }

    /** Applies the calculated adjustments to an existing VideoClip in a non-destructive manner. */
    fun applyTo(clip: VideoClip): VideoClip {
        return clip.copy(
            brightness = (clip.brightness + brightness).coerceIn(-1f, 1f),
            contrast = (clip.contrast + contrast).coerceIn(-.9f, .9f),
            saturation = (clip.saturation + saturation).coerceIn(-100f, 100f),
            temperature = (clip.temperature + temperature).coerceIn(-1f, 1f),
            grade = clip.grade.copy(
                exposure = (clip.grade.exposure + exposure).coerceIn(-3f, 3f),
                shadows = (clip.grade.shadows + shadows).coerceIn(-1f, 1f),
                highlights = (clip.grade.highlights + highlights).coerceIn(-1f, 1f),
            ),
        )
    }
}

/**
 * Statistics extracted from sampled pixels for auto-color evaluation.
 */
data class FrameColorStats(
    val meanLuma: Float,
    val p5Luma: Float,
    val p95Luma: Float,
    val meanR: Float,
    val meanG: Float,
    val meanB: Float,
    val meanSaturation: Float,
)

/**
 * AutoColorEngine evaluates exposure, white balance, contrast, highlights, shadows,
 * and saturation from a sample bitmap and calculates balanced, non-destructive adjustments
 * that feed directly into the existing VideoClip parameters and StudioGrade.
 */
object AutoColorEngine {

    /**
     * Extracts luminance and RGB statistics from a frame bitmap.
     * Uses downsampled pixels for maximum performance on mobile.
     */
    fun analyze(bitmap: Bitmap): FrameColorStats {
        val width = bitmap.width
        val height = bitmap.height
        val stepX = max(1, width / 64)
        val stepY = max(1, height / 64)

        var sumLuma = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumSat = 0.0
        var count = 0

        val lumaSamples = mutableListOf<Float>()

        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16 and 0xFF) / 255f
                val g = (pixel shr 8 and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f

                // Rec. 709 luminance
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val sat = if (maxC > 0f) (maxC - minC) / maxC else 0f

                sumLuma += luma
                sumR += r
                sumG += g
                sumB += b
                sumSat += sat
                count++
                lumaSamples.add(luma)
            }
        }

        if (count == 0) {
            return FrameColorStats(0.5f, 0f, 1f, 0.5f, 0.5f, 0.5f, 0.3f)
        }

        lumaSamples.sort()
        val p5 = lumaSamples[(count * 0.05f).toInt().coerceIn(0, count - 1)]
        val p95 = lumaSamples[(count * 0.95f).toInt().coerceIn(0, count - 1)]

        return FrameColorStats(
            meanLuma = (sumLuma / count).toFloat(),
            p5Luma = p5,
            p95Luma = p95,
            meanR = (sumR / count).toFloat(),
            meanG = (sumG / count).toFloat(),
            meanB = (sumB / count).toFloat(),
            meanSaturation = (sumSat / count).toFloat(),
        )
    }

    /**
     * Calculates the corrective adjustments based on color statistics.
     * All values are moderate to avoid harsh over-correction.
     */
    fun calculate(stats: FrameColorStats): AutoColorAdjustment {
        // 1. Exposure & Brightness: target mean luma around 0.46 (ideal midtone)
        val targetLuma = 0.46f
        val lumaDelta = targetLuma - stats.meanLuma
        // Subtle brightness and exposure correction
        val brightness = (lumaDelta * 0.45f).coerceIn(-0.35f, 0.35f)
        val exposure = (lumaDelta * 0.8f).coerceIn(-0.8f, 0.8f)

        // 2. Contrast: evaluate dynamic range between 5th and 95th percentiles
        val dynamicRange = stats.p95Luma - stats.p5Luma
        // Target dynamic range around 0.75
        val targetRange = 0.75f
        val contrastDelta = (targetRange - dynamicRange) * 0.5f
        val contrast = contrastDelta.coerceIn(-0.3f, 0.4f)

        // 3. White balance / Temperature: compare Red vs Blue
        // If Red > Blue, scene is too warm -> cool down (negative temperature)
        // If Blue > Red, scene is too cool -> warm up (positive temperature)
        val rbDelta = stats.meanB - stats.meanR
        val temperature = (rbDelta * 0.6f).coerceIn(-0.4f, 0.4f)

        // 4. Shadows & Highlights recovery:
        // Deep shadows (< 0.08) -> lift shadows
        val shadows = if (stats.p5Luma < 0.08f) {
            ((0.08f - stats.p5Luma) * 1.5f).coerceIn(0f, 0.4f)
        } else if (stats.p5Luma > 0.22f) {
            -((stats.p5Luma - 0.22f) * 1.2f).coerceIn(0f, 0.3f)
        } else 0f

        // Blown highlights (> 0.92) -> pull back highlights
        val highlights = if (stats.p95Luma > 0.92f) {
            -((stats.p95Luma - 0.92f) * 1.8f).coerceIn(0f, 0.4f)
        } else if (stats.p95Luma < 0.70f) {
            ((0.70f - stats.p95Luma) * 0.8f).coerceIn(0f, 0.25f)
        } else 0f

        // 5. Saturation: target moderate vibrant saturation around 0.32
        val targetSat = 0.32f
        val satDelta = (targetSat - stats.meanSaturation) * 60f
        val saturation = satDelta.coerceIn(-25f, 25f)

        return AutoColorAdjustment(
            brightness = brightness,
            contrast = contrast,
            saturation = saturation,
            temperature = temperature,
            exposure = exposure,
            shadows = shadows,
            highlights = highlights,
        )
    }

    /**
     * One-shot convenience: analyzes bitmap and produces adjustments.
     */
    fun evaluate(bitmap: Bitmap): AutoColorAdjustment {
        val stats = analyze(bitmap)
        return calculate(stats)
    }
}

