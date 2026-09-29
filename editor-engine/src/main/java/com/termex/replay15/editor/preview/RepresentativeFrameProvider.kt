package com.termex.replay15.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.termex.replay15.editor.domain.CropRect
import com.termex.replay15.editor.domain.VideoClip
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object RepresentativeFrameProvider {
    private const val TAG = "ReclyFrameProvider"
    const val DEFAULT_MAX_DIMENSION = 160

    /**
     * Extracts a compact, properly oriented and cropped BaseFrame from the given clip.
     * Guaranteed to run safely and never throw or crash if media is inaccessible.
     */
    fun extractBaseFrame(
        context: Context,
        clip: VideoClip,
        requestedTimeUs: Long,
        maxDimension: Int = DEFAULT_MAX_DIMENSION,
    ): Bitmap? {
        val app = context.applicationContext
        val uri = runCatching { Uri.parse(clip.uri) }.getOrNull() ?: return null

        return runCatching {
            if (clip.image) {
                extractImageFrame(app, uri, clip, maxDimension)
            } else {
                extractVideoFrame(app, uri, clip, requestedTimeUs, maxDimension)
            }
        }.fold(
            onSuccess = { raw ->
                if (raw == null) return null
                try {
                    applyTransforms(raw, clip)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply transforms to base frame for ${clip.id}", e)
                    raw
                }
            },
            onFailure = { e ->
                Log.w(TAG, "Failed to extract base frame for clip ${clip.id} at $requestedTimeUs", e)
                null
            },
        )
    }

    private fun calculateTargetDimensions(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
        val w = if (width > 0) width else maxDim
        val h = if (height > 0) height else maxDim
        val scale = min(maxDim.toFloat() / max(w, 1), maxDim.toFloat() / max(h, 1)).coerceAtMost(1f)
        val targetW = (w * scale).roundToInt().coerceAtLeast(16)
        val targetH = (h * scale).roundToInt().coerceAtLeast(16)
        return targetW to targetH
    }

    private fun extractImageFrame(
        context: Context,
        uri: Uri,
        clip: VideoClip,
        maxDimension: Int,
    ): Bitmap? {
        val (targetW, targetH) = calculateTargetDimensions(clip.width, clip.height, maxDimension)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = min(maxDimension.toFloat() / max(info.size.width, 1), maxDimension.toFloat() / max(info.size.height, 1))
                val tw = (info.size.width * scale).roundToInt().coerceAtLeast(16)
                val th = (info.size.height * scale).roundToInt().coerceAtLeast(16)
                decoder.setTargetSize(tw, th)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= targetW && bounds.outHeight / (sampleSize * 2) >= targetH) {
                sampleSize *= 2
            }
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }
        }
    }

    private fun extractVideoFrame(
        context: Context,
        uri: Uri,
        clip: VideoClip,
        requestedTimeUs: Long,
        maxDimension: Int,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val (targetW, targetH) = calculateTargetDimensions(clip.width, clip.height, maxDimension)

            // Safe bounded time inside the clip
            val boundedTimeUs = requestedTimeUs.coerceIn(0L, max(0L, clip.sourceUs - 10_000L))

            // Primary attempt: target time with closest frame
            var bitmap = retriever.getScaledFrameAtTime(
                boundedTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                targetW,
                targetH,
            )

            // Fallback 1: closest sync frame at requested time
            if (bitmap == null) {
                bitmap = retriever.getScaledFrameAtTime(
                    boundedTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetW,
                    targetH,
                )
            }

            // Fallback 2: middle of clip
            if (bitmap == null) {
                val middleUs = (clip.inUs + clip.outUs) / 2
                bitmap = retriever.getScaledFrameAtTime(
                    middleUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetW,
                    targetH,
                )
            }

            // Fallback 3: beginning of clip
            if (bitmap == null) {
                bitmap = retriever.getScaledFrameAtTime(
                    clip.inUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetW,
                    targetH,
                )
            }

            return bitmap
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }

    private fun applyTransforms(bitmap: Bitmap, clip: VideoClip): Bitmap {
        var current = bitmap

        // Handle rotation & flip if needed
        val needsRotation = clip.rotation != 0
        val needsFlip = clip.flip
        if (needsRotation || needsFlip) {
            val matrix = Matrix()
            if (needsFlip) matrix.postScale(-1f, 1f)
            if (needsRotation) matrix.postRotate(clip.rotation.toFloat())

            val transformed = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            if (transformed !== current && current !== bitmap) {
                current.recycle()
            }
            current = transformed
        }

        // Handle crop if non-default
        val crop = clip.crop
        if (crop != CropRect()) {
            val left = (crop.left * current.width).roundToInt().coerceIn(0, current.width - 1)
            val top = (crop.top * current.height).roundToInt().coerceIn(0, current.height - 1)
            val right = (crop.right * current.width).roundToInt().coerceIn(left + 1, current.width)
            val bottom = (crop.bottom * current.height).roundToInt().coerceIn(top + 1, current.height)
            val cropW = right - left
            val cropH = bottom - top

            if (cropW > 4 && cropH > 4) {
                val cropped = Bitmap.createBitmap(current, left, top, cropW, cropH)
                if (cropped !== current && current !== bitmap) {
                    current.recycle()
                }
                current = cropped
            }
        }

        return current
    }
}
