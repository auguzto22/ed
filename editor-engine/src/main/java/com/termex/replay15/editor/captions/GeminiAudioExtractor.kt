package com.termex.replay15.editor.captions

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

data class ExtractedCaptionAudio(
    val file: File,
    val mimeType: String,
    val durationUs: Long,
) {
    fun delete() { file.delete() }
}

/**
 * Produces a Gemini-supported audio file without touching video. AAC in an MP4 is remuxed to M4A;
 * codecs that cannot be muxed are decoded to a streamed WAV as a safe fallback.
 */
class GeminiAudioExtractor(private val context: Context) {
    private val directory: File by lazy {
        File(context.cacheDir, "recly_transcription").apply { mkdirs() }
    }

    fun extract(uri: String, fromUs: Long, toUs: Long, checkCancelled: () -> Unit = {}): ExtractedCaptionAudio {
        require(toUs > fromUs)
        val baseName = "audio_${System.nanoTime()}"
        val remuxed = File(directory, "$baseName.m4a")
        try {
            remux(uri, fromUs, toUs, remuxed, checkCancelled)
            return ExtractedCaptionAudio(remuxed, "audio/m4a", toUs - fromUs)
        } catch (failure: Throwable) {
            remuxed.delete()
            if (failure is java.util.concurrent.CancellationException) throw failure
            CaptionDebugLog.d("GeminiAudio", "AAC remux unavailable; using streamed WAV fallback")
        }

        val pcmCopy = try {
            CaptionAudioExtractor(context).extract(uri, fromUs, toUs, checkCancelled)
        } catch (failure: Throwable) {
            throw AudioExtractionFailed(cause = failure)
        }
        val wav = File(directory, "$baseName.wav")
        try {
            writeWav(pcmCopy.rawAudio, wav, checkCancelled)
            return ExtractedCaptionAudio(wav, "audio/wav", pcmCopy.durationMs * 1_000L)
        } catch (failure: Throwable) {
            wav.delete()
            if (failure is java.util.concurrent.CancellationException) throw failure
            throw AudioExtractionFailed(cause = failure)
        } finally {
            pcmCopy.delete()
        }
    }

    private fun remux(
        uri: String,
        fromUs: Long,
        toUs: Long,
        output: File,
        checkCancelled: () -> Unit,
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudio("A mídia não contém uma faixa de áudio")
            val format = extractor.getTrackFormat(track)
            val codec = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (codec != "audio/mp4a-latm" && codec != "audio/aac") throw UnsupportedAudio()
            extractor.selectTrack(track)
            extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(256 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            var wrote = false
            while (true) {
                checkCancelled()
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0L || sampleTime >= toUs) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) break
                if (sampleTime >= fromUs) {
                    buffer.position(0)
                    buffer.limit(size)
                    info.set(0, size, sampleTime - fromUs, extractor.sampleFlags)
                    muxer.writeSampleData(muxerTrack, buffer, info)
                    wrote = true
                }
                extractor.advance()
            }
            if (!wrote) throw UnsupportedAudio("A faixa de áudio não possui amostras no intervalo selecionado")
        } finally {
            runCatching { muxer?.stop() }
            muxer?.release()
            extractor.release()
        }
    }

    private fun writeWav(pcm: File, wav: File, checkCancelled: () -> Unit) {
        val dataLength = pcm.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(dataLength > 0) { "Áudio vazio" }
        BufferedOutputStream(FileOutputStream(wav)).use { output ->
            fun littleEndian(value: Int) {
                output.write(value and 0xFF); output.write((value ushr 8) and 0xFF)
                output.write((value ushr 16) and 0xFF); output.write((value ushr 24) and 0xFF)
            }
            fun littleEndianShort(value: Int) {
                output.write(value and 0xFF); output.write((value ushr 8) and 0xFF)
            }
            output.write("RIFF".toByteArray()); littleEndian(36 + dataLength)
            output.write("WAVEfmt ".toByteArray()); littleEndian(16); littleEndianShort(1)
            littleEndianShort(1); littleEndian(16_000); littleEndian(32_000); littleEndianShort(2); littleEndianShort(16)
            output.write("data".toByteArray()); littleEndian(dataLength)
            BufferedInputStream(FileInputStream(pcm)).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}
