package com.termex.replay15.editor.reference

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class FrameAnalysisConfig(
    val coarseStepUs: Long = 150_000L, val refinementStepUs: Long = 33_333L,
    val sampleWidth: Int = 160, val sampleHeight: Int = 90, val maxCoarseFrames: Int = 2_400,
) {
    init { require(coarseStepUs in 100_000L..250_000L && refinementStepUs in 16_000L..50_000L && sampleWidth in 64..320 && sampleHeight in 36..240) }
    fun coarseTimestamps(durationUs: Long): List<Long> {
        require(durationUs > 0)
        val step = ((durationUs + maxCoarseFrames - 1) / maxCoarseFrames).coerceAtLeast(coarseStepUs)
        return generateSequence(0L) { it + step }.takeWhile { it < durationUs }.toList()
    }
}

data class FrameFeatures(
    val timeUs: Long, val width: Int, val height: Int, val luma: ByteArray, val histogram: FloatArray,
    val meanLuma: Float, val edgeEnergy: Float, val sharpness: Float, val saturation: Float,
    val temperature: Float, val rgbSeparation: Float,
)

data class FrameDelta(
    val timeUs: Long, val difference: Float, val histogramDifference: Float, val luminanceDifference: Float,
    val edgeDifference: Float, val continuity: Float, val translationX: Float, val translationY: Float,
    val scale: Float, val sharpnessRatio: Float, val rgbSeparation: Float,
)

class ReferenceFrameSampler(private val context: Context, private val config: FrameAnalysisConfig = FrameAnalysisConfig()) {
    fun coarse(info: ReferenceMediaInfo, cancellation: ReferenceAnalysisCancellation, consume: (FrameFeatures) -> Unit) {
        sample(info, coarseTimestamps(info.durationUs), cancellation, consume)
    }

    fun coarseTimestamps(durationUs: Long): List<Long> {
        return config.coarseTimestamps(durationUs)
    }

    fun refine(info: ReferenceMediaInfo, centersUs: Collection<Long>, cancellation: ReferenceAnalysisCancellation, consume: (FrameFeatures) -> Unit) {
        val times = sortedSetOf<Long>()
        centersUs.forEach { center ->
            var time = (center - 250_000L).coerceAtLeast(0)
            val end = (center + 250_000L).coerceAtMost(info.durationUs - 1)
            while (time <= end) { times += time; time += config.refinementStepUs }
        }
        sample(info, times.toList(), cancellation, consume)
    }

    fun sample(info: ReferenceMediaInfo, timesUs: List<Long>, cancellation: ReferenceAnalysisCancellation, consume: (FrameFeatures) -> Unit) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(info.uri))
            for (time in timesUs) {
                cancellation.check()
                val bitmap = retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, config.sampleWidth, config.sampleHeight) ?: continue
                try { consume(extract(time, bitmap)) } finally { bitmap.recycle() }
            }
        } finally { retriever.release() }
    }

    companion object {
        fun extract(timeUs: Long, bitmap: Bitmap): FrameFeatures {
            val width = bitmap.width; val height = bitmap.height
            val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val luma = ByteArray(pixels.size); val histogram = FloatArray(32)
            var sum = 0.0; var saturation = 0.0; var temperature = 0.0; var rgbEdges = 0.0; var edge = 0.0; var laplace = 0.0
            for (i in pixels.indices) {
                val color = pixels[i]; val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
                val y = (r * 54 + g * 183 + b * 19) ushr 8
                luma[i] = y.toByte(); histogram[(y ushr 3).coerceAtMost(31)]++; sum += y
                val high = max(r, max(g, b)); val low = min(r, min(g, b)); saturation += if (high == 0) 0.0 else (high - low).toDouble() / high
                temperature += (r - b) / 255.0
                if (i % width > 0) {
                    val left = pixels[i - 1]; val rr = left ushr 16 and 255; val gg = left ushr 8 and 255; val bb = left and 255
                    val dy = abs(y - (luma[i - 1].toInt() and 255)); edge += dy
                    rgbEdges += abs(abs(r - rr) - abs(b - bb)) + abs(abs(g - gg) - abs(b - bb))
                }
                if (i >= width && i % width > 0) {
                    val center = y; val leftY = luma[i - 1].toInt() and 255; val upY = luma[i - width].toInt() and 255
                    laplace += abs(center * 2 - leftY - upY)
                }
            }
            histogram.indices.forEach { histogram[it] /= pixels.size.toFloat() }
            val count = pixels.size.toFloat().coerceAtLeast(1f)
            return FrameFeatures(timeUs, width, height, luma, histogram, (sum / count / 255.0).toFloat(),
                (edge / count / 64.0).toFloat(), (laplace / count / 96.0).toFloat(), (saturation / count).toFloat(),
                (temperature / count).toFloat(), (rgbEdges / count / 96.0).toFloat())
        }
    }
}

