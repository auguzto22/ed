package com.termex.replay15.editor.reference

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

data class ReferenceAudioFrame(val startUs: Long, val endUs: Long, val rms: Float, val peak: Float, val spectralFlux: Float)

object ReferenceAudioAnalyzer {
    private const val BIN_US = 20_000L

    fun analyze(context: Context, info: ReferenceMediaInfo, cancellation: ReferenceAnalysisCancellation): List<ReferenceAudioFrame> {
        if (!info.hasAudio) return emptyList()
        val extractor = MediaExtractor(); var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(info.uri), null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return emptyList()
            extractor.selectTrack(track); val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            codec = MediaCodec.createDecoderByType(mime); codec.configure(format, null, null, 0); codec.start()
            val bins = linkedMapOf<Long, Accumulator>(); val infoBuffer = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false; var outputFormat = format
            while (!outputDone) {
                cancellation.check()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, extractor.sampleFlags); extractor.advance() }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(infoBuffer, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && infoBuffer.size > 0) {
                            buffer.position(infoBuffer.offset); buffer.limit(infoBuffer.offset + infoBuffer.size); buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                            val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                            val bytesPerSample = if (encoding == 4) 4 else 2; var sampleIndex = 0L
                            while (buffer.remaining() >= bytesPerSample) {
                                val value = if (encoding == 4) buffer.float.coerceIn(-1f, 1f) else buffer.short / 32768f
                                val timeUs = infoBuffer.presentationTimeUs + sampleIndex * 1_000_000L / sampleRate / channels
                                val bin = (timeUs / BIN_US) * BIN_US; bins.getOrPut(bin) { Accumulator() }.add(value); sampleIndex++
                            }
                        }
                        outputDone = infoBuffer.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            return bins.map { (start, accumulator) -> ReferenceAudioFrame(start, minOf(info.durationUs, start + BIN_US), accumulator.rms(), accumulator.peak, accumulator.flux()) }
                .filter { it.endUs > it.startUs }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
        }
    }

    fun beats(frames: List<ReferenceAudioFrame>): List<BeatEvent> {
        if (frames.size < 8) return emptyList()
        val novelty = frames.mapIndexed { index, frame ->
            val previous = frames.getOrNull(index - 1)
            (frame.spectralFlux * .55f + (frame.rms - (previous?.rms ?: frame.rms)).coerceAtLeast(0f) * 2.2f + frame.peak * .12f)
        }
        val candidates = mutableListOf<BeatEvent>()
        for (i in 2 until frames.size - 2) {
            val local = novelty.subList((i - 24).coerceAtLeast(0), (i + 25).coerceAtMost(novelty.size))
            val threshold = local.median() + local.map { abs(it - local.median()) }.median() * 2.4f
            val value = novelty[i]
            if (value > threshold.coerceAtLeast(.035f) && value >= novelty[i - 1] && value >= novelty[i + 1]) {
                val strength = (value / (threshold + .001f)).coerceIn(1f, 4f) / 4f
                candidates += BeatEvent((frames[i].startUs + frames[i].endUs) / 2, strength, (.48f + strength * .45f).coerceAtMost(.94f))
            }
        }
        val intervals = candidates.zipWithNext().map { (a, b) -> b.timeUs - a.timeUs }.filter { it in 220_000L..1_500_000L }
        val period = intervals.medianLong()
        return candidates.filterIndexed { index, beat ->
            if (period == 0L || candidates.size < 4) true else {
                val neighbor = listOfNotNull(candidates.getOrNull(index - 1), candidates.getOrNull(index + 1))
                neighbor.any { abs(abs(it.timeUs - beat.timeUs) - period) <= period * .28 } || beat.strength > .68f
            }
        }.fold(mutableListOf()) { result, beat ->
            if (result.lastOrNull()?.let { beat.timeUs - it.timeUs < 140_000L } == true) {
                if (beat.strength > result.last().strength) result[result.lastIndex] = beat
            } else result += beat
            result
        }
    }

    private class Accumulator {
        private var square = 0.0; private var high = 0.0; private var count = 0L; private var previous = 0f
        var peak = 0f; private set
        fun add(value: Float) { square += value * value; high += abs(value - previous); previous = value; peak = maxOf(peak, abs(value)); count++ }
        fun rms() = if (count == 0L) 0f else sqrt(square / count).toFloat()
        fun flux() = if (count == 0L) 0f else (high / count).toFloat()
    }
}
