package com.termex.replay15.editor.preview.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory audio decoder managing one MediaExtractor + MediaCodec pair.
 * Decodes compressed audio into a normalized 48kHz stereo float PCM ring buffer.
 */
class AudioDecoderSession(
    private val context: Context,
    val uri: String,
    val targetSampleRate: Int = 48000,
    private val diagnostics: AudioDiagnostics? = null
) : AutoCloseable {

    private val isClosed = AtomicBoolean(false)
    private val activeGeneration = AtomicLong(0L)

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var inputEnded = false
    private var outputEnded = false
    private var seekTargetUs = 0L
    @Volatile var lastDecodedPtsUs: Long = Long.MIN_VALUE
        private set

    private var sourceSampleRate = 48000
    private var sourceChannelCount = 2

    private val processorChain = AudioProcessorChain(targetSampleRate, 2)
    private val speedInput = FloatArray(1024 * 16 * 2 + 4)

    // Ring buffer of normalized stereo float samples (interleaved: L, R)
    private val ringCapacity = targetSampleRate * 2 * 2 // 2 seconds of stereo
    private val ringBuffer = FloatArray(ringCapacity)
    private var ringReadPos = 0
    private var ringWritePos = 0
    private var ringAvailable = 0

    private val lock = Any()
    private val info = MediaCodec.BufferInfo()

    init {
        initCodec()
    }

    private fun initCodec() {
        val ext = MediaExtractor()
        ext.setDataSource(context.applicationContext, Uri.parse(uri), null)
        val track = (0 until ext.trackCount).firstOrNull {
            ext.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track found in $uri")

        ext.selectTrack(track)
        val format = ext.getTrackFormat(track)
        sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 48000
        sourceChannelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 2

        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(format, null, null, 0)
        dec.start()

        extractor = ext
        codec = dec
        diagnostics?.decoderCreated(uri)
    }

    fun seekTo(sourceTimeUs: Long, generation: Long) {
        synchronized(lock) {
            activeGeneration.set(generation)
            ringReadPos = 0
            ringWritePos = 0
            ringAvailable = 0
            inputEnded = false
            outputEnded = false
            seekTargetUs = sourceTimeUs.coerceAtLeast(0L)
            processorChain.flush()

            val ext = extractor ?: return
            val dec = codec ?: return

            ext.seekTo(sourceTimeUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            runCatching { dec.flush() }
            diagnostics?.decoderFlushed()
        }
    }

    fun setSpeedAndPitch(speed: Float, preservePitch: Boolean) {
        processorChain.updateParameters(speed, preservePitch)
    }

    /**
     * Reads up to [frameRequested] stereo frames into [output], starting at [offset].
     * Returns the actual number of frames read.
     */
    fun readFrames(output: FloatArray, offset: Int, frameRequested: Int, generation: Long = activeGeneration.get()): Int {
        if (generation != activeGeneration.get()) return 0
        val samplesRequested = frameRequested * 2
        var samplesRead = 0

        synchronized(lock) {
            // Pump decoder until we have enough samples or output ended
            while (generation == activeGeneration.get() && ringAvailable < samplesRequested && !outputEnded && !isClosed.get()) {
                val progress = pumpDecoder()
                if (!progress && ringAvailable == 0) break
            }

            val toCopy = minOf(samplesRequested, ringAvailable)
            for (i in 0 until toCopy) {
                output[offset + i] = ringBuffer[ringReadPos]
                ringReadPos = (ringReadPos + 1) % ringCapacity
            }
            ringAvailable -= toCopy
            samplesRead = toCopy
        }

        // Fill remaining with silence if EOF
        if (samplesRead < samplesRequested) {
            output.fill(0f, offset + samplesRead, offset + samplesRequested)
        }

        return samplesRead / 2
    }

    fun readTimelineFrames(output: FloatArray, frameRequested: Int, speed: Float, preservePitch: Boolean, generation: Long): Int {
        val sourceFrames = (frameRequested * speed).toInt().coerceIn(1, frameRequested * 16)
        val read = readFrames(speedInput, 0, sourceFrames, generation)
        if (read <= 0) { output.fill(0f, 0, frameRequested * 2); return 0 }
        processorChain.updateParameters(speed, preservePitch)
        return processorChain.processFloatPcm(speedInput, read, output, frameRequested)
    }

    private fun pumpDecoder(): Boolean {
        val ext = extractor ?: return false
        val dec = codec ?: return false
        var madeProgress = false

        // 1. Feed input buffer
        if (!inputEnded) {
            val inIndex = dec.dequeueInputBuffer(0)
            if (inIndex >= 0) {
                val inBuffer = dec.getInputBuffer(inIndex)
                if (inBuffer != null) {
                    inBuffer.clear()
                    val size = ext.readSampleData(inBuffer, 0)
                    if (size < 0) {
                        dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        dec.queueInputBuffer(inIndex, 0, size, ext.sampleTime, 0)
                        ext.advance()
                        madeProgress = true
                    }
                }
            }
        }

        // 2. Read output buffer
        val outIndex = dec.dequeueOutputBuffer(info, 0)
        when {
            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val newFormat = dec.outputFormat
                if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sourceSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    sourceChannelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                madeProgress = true
            }
            outIndex >= 0 -> {
                madeProgress = true
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputEnded = true
                }
                val outBuffer = dec.getOutputBuffer(outIndex)
                if (outBuffer != null && info.size > 0) {
                    outBuffer.position(info.offset)
                    outBuffer.limit(info.offset + info.size)
                    lastDecodedPtsUs = info.presentationTimeUs
                    processDecodedPcm(outBuffer, info.presentationTimeUs)
                }
                dec.releaseOutputBuffer(outIndex, false)
            }
        }

        return madeProgress
    }

    private fun processDecodedPcm(buffer: ByteBuffer, presentationTimeUs: Long) {
        val shortBuffer = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val totalShorts = shortBuffer.remaining()
        val inFrames = totalShorts / sourceChannelCount
        if (inFrames <= 0) return

        val channels = sourceChannelCount
        val discardFrames = if (presentationTimeUs < seekTargetUs) {
            (((seekTargetUs - presentationTimeUs) * sourceSampleRate) / 1_000_000L).toInt().coerceIn(0, inFrames)
        } else 0
        val usableFrames = inFrames - discardFrames
        val outputFrames = ((usableFrames.toLong() * targetSampleRate) / sourceSampleRate.coerceAtLeast(1)).toInt()
        var outFrame = 0
        while (outFrame < outputFrames && ringAvailable + 2 <= ringCapacity) {
            val inFrameIdx = discardFrames + ((outFrame.toLong() * sourceSampleRate) / targetSampleRate).toInt().coerceAtMost(usableFrames - 1)
            val left: Float
            val right: Float
            if (channels >= 2) {
                left = shortBuffer.get(inFrameIdx * channels) / 32768f
                right = shortBuffer.get(inFrameIdx * channels + 1) / 32768f
            } else {
                val mono = shortBuffer.get(inFrameIdx) / 32768f; left = mono; right = mono
            }
            ringBuffer[ringWritePos] = left
            ringBuffer[(ringWritePos + 1) % ringCapacity] = right
            ringWritePos = (ringWritePos + 2) % ringCapacity
            ringAvailable += 2

            outFrame++
        }
        if (presentationTimeUs >= seekTargetUs || discardFrames < inFrames) seekTargetUs = 0L
    }

    fun generation(): Long = activeGeneration.get()
    fun isEnded(): Boolean = outputEnded

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        synchronized(lock) {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            codec = null
            extractor?.let { runCatching { it.release() } }
            extractor = null
            processorChain.reset()
        }
    }
}
