package com.termex.replay15.editor.scopes

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The measurements a video scope draws: luma waveform, RGB parade, vectorscope and histogram.
 *
 * Pure Kotlin on purpose. Everything here works on a small ARGB sample of the frame, so the
 * panel can be exercised without a device, and the numbers shown to the user are the same ones
 * the tests assert on.
 */
data class ScopeFrame(
    val waveform: FloatArray,
    val paradeRed: FloatArray,
    val paradeGreen: FloatArray,
    val paradeBlue: FloatArray,
    val vectorscope: FloatArray,
    val histogram: FloatArray,
    val lumaMin: Int,
    val lumaMax: Int,
    val lumaAverage: Int,
    val clippedHighlights: Float,
    val clippedShadows: Float,
    val saturation: Float,
    /**
     * Average level per channel, 0..255.
     *
     * The parades themselves cannot answer "which channel dominates": each one is normalised
     * against the pixel count, so all three always sum to one and every image would look
     * perfectly balanced. A colour cast is a difference in average level, not in share of pixels.
     */
    val channelLevels: FloatArray,
) {
    val redLevel: Float get() = channelLevels[0]
    val greenLevel: Float get() = channelLevels[1]
    val blueLevel: Float get() = channelLevels[2]
    val vectorCount: Int get() = vectorscope.size / 2
    val waveformColumns: Int get() = waveform.size

    /** Largest hue excursion from neutral grey, the standard saturation read of a vectorscope. */
    fun saturationScore(): Float = saturation

    /** Where the tonal range sits, the single number that says "flat", "washed" or "crushed". */
    fun tonalSpread(): Float = (lumaMax - lumaMin) / 255f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScopeFrame) return false
        return lumaMin == other.lumaMin && lumaMax == other.lumaMax && lumaAverage == other.lumaAverage &&
            clippedHighlights == other.clippedHighlights && clippedShadows == other.clippedShadows &&
            saturation == other.saturation && channelLevels.contentEquals(other.channelLevels) &&
            waveform.contentEquals(other.waveform) && paradeRed.contentEquals(other.paradeRed) &&
            paradeGreen.contentEquals(other.paradeGreen) && paradeBlue.contentEquals(other.paradeBlue) &&
            vectorscope.contentEquals(other.vectorscope) && histogram.contentEquals(other.histogram)
    }

    override fun hashCode(): Int {
        var result = lumaMin
        result = 31 * result + lumaMax
        result = 31 * result + lumaAverage
        result = 31 * result + clippedHighlights.hashCode()
        result = 31 * result + clippedShadows.hashCode()
        result = 31 * result + saturation.hashCode()
        result = 31 * result + channelLevels.contentHashCode()
        result = 31 * result + waveform.contentHashCode()
        result = 31 * result + vectorscope.contentHashCode()
        return result
    }
}

object ScopeAnalyzer {

    const val WAVEFORM_COLUMNS = 128
    const val PARADE_LEVELS = 64
    const val VECTOR_BINS = 48
    const val HISTOGRAM_BINS = 64

    /** Pixels above this level are treated as blown, not merely bright. */
    const val HIGHLIGHT_CLIP = 250
    const val SHADOW_CLIP = 5

