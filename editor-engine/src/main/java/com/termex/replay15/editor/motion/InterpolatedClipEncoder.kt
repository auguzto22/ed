package com.termex.replay15.editor.motion

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.util.Log
import com.termex.replay15.editor.stabilize.LumaFrame
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders a slowed clip into a new file whose frames are interpolated.
 *
 * The original media is never modified. The result is a preview-only artifact; export always
 * reads the untouched source, and the preview's audio is unaffected because it is read from
 * the separate audio track rather than from this file.
 *
 * Frames are decoded one at a time, the missing ones are synthesized by
 * [FrameInterpolator] and pushed to the encoder as YUV with an explicit presentation time.
 * Going through a YUV buffer rather than a canvas is what keeps the timing exact: a canvas
 * on the encoder's input surface would stamp frames with wall-clock time.
 */
class InterpolatedClipEncoder(
    private val request: SmoothSlowMoRequest,
    private val width: Int,
    private val height: Int,
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
) : AutoCloseable {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    private val frameIntervalUs: Long get() = 1_000_000L / request.outputFps
    private val sourceIntervalUs: Long get() = (1_000_000f / request.sourceFps).toLong().coerceAtLeast(1L)

    fun encodeTo(output: File, bitrate: Int = DEFAULT_BITRATE): Boolean {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(request.inputUri)
            startEncoder(output, bitrate)
            val produced = renderFrames(retriever)
            if (!produced) return false
            finishEncoder()
            return true
        } catch (failure: Throwable) {
            Log.w(TAG, "Interpolated render failed for ${request.clipId}", failure)
            return false
        } finally {
            release()
            runCatching { retriever.release() }
        }
    }

    private fun startEncoder(output: File, bitrate: Int) {
        val encoder = MediaCodec.createEncoderByType(MIME_AVC)
        val colorFormat = selectColorFormat(encoder.codecInfo)
        val format = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, request.outputFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    private var semiPlanar = false

    private fun renderFrames(retriever: MediaMetadataRetriever): Boolean {
        var previous: Bitmap? = null
        var previousLuma = LumaFrame(2, 2, IntArray(4))
        var sourceTimeUs = request.inUs
        var emitted = 0

        while (sourceTimeUs < request.outUs) {
            if (cancelled.get()) return false
            val current = retriever.getFrameAtTime(sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: break
            if (current.width != width || current.height != height) break

            val currentLuma = FrameInterpolator.luminanceOf(pixelsOf(current), current.width, current.height)
            val velocity = FrameInterpolator.measureVelocity(previousLuma, currentLuma)
            val held = previous
            val heldPixels = held?.let { pixelsOf(it) }

            if (held != null && heldPixels != null) {
                val (velocityX, velocityY) = velocity ?: (0f to 0f)
                val steps = stepsBetweenFrames(velocity)
                for (step in 1 until steps) {
                    val pixels = FrameInterpolator.interpolateArgb(
                        heldPixels, pixelsOf(current), held.width, held.height,
                        velocityX, velocityY, step.toFloat() / steps,
                    )
                    if (!queueFrame(pixels, held.width, held.height)) return false
                    emitted++
                }
            }

            if (!queueFrame(pixelsOf(current), current.width, current.height)) return false
            emitted++

            held?.recycle()
            previous = current
            previousLuma = currentLuma
            sourceTimeUs += sourceIntervalUs
        }

        if (previous == null) return false
        previous.recycle()
        return emitted > 0
    }

    /**
     * A shot with nothing moving has no gap worth filling, and a frame the estimator could
     * not measure is safer duplicated than warped by a guess.
     */
    private fun stepsBetweenFrames(velocity: Pair<Float, Float>?): Int {
        if (velocity == null) return 1
        if (kotlin.math.abs(velocity.first) < MIN_MOTION_PX && kotlin.math.abs(velocity.second) < MIN_MOTION_PX) return 1
        return (1f / request.slowestSpeed).toInt().coerceIn(1, request.outputFps)
    }

    /** Converts and queues one frame, then makes room in the encoder if it is running behind. */
    private fun queueFrame(pixels: IntArray, frameWidth: Int, frameHeight: Int): Boolean {
        val encoder = codec ?: return false
        writeYuv(pixels, frameWidth, frameHeight)
        drain(false)
        while (true) {
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = encoder.getInputBuffer(index) ?: return false
                input.clear()
                if (input.capacity() < yuv!!.size) return false
                input.put(yuv!!)
                encoder.queueInputBuffer(index, 0, yuv!!.size, presentationUs, 0)
                presentationUs += frameIntervalUs
                return true
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
        }
    }

    /** `Bitmap.pixels` is not a Kotlin property getter, so the array is pulled explicitly. */
    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private var yuv: ByteArray? = null

    private fun lumaBufferBacking(): ByteArray = yuv ?: ByteArray(width * height * 3 / 2).also { yuv = it }

    /** ARGB to I420, or to NV12 when the encoder asked for a semi-planar layout. */
    private fun writeYuv(pixels: IntArray, frameWidth: Int, frameHeight: Int) {
        val out = lumaBufferBacking()
        val frameSize = frameWidth * frameHeight
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        var uvIndex = frameSize
        for (y in 0 until frameHeight) {
            for (x in 0 until frameWidth) {
                val color = pixels[y * frameWidth + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val luma = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = luma.coerceIn(0, 255).toByte()
                // Chroma is sampled once per 2x2 block, which is exactly what 4:2:0 stores.
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (semiPlanar) {
                        out[uvIndex++] = u.coerceIn(0, 255).toByte()
                        out[uvIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        out[uIndex++] = u.coerceIn(0, 255).toByte()
                        out[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }

    private fun finishEncoder(): Boolean {
        val encoder = codec ?: return false
        // An empty input buffer with the end-of-stream flag is how the encoder is told to drain.
        while (true) {
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
        }
        drain(true)
        return true
    }

    private fun drain(untilEnd: Boolean) {
        val encoder = codec ?: return
        val muxerInstance = muxer ?: return
        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (untilEnd && isEndOfStreamSignalled) return
                    return
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxerInstance.addTrack(encoder.outputFormat)
                        muxerInstance.start()
                        muxerStarted = true
                    }
                }
                status >= 0 -> {
                    val buffer = encoder.getOutputBuffer(status)
                    if (buffer != null && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        muxerStarted && bufferInfo.size > 0
                    ) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxerInstance.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(status, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isEndOfStreamSignalled = true
                        if (untilEnd) return
                    }
                }
            }
        }
    }

    private var isEndOfStreamSignalled = false

    private fun selectColorFormat(info: MediaCodecInfo): Int {
        val capabilities = info.getCapabilitiesForType(MIME_AVC)
        val planar = capabilities.colorFormats.firstOrNull {
            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }
        if (planar != null) return planar
        return capabilities.colorFormats.firstOrNull {
            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        } ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }

    private fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        codec = null
        muxer = null
        muxerStarted = false
    }

    override fun close() = release()

    private companion object {
        const val TAG = "ReclyInterpolator"
        const val MIME_AVC = "video/avc"
        const val DEFAULT_BITRATE = 8_000_000
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val TIMEOUT_US = 10_000L
        const val MIN_MOTION_PX = 1.5f
    }
}
