package com.termex.replay15.editor.captions

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Decodes a private 16 kHz mono PCM copy. The project's media is never modified. */
class CaptionAudioExtractor(private val context: Context) {
    data class AudioCopy(val rawAudio: File, val processedAudio: File, val durationMs: Long) {
        fun delete() { rawAudio.delete(); processedAudio.delete() }
    }
    fun extract(uri: String, fromUs: Long, toUs: Long, cancelled: () -> Unit = {}): AudioCopy {
        require(toUs > fromUs)
        val extractor = MediaExtractor()
        val raw = File.createTempFile("caption_raw_", ".pcm", context.cacheDir)
        val processed = File.createTempFile("caption_processed_", ".pcm", context.cacheDir)
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("A mídia não contém faixa de áudio")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("Áudio sem formato")
            extractor.selectTrack(track)
            extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var inputDone = false
            var outputDone = false
            var sampleRate = 0
            var channels = 0
            var nextOutputSample = 0L
            var sourceFrame = 0L
            var previous = 0.0
            var emitted = 0L
            val targetSamples = (toUs - fromUs) * 16_000 / 1_000_000
            BufferedOutputStream(FileOutputStream(raw)).use { output ->
                val info = MediaCodec.BufferInfo()
                while (!outputDone) {
                    cancelled()
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0 || extractor.sampleTime >= toUs) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when (index) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val decoded = codec.outputFormat
                            sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT && channels > 0 && sampleRate > 0) { "Formato PCM do decodificador não suportado" }
                        }
                        else -> if (index >= 0) {
                            if (sampleRate == 0) {
                                val decoded = codec.outputFormat
                                sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            val buffer = codec.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val frames = info.size / (channels * 2)
                            for (frame in 0 until frames) {
                                val timeUs = info.presentationTimeUs + frame * 1_000_000L / sampleRate
                                var mono = 0.0
                                repeat(channels) { mono += buffer.short.toDouble() / channels }
                                if (timeUs < fromUs) { previous = mono; continue }
                                if (timeUs >= toUs) break
                                val target = (timeUs - fromUs) * 16_000 / 1_000_000
                                while (nextOutputSample <= target && emitted < targetSamples) {
                                    val phase = if (target == sourceFrame) 1.0 else (nextOutputSample - sourceFrame).toDouble() / max(1, target - sourceFrame)
                                    val sample = (previous + (mono - previous) * phase.coerceIn(0.0, 1.0)).toInt().coerceIn(-32768, 32767)
                                    output.write(sample and 255); output.write((sample ushr 8) and 255)
                                    nextOutputSample++; emitted++
                                }
                                sourceFrame = target; previous = mono
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || emitted >= targetSamples) outputDone = true
                        }
                    }
                }
            }
            // Gentle fixed gain creates a distinct retry signal while retaining the raw recording.
            val bytes = ByteArray(8192)
            raw.inputStream().buffered().use { input -> processed.outputStream().buffered().use { output ->
                while (true) {
                    cancelled()
                    val count = input.read(bytes)
                    if (count < 0) break
                    var i = 0
                    while (i + 1 < count) {
                        val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                        val adjusted = (sample * 1.15).toInt().coerceIn(-32768, 32767)
                        bytes[i] = adjusted.toByte(); bytes[i + 1] = (adjusted shr 8).toByte()
                        i += 2
                    }
                    output.write(bytes, 0, count)
                }
            } }
            return AudioCopy(raw, processed, raw.length() / 32)
        } catch (e: Exception) {
            raw.delete(); processed.delete(); throw e
        } finally {
            runCatching { codec?.stop() }; codec?.release(); extractor.release()
        }
    }
}
