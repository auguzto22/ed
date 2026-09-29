package com.termex.replay15.editor.audio

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.*
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors

class WaveformCache(context: Context, private val changed: () -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val directory = File(app.cacheDir, "editor-waveforms").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = mutableSetOf<String>(); private val failed = mutableSetOf<String>()
    @Volatile private var closed = false
    private val cache = object : LruCache<String, WaveformSamples>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: WaveformSamples) = value.peaks.size * 4
    }
    fun get(uri: String, durationUs: Long): WaveformSamples? {
        val key = "v1:$uri:$durationUs"
        cache.get(key)?.let { return it }
        if (closed || key in pending || key in failed || pending.size >= 4) return null
        pending += key
        worker.execute {
            val diskKey = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val file = File(directory, "$diskKey.wave")
            val result = runCatching {
                val disk = runCatching { DataInputStream(file.inputStream().buffered()).use { input ->
                    require(input.readLong() == durationUs); val count = input.readInt(); require(count in 1..60_000)
                    WaveformSamples(durationUs, FloatArray(count) { input.readFloat().also { require(it in 0f..1f) } })
                } }.getOrNull()
                disk ?: decode(uri, durationUs)?.also { wave ->
                    val temporary = File(directory, "$diskKey.tmp")
                    try { DataOutputStream(temporary.outputStream().buffered()).use { output ->
                        output.writeLong(durationUs); output.writeInt(wave.peaks.size); wave.peaks.forEach(output::writeFloat)
                    }; check(temporary.renameTo(file)) } finally { temporary.delete() }
                }
            }.getOrNull()
            main.post {
                pending -= key
                if (!closed) { if (result != null) cache.put(key, result) else failed += key; changed() }
            }
        }
        return null
    }
    private fun decode(uri: String, durationUs: Long): WaveformSamples? {
        val extractor = MediaExtractor(); var codec: MediaCodec? = null
        try {
            extractor.setDataSource(app, Uri.parse(uri), null)
            val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(index); val format = extractor.getTrackFormat(index)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME))); codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            val info = MediaCodec.BufferInfo(); val result = WaveformSamples(durationUs)
            var inputEnded = false; var outputEnded = false
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(rate in 1..384_000 && channels in 1..32)
            var encoding = AudioFormat.ENCODING_PCM_16BIT; var lastOutput = System.nanoTime()
            while (!outputEnded && !closed && !Thread.currentThread().isInterrupted) {
                if (!inputEnded) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(input)); val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) { decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { decoder.queueInputBuffer(input, 0, count, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = decoder.outputFormat; rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(rate in 1..384_000 && channels in 1..32)
                    encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                } else if (output >= 0) {
                    lastOutput = System.nanoTime()
                    val bytes = decoder.getOutputBuffer(output)?.order(ByteOrder.nativeOrder())
                    if (bytes != null && info.size > 0) {
                        bytes.position(info.offset); bytes.limit(info.offset + info.size)
                        val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2; var frame = 0L
                        while (bytes.remaining() >= sampleBytes * channels) {
                            var peak = 0f
                            repeat(channels) { val value = if (sampleBytes == 4) bytes.float else bytes.short / 32768f; peak = maxOf(peak, kotlin.math.abs(value)) }
                            result.add(info.presentationTimeUs + frame * 1_000_000 / rate, peak); frame++
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(output, false)
                }
                require(System.nanoTime() - lastOutput < 15_000_000_000L) { "Audio nao responde" }
            }
            return result.takeIf { outputEnded && !closed }
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
    fun clear() { cache.evictAll(); failed.clear() }
    override fun close() { closed = true; worker.shutdownNow(); main.removeCallbacksAndMessages(null); cache.evictAll() }
}
