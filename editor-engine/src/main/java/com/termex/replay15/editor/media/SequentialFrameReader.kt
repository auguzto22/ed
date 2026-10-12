package com.termex.replay15.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes a video once, in presentation order, and hands out small ARGB frames for an ascending
 * list of source timestamps.
 *
 * `MediaMetadataRetriever.getScaledFrameAtTime(..., OPTION_CLOSEST, ...)` seeks back to the
 * previous sync frame and decodes forward again for every single request, so sampling N
 * timestamps cost N GOP decodes. Analysis (Auto Edit, Reference Style) and motion tracking sample
 * hundreds of timestamps, which made them take minutes. Here each frame is decoded at most once,
 * the codec runs in best-effort (non-realtime) mode, and long gaps between requests are skipped
 * with a seek.
 *
 * Frames are display-oriented (the track rotation is applied, like MediaMetadataRetriever does)
 * and scaled to fit inside [maxWidth] x [maxHeight] keeping the aspect ratio. Every bitmap handed
 * to `consume` is owned by the consumer.
 */
object SequentialFrameReader {
    /** Gap after which seeking to the next sync frame is cheaper than decoding through. */
    private const val SKIP_GAP_US = 2_500_000L
    private const val STALL_TIMEOUT_NS = 4_000_000_000L

