package com.termex.replay15.editor.color

import android.graphics.Bitmap
import com.termex.replay15.editor.domain.StudioGrade
import com.termex.replay15.editor.domain.VideoClip
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Result of matching colors between a reference frame and a target frame.
 */
data class ColorMatchResult(
    val brightnessOffset: Float = 0f,
    val contrastOffset: Float = 0f,
    val saturationOffset: Float = 0f,
    val temperatureOffset: Float = 0f,
    val exposureOffset: Float = 0f,
    val shadowsOffset: Float = 0f,
    val highlightsOffset: Float = 0f,
) {
    init {
        require(brightnessOffset in -1f..1f)
        require(contrastOffset in -.9f.. .9f)
        require(saturationOffset in -100f..100f)
        require(temperatureOffset in -1f..1f)
        require(exposureOffset in -3f..3f)
        require(shadowsOffset in -1f..1f)
        require(highlightsOffset in -1f..1f)
    }

    /**
     * Applies the calculated delta to the target clip with a selectable strength (0.0 to 1.0).
     */
    fun applyTo(targetClip: VideoClip, strength: Float = 1f): VideoClip {
        val f = strength.coerceIn(0f, 1f)
        return targetClip.copy(
            brightness = (targetClip.brightness + brightnessOffset * f).coerceIn(-1f, 1f),
            contrast = (targetClip.contrast + contrastOffset * f).coerceIn(-.9f, .9f),
            saturation = (targetClip.saturation + saturationOffset * f).coerceIn(-100f, 100f),
            temperature = (targetClip.temperature + temperatureOffset * f).coerceIn(-1f, 1f),
            grade = targetClip.grade.copy(
                exposure = (targetClip.grade.exposure + exposureOffset * f).coerceIn(-3f, 3f),
                shadows = (targetClip.grade.shadows + shadowsOffset * f).coerceIn(-1f, 1f),
                highlights = (targetClip.grade.highlights + highlightsOffset * f).coerceIn(-1f, 1f),
            ),
        )
    }
}

/**
 * ColorMatchEngine analyzes color characteristics (luminance, contrast distribution,
 * RGB tonal balance, saturation) of a reference frame and aligns the target clip
 * to match the look of the reference frame in a non-destructive manner.
 */
object ColorMatchEngine {

    /**
     * Calculates the corrective offsets needed on the target clip to match the reference frame.
     */
    fun match(referenceStats: FrameColorStats, targetStats: FrameColorStats): ColorMatchResult {
        // 1. Exposure / Luminance match
        val lumaDelta = referenceStats.meanLuma - targetStats.meanLuma
        val exposureOffset = (lumaDelta * 0.9f).coerceIn(-1.2f, 1.2f)
        val brightnessOffset = (lumaDelta * 0.35f).coerceIn(-0.3f, 0.3f)

        // 2. Contrast match (compare dynamic range P95 - P5)
        val refRange = referenceStats.p95Luma - referenceStats.p5Luma
        val targetRange = targetStats.p95Luma - targetStats.p5Luma
        val rangeDelta = (refRange - targetRange) * 0.6f
        val contrastOffset = rangeDelta.coerceIn(-0.35f, 0.45f)

        // 3. White balance / Color temperature match
        // Evaluate warm/cool differential (B - R)
        val refTemp = referenceStats.meanB - referenceStats.meanR
        val targetTemp = targetStats.meanB - targetStats.meanR
        val tempDelta = (refTemp - targetTemp) * 0.8f
        val temperatureOffset = tempDelta.coerceIn(-0.5f, 0.5f)

        // 4. Shadow / Highlight tone matching
        val shadowDelta = (referenceStats.p5Luma - targetStats.p5Luma) * 1.2f
        val shadowsOffset = shadowDelta.coerceIn(-0.4f, 0.4f)

        val highlightDelta = (referenceStats.p95Luma - targetStats.p95Luma) * 1.2f
        val highlightsOffset = highlightDelta.coerceIn(-0.4f, 0.4f)

        // 5. Saturation match
        val satDelta = (referenceStats.meanSaturation - targetStats.meanSaturation) * 75f
        val saturationOffset = satDelta.coerceIn(-35f, 35f)

        return ColorMatchResult(
            brightnessOffset = brightnessOffset,
            contrastOffset = contrastOffset,
            saturationOffset = saturationOffset,
            temperatureOffset = temperatureOffset,
            exposureOffset = exposureOffset,
            shadowsOffset = shadowsOffset,
            highlightsOffset = highlightsOffset,
        )
    }

    /**
     * Matches two bitmaps directly (reference and target).
     */
    fun match(referenceBitmap: Bitmap, targetBitmap: Bitmap): ColorMatchResult {
        val refStats = AutoColorEngine.analyze(referenceBitmap)
        val targetStats = AutoColorEngine.analyze(targetBitmap)
        return match(refStats, targetStats)
    }
}