    /**
     * Builds every scope from one ARGB sample of the frame.
     *
     * [pixels] is a small downscaled frame, typically a few thousand samples. Scanning it costs
     * well under a millisecond, so the panel can refresh on every seek without a dedicated
     * pipeline or a second decoder.
     */
    fun analyze(pixels: IntArray, width: Int, height: Int): ScopeFrame {
        require(pixels.size >= width * height && width > 0 && height > 0)
        val waveform = FloatArray(WAVEFORM_COLUMNS)
        val red = FloatArray(PARADE_LEVELS)
        val green = FloatArray(PARADE_LEVELS)
        val blue = FloatArray(PARADE_LEVELS)
        val vectors = FloatArray(VECTOR_BINS * VECTOR_BINS * 2)
        val histogram = FloatArray(HISTOGRAM_BINS)
        val columnLuma = IntArray(WAVEFORM_COLUMNS)
        val columnCount = IntArray(WAVEFORM_COLUMNS)

        var lumaMin = 255
        var lumaMax = 0
        var lumaSum = 0L
        var highlights = 0
        var shadows = 0
        var saturationSum = 0.0
        var vectorWeight = 0.0
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L

        for (index in 0 until width * height) {
            val color = pixels[index]
            val r = color ushr 16 and 255
            val g = color ushr 8 and 255
            val b = color and 255
            val y = (r * 54 + g * 183 + b * 19) ushr 8

            // A narrow frame still has to land inside the column array. Deriving the column from
            // the linear index alone walks off the end as soon as the frame has more than one row.
            val x = index % width
            val column = if (width >= WAVEFORM_COLUMNS) x else x * WAVEFORM_COLUMNS / width
            columnLuma[column] += y
            columnCount[column]++
            if (y < lumaMin) lumaMin = y
            if (y > lumaMax) lumaMax = y
            lumaSum += y
            histogram[(y * HISTOGRAM_BINS / 256).coerceAtMost(HISTOGRAM_BINS - 1)]++
            if (y >= HIGHLIGHT_CLIP) highlights++
            if (y <= SHADOW_CLIP) shadows++

            red[(r * PARADE_LEVELS / 256).coerceAtMost(PARADE_LEVELS - 1)]++
            green[(g * PARADE_LEVELS / 256).coerceAtMost(PARADE_LEVELS - 1)]++
            blue[(b * PARADE_LEVELS / 256).coerceAtMost(PARADE_LEVELS - 1)]++
            redSum += r; greenSum += g; blueSum += b

            val high = max(r, max(g, b))
            val low = minOf(r, minOf(g, b))
            val chroma = high - low
            if (high != 0) saturationSum += chroma.toDouble() / high
            // A neutral pixel has no hue to place; counting it would bury the trace in the middle.
            if (chroma >= 8) {
                // Rec.601 luma-normalised chroma, so the radius means saturation, not brightness.
                val cb = (b - y) * .564f
                val cr = (r - y) * .713f
                val radius = sqrt((cb * cb + cr * cr).toDouble()).toFloat().coerceIn(0f, 1f)
                val angle = Math.toDegrees(kotlin.math.atan2(cr.toDouble(), cb.toDouble())).toFloat()
                val binX = ((angle + 180f) / 360f * VECTOR_BINS).toInt().coerceIn(0, VECTOR_BINS - 1)
                val binY = (radius * (VECTOR_BINS - 1)).toInt().coerceIn(0, VECTOR_BINS - 1)
                val slot = (binY * VECTOR_BINS + binX) * 2
                vectors[slot] += 1f
                vectors[slot + 1] = max(vectors[slot + 1], radius)
                vectorWeight += 1.0
            }
        }

        val count = (width * height).toFloat()
        for (column in 0 until WAVEFORM_COLUMNS) {
            val samples = columnCount[column]
            if (samples > 0) {
                waveform[column] = columnLuma[column].toFloat() / samples / 255f
            }
        }
        histogram.indices.forEach { histogram[it] /= count }
        red.indices.forEach { red[it] /= count; green[it] /= count; blue[it] /= count }
        for (slot in 0 until VECTOR_BINS * VECTOR_BINS) {
            vectors[slot * 2] /= count
        }

        return ScopeFrame(
            waveform = waveform, paradeRed = red, paradeGreen = green, paradeBlue = blue,
            vectorscope = vectors, histogram = histogram,
            lumaMin = lumaMin, lumaMax = lumaMax, lumaAverage = (lumaSum / count).toInt(),
            clippedHighlights = highlights / count, clippedShadows = shadows / count,
            saturation = if (vectorWeight > 0) (saturationSum / count).toFloat() else 0f,
            channelLevels = floatArrayOf(redSum / count, greenSum / count, blueSum / count),
        )
    }
}