    /**
     * @param timesUs ascending source timestamps.
     * @param cancel throws to abort (it is called between codec operations).
     * @return how many leading entries of [timesUs] were handled (delivered, or past the end of
     *   the stream). A value lower than `timesUs.size` means the fast path failed at that index;
     *   the caller may fall back to MediaMetadataRetriever for the remaining timestamps.
     */
    fun read(
        context: Context,
        uri: String,
        timesUs: List<Long>,
        maxWidth: Int,
        maxHeight: Int,
        cancel: () -> Unit = {},
        consume: (requestedUs: Long, bitmap: Bitmap) -> Unit,
    ): Int {
        if (timesUs.isEmpty()) return 0
        require(maxWidth > 0 && maxHeight > 0)
        var handled = 0
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context.applicationContext, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return 0
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val frameIntervalUs = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                runCatching { (1_000_000f / format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1)).toLong() }
                    .getOrDefault(33_333L)
            } else 33_333L
            // A request is served by the first frame that is not more than half a frame early.
            val toleranceUs = (frameIntervalUs / 2).coerceIn(4_000L, 25_000L)
            val decoder = openDecoder(format) ?: return 0
            codec = decoder
            val info = MediaCodec.BufferInfo()
            extractor.seekTo(timesUs[0], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var inputDone = false
            var lastProgressNs = System.nanoTime()
            val lastRequestUs = timesUs.last()
            while (handled < timesUs.size) {
                cancel()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(2_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index))
                        val sampleTime = extractor.sampleTime
                        // Nothing after the last request (plus one GOP of reordering) is needed.
                        val size = if (sampleTime < 0 || sampleTime > lastRequestUs + SKIP_GAP_US) -1
                            else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 5_000)
                if (index < 0) {
                    check(System.nanoTime() - lastProgressNs < STALL_TIMEOUT_NS) { "Decoder stalled" }
                    continue
                }
                lastProgressNs = System.nanoTime()
                val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = info.presentationTimeUs
                if (info.size > 0 && pts + toleranceUs >= timesUs[handled]) {
                    val image = decoder.getOutputImage(index)
                    val bitmap = image?.let { runCatching { toBitmap(it, rotation, maxWidth, maxHeight) }.getOrNull() }
                    image?.close()
                    if (bitmap == null) {
                        decoder.releaseOutputBuffer(index, false)
                        return handled
                    }
                    var delivered = false
                    while (handled < timesUs.size && pts + toleranceUs >= timesUs[handled]) {
                        val last = handled + 1 >= timesUs.size || pts + toleranceUs < timesUs[handled + 1]
                        val owned = if (last) bitmap.also { delivered = true }
                            else bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        consume(timesUs[handled], owned)
                        handled++
                    }
                    if (!delivered) bitmap.recycle()
                }
                decoder.releaseOutputBuffer(index, false)
                if (endOfStream) break
                // Jump over long gaps instead of decoding every frame in between.
                // Restarting from the sync frame before the next request (flush discards the few
                // frames in flight, none of which can reach a request that is this far ahead).
                if (handled < timesUs.size && !inputDone && timesUs[handled] - pts > SKIP_GAP_US) {
                    extractor.seekTo(timesUs[handled], MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    decoder.flush()
                }
            }
            // Requests after the last decodable frame have no image, exactly like a null result
            // from MediaMetadataRetriever.
            return timesUs.size
        } catch (cancelled: InterruptedException) {
            throw cancelled
        } catch (failure: Exception) {
            // Platform/codec failure: report progress so the caller can fall back.
            return handled
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun openDecoder(source: MediaFormat): MediaCodec? {
        val mime = source.getString(MediaFormat.KEY_MIME) ?: return null
        val names = buildList {
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(source) }.getOrNull()?.let(::add)
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
                .sortedBy { !it.isHardwareAccelerated }
                .forEach { if (it.name !in this) add(it.name) }
        }
        for (name in names) {
            // Try once with best-effort hints, then plain, before moving to another codec.
            for (hints in listOf(true, false)) {
                var codec: MediaCodec? = null
                try {
                    val format = MediaFormat(source)
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    if (hints) {
                        format.setInteger(MediaFormat.KEY_PRIORITY, 1)
                        format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                    }
                    codec = MediaCodec.createByCodecName(name)
                    codec.configure(format, null, null, 0)
                    codec.start()
                    return codec
                } catch (_: Exception) {
                    runCatching { codec?.release() }
                }
            }
        }
        return null
    }

    /** YUV_420_888 -> display-oriented ARGB, 2x2 supersampled luma, fitted inside the bounds. */
    internal fun toBitmap(image: Image, rotation: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < 3) return null
        val crop = image.cropRect
        val bufferW = crop.width().coerceAtLeast(1)
        val bufferH = crop.height().coerceAtLeast(1)
        val quarter = ((rotation % 360) + 360) % 360 / 90
        val displayW = if (quarter % 2 == 1) bufferH else bufferW
        val displayH = if (quarter % 2 == 1) bufferW else bufferH
        val scale = min(maxWidth.toFloat() / displayW, maxHeight.toFloat() / displayH)
        val outW = max(1, (displayW * scale).roundToInt())
        val outH = max(1, (displayH * scale).roundToInt())
        val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride; val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride; val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride; val vPix = vPlane.pixelStride
        val yLimit = yBuf.limit() - 1; val uLimit = uBuf.limit() - 1; val vLimit = vBuf.limit() - 1
        val pixels = IntArray(outW * outH)
        fun bufferX(nx: Float, ny: Float): Float = when (quarter) { 1 -> ny; 2 -> 1f - nx; 3 -> 1f - ny; else -> nx }
        fun bufferY(nx: Float, ny: Float): Float = when (quarter) { 1 -> 1f - nx; 2 -> 1f - ny; 3 -> nx; else -> ny }
        fun luma(nx: Float, ny: Float): Int {
            val bx = (crop.left + bufferX(nx, ny) * bufferW).toInt().coerceIn(crop.left, crop.right - 1)
            val by = (crop.top + bufferY(nx, ny) * bufferH).toInt().coerceIn(crop.top, crop.bottom - 1)
            return yBuf.get((by * yRow + bx * yPix).coerceIn(0, yLimit)).toInt() and 255
        }
        for (oy in 0 until outH) {
            for (ox in 0 until outW) {
                val x0 = ox.toFloat() / outW; val x1 = (ox + 1f) / outW
                val y0 = oy.toFloat() / outH; val y1 = (oy + 1f) / outH
                val qx0 = x0 + (x1 - x0) * .25f; val qx1 = x0 + (x1 - x0) * .75f
                val qy0 = y0 + (y1 - y0) * .25f; val qy1 = y0 + (y1 - y0) * .75f
                val y = (luma(qx0, qy0) + luma(qx1, qy0) + luma(qx0, qy1) + luma(qx1, qy1)) / 4
                val cx = (x0 + x1) * .5f; val cy = (y0 + y1) * .5f
                val bx = ((crop.left + bufferX(cx, cy) * bufferW).toInt().coerceIn(crop.left, crop.right - 1)) / 2
                val by = ((crop.top + bufferY(cx, cy) * bufferH).toInt().coerceIn(crop.top, crop.bottom - 1)) / 2
                val u = (uBuf.get((by * uRow + bx * uPix).coerceIn(0, uLimit)).toInt() and 255) - 128
                val v = (vBuf.get((by * vRow + bx * vPix).coerceIn(0, vLimit)).toInt() and 255) - 128
                // BT.601 limited range, the default for camera/phone footage.
                val c = (y - 16).coerceAtLeast(0) * 1192
                val r = ((c + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((c - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((c + 2066 * u) shr 10).coerceIn(0, 255)
                pixels[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }
}
