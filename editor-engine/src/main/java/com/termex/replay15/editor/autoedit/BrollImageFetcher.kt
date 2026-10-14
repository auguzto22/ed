package com.termex.replay15.editor.autoedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds and stores one picture per B-roll decision. Each image is downloaded once, cropped to
 * the canvas aspect (so it fills the frame whether the project fits or fills) and saved as a JPEG
 * in app storage, where the project references it like any imported photo. Moments without a
 * usable picture are simply left out.
 */
class BrollImageFetcher(
    context: Context,
    private val searches: List<StockImageSearch>,
) {
    private val directory = File(context.applicationContext.filesDir, DIRECTORY)

    data class Result(val decisions: List<BrollDecision>, val warnings: List<String>)

    /** Pixel rectangle (android.graphics.Rect is a stub in JVM unit tests). */
    data class CropBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun resolve(
        decisions: List<BrollDecision>,
        canvasAspect: Float,
        cancellation: AutoEditCancellation,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        if (decisions.isEmpty() || searches.isEmpty()) return Result(emptyList(), emptyList())
        require(canvasAspect.isFinite() && canvasAspect > 0f)
        check(directory.isDirectory || directory.mkdirs()) { "Sem espaco para salvar imagens" }
        val tall = canvasAspect < 1f
        val byQuery = HashMap<String, List<StockImage>>()
        val usedUrls = HashSet<String>()
        var searchFailures = 0
        val resolved = decisions.mapIndexedNotNull { index, decision ->
            cancellation.check()
            progress(index, decisions.size)
            val candidates = byQuery.getOrPut(decision.query) {
                searches.asSequence().map { search ->
                    runCatching { search.search(decision.query, tall, cancellation) }
                        .onFailure { if (it is InterruptedException) throw it; searchFailures++ }
                        .getOrDefault(emptyList())
                }.firstOrNull { it.isNotEmpty() }.orEmpty()
            }
            // The same picture twice in one clip looks like a mistake; try the next result instead.
            val media = candidates.asSequence().filter { it.url !in usedUrls }.take(MAX_ATTEMPTS).firstNotNullOfOrNull { image ->
                runCatching { store(image, canvasAspect, cancellation) }
                    .onFailure { if (it is InterruptedException) throw it }
                    .getOrNull()?.also { usedUrls += image.url }
            }
            media?.let { decision.copy(media = it) }
        }
        progress(decisions.size, decisions.size)
        val warnings = buildList {
            if (resolved.size < decisions.size) add("${decisions.size - resolved.size} de ${decisions.size} imagens de B-roll nao foram encontradas")
            if (searchFailures > 0 && resolved.isEmpty()) add("Sem acesso aos bancos de imagens: confira a internet")
        }
        return Result(resolved, warnings)
    }

    private fun store(image: StockImage, canvasAspect: Float, cancellation: AutoEditCancellation): BrollMedia {
        val bytes = StockHttp.getImage(image.url, cancellation)
        cancellation.check()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth; val height = bounds.outHeight
        require(width >= MIN_SIDE && height >= MIN_SIDE) { "Imagem pequena demais" }
        val crop = centerCrop(width, height, canvasAspect)
        val (outWidth, outHeight) = outputSize(crop.width, crop.height)
        val sample = sampleSize(crop.width, crop.height, outWidth, outHeight)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Imagem nao suportada")
        try {
            // Crop in the decoded (sampled) space; clamp because sampling rounds sizes down.
            val sx = decoded.width.toFloat() / width; val sy = decoded.height.toFloat() / height
            val left = (crop.left * sx).roundToInt().coerceIn(0, decoded.width - 1)
            val top = (crop.top * sy).roundToInt().coerceIn(0, decoded.height - 1)
            val cropWidth = (crop.width * sx).roundToInt().coerceIn(1, decoded.width - left)
            val cropHeight = (crop.height * sy).roundToInt().coerceIn(1, decoded.height - top)
            val cropped = Bitmap.createBitmap(decoded, left, top, cropWidth, cropHeight)
            val scaled = if (cropped.width == outWidth && cropped.height == outHeight) cropped
                else Bitmap.createScaledBitmap(cropped, outWidth, outHeight, true)
            try {
                cancellation.check()
                val file = File(directory, "${UUID.randomUUID()}.jpg")
                try {
                    file.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)) { "Falha ao salvar imagem" } }
                } catch (error: Throwable) { file.delete(); throw error }
                return BrollMedia(Uri.fromFile(file).toString(), scaled.width, scaled.height, image.source)
            } finally {
                if (scaled !== cropped) scaled.recycle()
                if (cropped !== decoded) cropped.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    companion object {
        const val DIRECTORY = "auto-broll"
        private const val MIN_SIDE = 320
        private const val MAX_LONG_SIDE = 1920
        private const val MAX_ATTEMPTS = 4

        /** Largest centered rectangle of [aspect] (width/height) inside the image. */
        fun centerCrop(width: Int, height: Int, aspect: Float): CropBox {
            require(width > 0 && height > 0 && aspect > 0f)
            return if (width.toFloat() / height > aspect) {
                val cropWidth = (height * aspect).roundToInt().coerceIn(1, width)
                val left = (width - cropWidth) / 2
                CropBox(left, 0, left + cropWidth, height)
            } else {
                val cropHeight = (width / aspect).roundToInt().coerceIn(1, height)
                val top = (height - cropHeight) / 2
                CropBox(0, top, width, top + cropHeight)
            }
        }

        /** Never upscales; caps the long side at 1920 and keeps sizes even for the encoder. */
        fun outputSize(cropWidth: Int, cropHeight: Int): Pair<Int, Int> {
            val scale = minOf(1f, MAX_LONG_SIDE.toFloat() / max(cropWidth, cropHeight))
            fun even(value: Float) = (((value / 2f).toInt()) * 2).coerceAtLeast(2)
            return even(cropWidth * scale) to even(cropHeight * scale)
        }

        fun sampleSize(cropWidth: Int, cropHeight: Int, outWidth: Int, outHeight: Int): Int {
            var sample = 1
            while (cropWidth / (sample * 2) >= outWidth && cropHeight / (sample * 2) >= outHeight) sample *= 2
            return sample
        }

        /** Deletes downloaded images that ended up unused (preview cancelled or regenerated). */
        fun discard(context: Context, decisions: List<BrollDecision>) {
            val directory = File(context.applicationContext.filesDir, DIRECTORY).canonicalFile
            decisions.mapNotNull { it.media?.uri }.forEach { uri ->
                val path = Uri.parse(uri).path ?: return@forEach
                val file = File(path).canonicalFile
                if (file.parentFile == directory) file.delete()
            }
        }
    }
}