object GlobalMotionEstimator {
    fun delta(before: FrameFeatures, after: FrameFeatures): FrameDelta {
        require(before.width == after.width && before.height == after.height)
        val w = before.width; val h = before.height
        var bestDx = 0; var bestDy = 0; var bestError = Double.MAX_VALUE
        for (dy in -5..5) for (dx in -5..5) {
            var error = 0L; var count = 0
            var y = 7
            while (y < h - 7) { var x = 7
                while (x < w - 7) {
                    val a = before.luma[y * w + x].toInt() and 255
                    val b = after.luma[(y + dy) * w + x + dx].toInt() and 255
                    error += abs(a - b); count++; x += 4
                }; y += 4
            }
            val normalized = error.toDouble() / count.coerceAtLeast(1)
            if (normalized < bestError) { bestError = normalized; bestDx = dx; bestDy = dy }
        }
        var raw = 0L; var count = 0
        for (i in before.luma.indices step 3) { raw += abs((before.luma[i].toInt() and 255) - (after.luma[i].toInt() and 255)); count++ }
        val hist = before.histogram.indices.sumOf { abs(before.histogram[it] - after.histogram[it]).toDouble() }.toFloat() / 2f
        val luminance = abs(before.meanLuma - after.meanLuma)
        val edges = abs(before.edgeEnergy - after.edgeEnergy).coerceAtMost(1f)
        val continuity = (1f - (bestError / 72.0).toFloat()).coerceIn(0f, 1f)
        val difference = ((raw.toFloat() / count.coerceAtLeast(1) / 90f) * .45f + hist * .35f + luminance * .12f + edges * .08f).coerceIn(0f, 1f)
        val scale = estimateScale(before, after)
        return FrameDelta(after.timeUs, difference, hist, luminance, edges, continuity, bestDx / w.toFloat(), bestDy / h.toFloat(), scale,
            (after.sharpness / before.sharpness.coerceAtLeast(.001f)).coerceIn(0f, 4f), after.rgbSeparation)
    }

    private fun estimateScale(a: FrameFeatures, b: FrameFeatures): Float {
        val candidates = floatArrayOf(.94f, .97f, 1f, 1.03f, 1.06f)
        val cx = (a.width - 1) / 2f; val cy = (a.height - 1) / 2f
        return candidates.minBy { scale ->
            var error = 0L; var count = 0; var y = 9
            while (y < a.height - 9) { var x = 9
                while (x < a.width - 9) {
                    val bx = (cx + (x - cx) / scale).toInt().coerceIn(0, a.width - 1)
                    val by = (cy + (y - cy) / scale).toInt().coerceIn(0, a.height - 1)
                    error += abs((a.luma[y * a.width + x].toInt() and 255) - (b.luma[by * b.width + bx].toInt() and 255)); count++; x += 5
                }; y += 5
            }; error.toDouble() / count.coerceAtLeast(1)
        }
    }
}
