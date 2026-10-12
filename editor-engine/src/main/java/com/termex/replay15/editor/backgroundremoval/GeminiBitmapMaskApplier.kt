package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.termex.replay15.editor.ai.GeminiApiKeyConfig
import com.termex.replay15.editor.captions.GeminiHttpClient
import com.termex.replay15.editor.domain.BackgroundMode
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.SegmentationQuality
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Removes the background of chosen images (photo overlays / stickers) using only the Gemini API.
 *
 * Gemini returns the traced contour of every foreground subject; everything outside the union
 * of those contours becomes fully transparent. There is no local fallback and no guessed shape:
 * if Gemini cannot trace the subject, this returns null and the caller keeps the original image.
 */
class GeminiBitmapMaskApplier(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val apiKey: String = GeminiApiKeyConfig.getApiKey(),
) : AutoCloseable {

    private val http = GeminiHttpClient(apiKey)
    /** Avoids hammering the API every frame after a failure; retried after the cooldown. */
    private val failedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var closed = false

    fun apply(source: Bitmap, effect: BackgroundRemovalEffect): Bitmap? {
        if (!effect.enabled || effect.mode != BackgroundMode.REMOVE) return source
        if (closed || source.isRecycled) return null
        if (apiKey.isBlank()) {
            Log.w(TAG, "GEMINI_API_KEY ausente; remoção de fundo indisponível")
            return null
        }
        val key = "${System.identityHashCode(source)}:${source.generationId}:${effect.quality}"
        failedAt[key]?.let { if (SystemClock.elapsedRealtime() - it < FAILURE_COOLDOWN_MS) return null }

        val subjects = runCatching { requestSubjects(source, effect.quality) }
            .onFailure { Log.w(TAG, "Gemini não conseguiu recortar a imagem", it) }
            .getOrDefault(emptyList())
        if (subjects.isEmpty()) {
            failedAt[key] = SystemClock.elapsedRealtime()
            return null
        }
        failedAt.remove(key)
        return cutout(source, subjects, effect)
    }

    private fun requestSubjects(source: Bitmap, quality: SegmentationQuality): List<GeminiSubject> {
        val maxDim = when (quality) {
            SegmentationQuality.FAST -> 768
            SegmentationQuality.BALANCED -> 1024
            SegmentationQuality.HIGH -> 1536
        }
        val scale = min(1f, maxDim.toFloat() / max(source.width, source.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else source

        // JPEG has no alpha: flatten onto white so already-transparent areas read as background.
        val opaque = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        if (scaled !== source) scaled.recycle()
        val bytes = ByteArrayOutputStream().use { out ->
            opaque.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.toByteArray()
        }
        opaque.recycle()

        val payload = GeminiSegmentationRequest.build(Base64.encodeToString(bytes, Base64.NO_WRAP))
        val response = http.withRetry({ if (closed) throw IllegalStateException("closed") }) {
            http.createInteraction(payload) { if (closed) throw IllegalStateException("closed") }
        }
        return GeminiSegmentationParser.parseSubjects(GeminiSegmentationRequest.outputText(response))
    }

    /** Keeps only the pixels inside the subjects' contours, at the source's full resolution. */
    private fun cutout(source: Bitmap, subjects: List<GeminiSubject>, effect: BackgroundRemovalEffect): Bitmap {
        val w = source.width
        val h = source.height
        val path = Path().apply { fillType = Path.FillType.WINDING }
        subjects.forEach { subject ->
            subject.polygon.forEachIndexed { index, (x, y) ->
                if (index == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h)
            }
            path.close()
        }

        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val featherPx = effect.feather * min(w, h) * 0.05f
        Canvas(mask).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            if (featherPx >= 0.5f) maskFilter = BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
        })

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawBitmap(source, 0f, 0f, null)
            // DST_IN multiplies the image alpha by the mask: outside the subjects alpha becomes 0.
            drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }
        mask.recycle()
        return output
    }

    override fun close() {
        closed = true
        failedAt.clear()
    }

    private companion object {
        const val TAG = "GeminiBgRemoval"
        const val FAILURE_COOLDOWN_MS = 30_000L
    }
}
