package com.termex.replay15.editor.autoedit

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.termex.replay15.editor.domain.AudioClip
import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.domain.VideoClip
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

class AutoEditCancellation {
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }
    fun check() { if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException("Auto Edit cancelado") }
}

object ClipAnalyzer {
    private const val AUDIO_BIN_US = 100_000L
    private const val FRAME_STEP_US = 500_000L
    private val memoryCache = ConcurrentHashMap<String, ClipAnalysis>()

    fun analyze(
        context: Context,
        clip: VideoClip,
        captionWords: List<TimedWord>,
        cancellation: AutoEditCancellation,
        progress: (AutoEditProgress) -> Unit,
    ): ClipAnalysis {
        val key = listOf(AUTO_EDIT_ALGORITHM_VERSION, clip.uri, clip.sourceUs, clip.inUs, clip.outUs, clip.speed,
            clip.speedCurve.hashCode(), captionWords.hashCode()).joinToString("|")
        memoryCache[key]?.let { return it.copy(clipId = clip.id) }
        progress(AutoEditProgress.Inspecting)
        cancellation.check()
        if (clip.image) return ClipAnalysis(clip.id, clip.durationUs, words = captionWords).also { memoryCache[key] = it }
        val warnings = mutableListOf<String>()
        progress(AutoEditProgress.Audio)
        val audio = runCatching { analyzeAudio(context, clip, cancellation) }.getOrElse {
            warnings += "Audio indisponivel para analise: ${it.message ?: "decoder recusou a faixa"}"
            emptyList()
        }
        progress(AutoEditProgress.Visuals)
        val motion = runCatching { analyzeMotion(context, clip, cancellation) }.getOrElse {
            warnings += "Movimento indisponivel para analise: ${it.message ?: "frames nao puderam ser lidos"}"
            emptyList()
        }
        val result = ClipAnalysis(clip.id, clip.durationUs, audio, motion, captionWords,
            hasAudio = audio.isNotEmpty(), warnings = warnings)
        memoryCache[key] = result
        return result
    }

    /** Reuses imported/manual timed captions as transcript evidence; it never fabricates words. */
    fun wordsFromCaptions(texts: List<TextClip>, clipStartUs: Long, clipDurationUs: Long): List<TimedWord> {
        val clipEnd = clipStartUs + clipDurationUs
        return texts.asSequence()
            .filter { it.startUs < clipEnd && it.endUs > clipStartUs && it.y >= .6f && it.endUs - it.startUs <= 6 * SECOND }
            .sortedBy { it.startUs }
            .flatMap { caption ->
                val tokens = caption.text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
                if (tokens.isEmpty()) return@flatMap emptySequence()
                val start = (caption.startUs - clipStartUs).coerceAtLeast(0)
                val end = (caption.endUs - clipStartUs).coerceAtMost(clipDurationUs)
                val weights = tokens.map { it.length.coerceAtLeast(1) + 1 }
                val total = weights.sum().coerceAtLeast(1)
                var cursor = start
                tokens.mapIndexed { index, token ->
                    val tokenEnd = if (index == tokens.lastIndex) end else cursor + (end - start) * weights[index] / total
                    TimedWord(token, cursor, tokenEnd.coerceAtLeast(cursor + 1), 1f).also { cursor = tokenEnd }
                }.asSequence()
            }.toList()
    }

    fun analyzeAudio(context: Context, clip: AudioClip, cancellation: AutoEditCancellation): List<AudioSample> =
        decodeAudio(context, clip.uri, clip.inUs, clip.outUs, clip.durationUs, cancellation) { sourceUs ->
            (sourceUs - clip.inUs).coerceAtLeast(0L)
        }

    fun analyzeAudio(context: Context, clip: VideoClip, cancellation: AutoEditCancellation): List<AudioSample> =
        decodeAudio(context, clip.uri, clip.inUs, clip.outUs, clip.durationUs, cancellation, clip.timeMap::timelineAt)

    private fun decodeAudio(
        context: Context,
        uri: String,
        inUs: Long,
        outUs: Long,
        durationUs: Long,
        cancellation: AutoEditCancellation,
        sourceToTimeline: (Long) -> Long,
    ): List<AudioSample> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            val bins = linkedMapOf<Long, AudioAccumulator>()
            var inputDone = false
            var outputDone = false
            var outputFormat = format
            while (!outputDone) {
                cancellation.check()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: continue
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0 || sampleTime > outUs) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, sampleTime, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) Unit
                    else -> if (index >= 0) {
                        if (info.size > 0 && info.presentationTimeUs in inUs..outUs) {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null) {
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val localUs = sourceToTimeline(info.presentationTimeUs.coerceIn(inUs, outUs))
                                val bin = (localUs / AUDIO_BIN_US) * AUDIO_BIN_US
                                val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                                val accumulator = bins.getOrPut(bin) { AudioAccumulator() }
                                buffer.order(ByteOrder.LITTLE_ENDIAN)
                                if (encoding == 4) {
                                    while (buffer.remaining() >= 4) accumulator.add(buffer.float.coerceIn(-1f, 1f))
                                } else {
                                    while (buffer.remaining() >= 2) accumulator.add(buffer.short / 32768f)
                                }
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            return bins.map { (start, value) -> AudioSample(start, minOf(durationUs, start + AUDIO_BIN_US), value.rms(), value.peak) }
                .filter { it.endUs > it.startUs }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
        }
    }

    private fun analyzeMotion(context: Context, clip: VideoClip, cancellation: AutoEditCancellation): List<MotionSample> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(clip.uri))
            var previous: IntArray? = null
            val result = mutableListOf<MotionSample>()
            var timelineUs = 0L
            while (timelineUs <= clip.durationUs) {
                cancellation.check()
                val sourceUs = clip.timeMap.sourceAt(timelineUs)
                val bitmap = retriever.getScaledFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST, 160, 90) ?: run {
                    timelineUs += FRAME_STEP_US; continue
                }
                val luma = luma(bitmap)
                bitmap.recycle()
                previous?.let { before ->
                    var total = 0L; var weightedX = 0.0; var weightedY = 0.0
                    for (index in luma.indices step 2) {
                        val delta = abs(luma[index] - before[index])
                        total += delta
                        weightedX += (index % 160) * delta
                        weightedY += (index / 160) * delta
                    }
                    val count = (luma.size / 2).coerceAtLeast(1)
                    val amount = (total.toFloat() / count / 48f).coerceIn(0f, 1f)
                    val focusX = if (total > 0) (weightedX / total / 159.0).toFloat() else .5f
                    val focusY = if (total > 0) (weightedY / total / 89.0).toFloat() else .5f
                    result += MotionSample(timelineUs, amount, focusX.coerceIn(0f, 1f), focusY.coerceIn(0f, 1f))
                }
                previous = luma
                timelineUs += FRAME_STEP_US
            }
            return result
        } finally { retriever.release() }
    }

    private fun luma(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return IntArray(pixels.size) { index ->
            val color = pixels[index]
            ((color shr 16 and 255) * 54 + (color shr 8 and 255) * 183 + (color and 255) * 19) shr 8
        }
    }

    private class AudioAccumulator {
        private var square = 0.0
        private var count = 0L
        var peak = 0f; private set
        fun add(value: Float) { square += value * value; count++; peak = maxOf(peak, abs(value)) }
        fun rms() = if (count == 0L) 0f else sqrt(square / count).toFloat()
    }
}
