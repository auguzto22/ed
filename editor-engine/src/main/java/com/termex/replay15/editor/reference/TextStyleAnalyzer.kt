package com.termex.replay15.editor.reference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.termex.replay15.editor.domain.TextAnimation
import com.termex.replay15.editor.domain.TextFont
import kotlin.math.abs

private data class TextObservation(val timeUs: Long, val textHash: Int, val words: Int, val x: Float, val y: Float, val width: Float, val height: Float, val fill: Int, val darkBorder: Boolean)

object TextStyleAnalyzer {
    fun analyze(context: Context, info: ReferenceMediaInfo, cancellation: ReferenceAnalysisCancellation): CaptionStyleProfile {
        val retriever = MediaMetadataRetriever(); val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            retriever.setDataSource(context, Uri.parse(info.uri))
            val stepUs = maxOf(650_000L, info.durationUs / 120L)
            val observations = mutableListOf<TextObservation>(); var timeUs = 0L
            while (timeUs < info.durationUs) {
                cancellation.check()
                val bitmap = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 480, 270)
                if (bitmap != null) try {
                    val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                    result.textBlocks.flatMap { it.lines }.forEach { line ->
                        val box = line.boundingBox ?: return@forEach
                        if (box.width() < bitmap.width * .04f || box.height() < bitmap.height * .025f) return@forEach
                        val normalizedY = box.centerY() / bitmap.height.toFloat()
                        val pixels = robustColors(bitmap, box.left, box.top, box.right, box.bottom)
                        observations += TextObservation(timeUs, line.text.lowercase().trim().hashCode(), line.text.trim().split(Regex("\\s+")).size,
                            box.centerX() / bitmap.width.toFloat(), normalizedY, box.width() / bitmap.width.toFloat(), box.height() / bitmap.height.toFloat(), pixels.first, pixels.second)
                    }
                } finally { bitmap.recycle() }
                timeUs += stepUs
            }
            if (observations.isEmpty()) return CaptionStyleProfile()
            val sampleCount = ((info.durationUs + stepUs - 1) / stepUs).toInt().coerceAtLeast(1)
            val persistentHashes = observations.groupingBy { it.textHash }.eachCount().filterValues { it >= sampleCount * .65f }.keys
            val candidates = observations.filter { it.textHash !in persistentHashes && it.y in .5f.. .94f && it.width < .9f }
            if (candidates.size < 2) return CaptionStyleProfile()
            val xs = candidates.map { it.x }; val ys = candidates.map { it.y }; val heights = candidates.map { it.height }
            val words = candidates.map { it.words }; val fill = dominantColor(candidates.map { it.fill })
            val relativeHeight = heights.median().coerceIn(.02f, .18f)
            val condensed = candidates.map { it.width / it.words.coerceAtLeast(1) }.median() < .11f
            val bold = candidates.count { it.darkBorder } >= candidates.size / 2 || relativeHeight > .055f
            val font = when { condensed && bold -> TextFont.BEBAS; bold -> TextFont.MONTSERRAT; else -> TextFont.OUTFIT }
            val temporalGroups = candidates.groupBy { it.textHash }.values.map { it.map(TextObservation::timeUs).sorted() }
            val animation = if (temporalGroups.any { group -> group.size <= 2 }) TextAnimation.POP else TextAnimation.FADE
            return CaptionStyleProfile(true, xs.median().coerceIn(.1f, .9f), ys.median().coerceIn(.08f, .92f), relativeHeight,
                fill, 0xFF000000.toInt(), false, bold, false, if (condensed) "condensed sans-serif" else "sans-serif", font,
                if (condensed || bold) .7f else .55f, animation, words.average().toFloat(), words.sorted()[words.size / 2],
                (candidates.size.toFloat() / 12f).coerceIn(.35f, .9f))
        } finally { recognizer.close(); retriever.release() }
    }

    private fun robustColors(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Boolean> {
        val colors = mutableListOf<Int>(); var dark = 0; var total = 0
        val l = left.coerceIn(0, bitmap.width - 1); val r = right.coerceIn(l + 1, bitmap.width)
        val t = top.coerceIn(0, bitmap.height - 1); val b = bottom.coerceIn(t + 1, bitmap.height)
        val step = maxOf(1, (r - l) / 24)
        var y = t
        while (y < b) { var x = l
            while (x < r) { val c = bitmap.getPixel(x, y); val lum = (Color.red(c) * 54 + Color.green(c) * 183 + Color.blue(c) * 19) / 256
                if (lum > 150) colors += c; if (lum < 45) dark++; total++; x += step
            }; y += step
        }
        return dominantColor(colors) to (dark > total * .18f)
    }

    private fun dominantColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.WHITE
        val r = colors.map(Color::red).sorted()[colors.size / 2]; val g = colors.map(Color::green).sorted()[colors.size / 2]; val b = colors.map(Color::blue).sorted()[colors.size / 2]
        return Color.rgb(r, g, b)
    }
}
