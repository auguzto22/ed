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

/** Caption track style plus the other on-screen text (titles, cards) seen in the reference. */
data class ReferenceTextAnalysis(val captions: CaptionStyleProfile, val titles: List<ReferenceLayoutElement>)

object TextStyleAnalyzer {
    /** OCR needs real pixels: ~16 px per glyph. Long side 1280 keeps 9:16 captions legible. */
    internal const val OCR_LONG_SIDE = 1280
    internal const val OCR_SHORT_SIDE = 720
    private const val MAX_OCR_SAMPLES = 120
    private const val MIN_OCR_STEP_US = 250_000L

    fun analyze(context: Context, info: ReferenceMediaInfo, cancellation: ReferenceAnalysisCancellation): CaptionStyleProfile =
        analyzeAll(context, info, cancellation).captions

    internal fun ocrBox(width: Int, height: Int): Pair<Int, Int> =
        if (height > width) OCR_SHORT_SIDE to OCR_LONG_SIDE else OCR_LONG_SIDE to OCR_SHORT_SIDE

    internal fun ocrStepUs(durationUs: Long): Long = maxOf(MIN_OCR_STEP_US, durationUs / MAX_OCR_SAMPLES)

    fun analyzeAll(context: Context, info: ReferenceMediaInfo, cancellation: ReferenceAnalysisCancellation): ReferenceTextAnalysis {
        val retriever = MediaMetadataRetriever(); val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val stepUs = ocrStepUs(info.durationUs)
            val (boxW, boxH) = ocrBox(info.width, info.height)
            val observations = mutableListOf<TextObservation>()
            val lines = mutableListOf<TextLineObservation>()
            val times = generateSequence(0L) { it + stepUs }.takeWhile { it < info.durationUs }.toList()
            fun observe(timeUs: Long, bitmap: Bitmap) {
                try {
                    val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                    result.textBlocks.flatMap { it.lines }.forEach { line ->
                        val box = line.boundingBox ?: return@forEach
                        if (box.width() < bitmap.width * .04f || box.height() < bitmap.height * .012f) return@forEach
                        val normalizedY = box.centerY() / bitmap.height.toFloat()
                        val pixels = robustColors(bitmap, box.left, box.top, box.right, box.bottom)
                        val text = line.text.trim()
                        observations += TextObservation(timeUs, text.lowercase().hashCode(), text.split(Regex("\\s+")).size,
                            box.centerX() / bitmap.width.toFloat(), normalizedY, box.width() / bitmap.width.toFloat(), box.height() / bitmap.height.toFloat(), pixels.first, pixels.second)
                        lines += TextLineObservation(timeUs, text, LayoutBox.fromEdges(box.left / bitmap.width.toFloat(), box.top / bitmap.height.toFloat(),
                            box.right / bitmap.width.toFloat(), box.bottom / bitmap.height.toFloat()).clamped(), pixels.first,
                            backgroundBehind(bitmap, box.left, box.top, box.right, box.bottom), pixels.second)
                    }
                } finally { bitmap.recycle() }
            }
            // Sequential decode; only the remainder of a failed fast pass uses per-frame seeks.
            val handled = com.termex.replay15.editor.media.SequentialFrameReader.read(
                context, info.uri, times, boxW, boxH, cancellation::check, ::observe)
            if (handled < times.size) {
                retriever.setDataSource(context, Uri.parse(info.uri))
                for (timeUs in times.subList(handled, times.size)) {
                    cancellation.check()
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, boxW, boxH)?.let { observe(timeUs, it) }
                }
            }
            if (observations.isEmpty()) return ReferenceTextAnalysis(CaptionStyleProfile(), emptyList())
            val sampleCount = ((info.durationUs + stepUs - 1) / stepUs).toInt().coerceAtLeast(1)
            // Text on screen the whole time is a watermark/handle, not a caption or a title.
            val persistentHashes = observations.groupingBy { it.textHash }.eachCount().filterValues { it >= sampleCount * .65f }.keys
            val persistentTexts = lines.groupingBy { ReferenceLayoutDetector.normalize(it.text) }.eachCount()
                .filterValues { it >= sampleCount * .65f }.keys
            val titles = ReferenceLayoutDetector.titles(lines, info.durationUs, stepUs, persistentTexts)
            val candidates = observations.filter { obs ->
                obs.textHash !in persistentHashes && obs.y in .45f.. .94f && obs.width < .9f && obs.height < .06f
            }.filterNot { obs ->
                // Lines that belong to a title/card are not caption samples.
                titles.any { obs.timeUs in it.startUs..it.endUs && it.box.contains(obs.x, obs.y) }
            }
            if (candidates.size < 2) return ReferenceTextAnalysis(CaptionStyleProfile(), titles)
            val xs = candidates.map { it.x }; val ys = candidates.map { it.y }; val heights = candidates.map { it.height }
            val words = candidates.map { it.words }; val fill = dominantColor(candidates.map { it.fill })
            val relativeHeight = heights.median().coerceIn(.02f, .18f)
            val condensed = candidates.map { it.width / it.words.coerceAtLeast(1) }.median() < .11f
            val bold = candidates.count { it.darkBorder } >= candidates.size / 2 || relativeHeight > .055f
            val font = when { condensed && bold -> TextFont.BEBAS; bold -> TextFont.MONTSERRAT; else -> TextFont.OUTFIT }
            val temporalGroups = candidates.groupBy { it.textHash }.values.map { it.map(TextObservation::timeUs).sorted() }
            val animation = if (temporalGroups.any { group -> group.size <= 2 }) TextAnimation.POP else TextAnimation.FADE
            val captionLines = lines.filter { line -> candidates.any { it.timeUs == line.timeUs && it.textHash == line.text.lowercase().hashCode() } }
            val letters = captionLines.joinToString("") { it.text }.filter(Char::isLetter)
            val uppercase = letters.length >= 4 && letters.count(Char::isUpperCase) >= letters.length * .8f
            return ReferenceTextAnalysis(CaptionStyleProfile(true, xs.median().coerceIn(.1f, .9f), ys.median().coerceIn(.08f, .92f), relativeHeight,
                fill, 0xFF000000.toInt(), false, bold, false, if (condensed) "condensed sans-serif" else "sans-serif", font,
                if (condensed || bold) .7f else .55f, animation, words.average().toFloat(), words.sorted()[words.size / 2],
                (candidates.size.toFloat() / 12f).coerceIn(.35f, .9f), uppercase), titles)
        } finally { recognizer.close(); retriever.release() }
    }

    /** Uniform color in a thin ring around the text box = the text sits on a card/plate. */
    private fun backgroundBehind(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int? {
        val pad = ((bottom - top) * .35f).toInt().coerceAtLeast(2)
        val samples = mutableListOf<Int>()
        val l = (left - pad).coerceIn(0, bitmap.width - 1); val r = (right + pad).coerceIn(0, bitmap.width - 1)
        val t = (top - pad).coerceIn(0, bitmap.height - 1); val b = (bottom + pad).coerceIn(0, bitmap.height - 1)
        val step = maxOf(1, (r - l) / 20)
        var x = l
        while (x <= r) { samples += bitmap.getPixel(x, t); samples += bitmap.getPixel(x, b); x += step }
        if (samples.size < 8) return null
        val median = dominantColor(samples)
        val close = samples.count { abs(Color.red(it) - Color.red(median)) + abs(Color.green(it) - Color.green(median)) + abs(Color.blue(it) - Color.blue(median)) < 60 }
        return if (close >= samples.size * .8f) median else null
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
