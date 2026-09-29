package com.termex.replay15.editor.preview.engine.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioOutput(
    private val context: Context,
    private val bufferPolicy: AudioBufferPolicy,
    private val queue: PcmBufferQueue,
    private val clock: AudioPlaybackClock,
    private val diagnostics: AudioDiagnostics,
    private val onUnderrun: () -> Unit = {}
) : AutoCloseable {

    private val isRunning = AtomicBoolean(true)
    private val isPlaying = AtomicBoolean(false)
    private val isFlushing = AtomicBoolean(false)
    private val totalFramesWritten = AtomicLong(0L)
    private val currentGeneration = AtomicLong(0L)
    private val currentProjectRevision = AtomicLong(0L)
    private val ioLock = Any()

    private var audioTrack: AudioTrack? = null
    private var isFloatEncoding = true
    private var outputThread: Thread? = null

    // Fallback 16-bit short buffer if device doesn't support PCM_FLOAT
    private var shortBuffer: ShortArray? = null

    private val deviceCallback = if (Build.VERSION.SDK_INT >= 23) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                clock.invalidateTimestamp()
                diagnostics.event("AUDIO_ROUTE_CHANGED added")
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                clock.invalidateTimestamp()
                diagnostics.event("AUDIO_ROUTE_CHANGED removed")
            }
        }
    } else null

    init {
        initAudioTrack()
        startOutputThread()
        registerDeviceCallback()
    }

    private fun initAudioTrack() {
        val sampleRate = bufferPolicy.sampleRate
        val bufferSize = bufferPolicy.bufferSizeInBytes(diagnostics.audioUnderrunCount.get().toInt())

        // Try PCM_FLOAT first
        try {
            audioTrack = createTrack(sampleRate, AudioFormat.ENCODING_PCM_FLOAT, bufferSize)
            isFloatEncoding = true
        } catch (t: Throwable) {
            diagnostics.event("AUDIO_TRACK_FALLBACK_PCM16: ${t.message}")
            // Fallback to PCM_16BIT
            try {
                audioTrack = createTrack(sampleRate, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                isFloatEncoding = false
            } catch (t2: Throwable) {
                diagnostics.event("AUDIO_TRACK_INIT_FAILED: ${t2.message}")
            }
        }
        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) diagnostics.audioTrackCreated()
    }

    private fun createTrack(sampleRate: Int, encoding: Int, bufferSize: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(encoding)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (Build.VERSION.SDK_INT >= 26) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()
    }

    private fun startOutputThread() {
        outputThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (isRunning.get()) {
                val track = audioTrack ?: break

                if (!isPlaying.get() || isFlushing.get()) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                val block = queue.poll(10L)
                diagnostics.audioQueuedDurationUs.set(queue.queuedDurationUs())
                if (block == null) {
                    if (isPlaying.get() && !isFlushing.get()) {
                        val pos = clock.positionUs(track) ?: 0L
                        val hardwareUnderruns = if (Build.VERSION.SDK_INT >= 24) track.underrunCount.toLong() else 0L
                        if (hardwareUnderruns > diagnostics.audioUnderrunCount.get()) {
                            diagnostics.underrun(pos, queue.queuedDurationUs() / 1000L)
                            onUnderrun()
                        }
                    }
                    continue
                }

                if (block.generation != currentGeneration.get() || block.projectRevision != currentProjectRevision.get()) {
                    diagnostics.staleBlockDropped()
                    PcmBlock.release(block)
                    continue
                }

                var releaseBlock = true
                synchronized(ioLock) {
                    if (block.generation == currentGeneration.get() && block.projectRevision == currentProjectRevision.get() && isPlaying.get() && !isFlushing.get()) {
                        if (isFloatEncoding) {
                            var offset = 0
                            while (offset < block.sampleCount && block.generation == currentGeneration.get()) {
                                val written = track.write(block.samples, offset, block.sampleCount - offset, AudioTrack.WRITE_BLOCKING)
                                if (written <= 0) break
                                offset += written; totalFramesWritten.addAndGet((written / 2).toLong())
                            }
                        } else {
                            val sampleCount = block.sampleCount
                            var shorts = shortBuffer
                            if (shorts == null || shorts.size < sampleCount) shorts = ShortArray(sampleCount).also { shortBuffer = it }
                            for (i in 0 until sampleCount) shorts[i] = (block.samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            var offset = 0
                            while (offset < sampleCount && block.generation == currentGeneration.get()) {
                                val written = track.write(shorts, offset, sampleCount - offset, AudioTrack.WRITE_BLOCKING)
                                if (written <= 0) break
                                offset += written; totalFramesWritten.addAndGet((written / 2).toLong())
                            }
                        }
                    } else if (block.generation == currentGeneration.get() && block.projectRevision == currentProjectRevision.get() && !isFlushing.get()) {
                        releaseBlock = !queue.offerFirst(block)
                    }
                }

                if (releaseBlock) PcmBlock.release(block)
                diagnostics.audioQueuedDurationUs.set(queue.queuedDurationUs())
            }
        }, "ReclyAudioOutput").apply {
            isDaemon = true
            start()
        }
    }

    fun play() {
        if (isPlaying.compareAndSet(false, true)) {
            val track = audioTrack
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    track.play()
                    diagnostics.event("AUDIO_PLAY")
                } catch (t: Throwable) {
                    diagnostics.event("AUDIO_PLAY_ERROR ${t.message}")
                }
            }
        }
    }

    fun pause() {
        if (isPlaying.compareAndSet(true, false)) {
            val track = audioTrack
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    track.pause()
                    diagnostics.event("AUDIO_PAUSE")
                } catch (t: Throwable) {
                    diagnostics.event("AUDIO_PAUSE_ERROR ${t.message}")
                }
            }
        }
    }

    fun flush(generation: Long) {
        currentGeneration.set(generation) // invalidate a polled block before waiting for the I/O lock
        isFlushing.set(true)
        val track = audioTrack
        synchronized(ioLock) {
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    track.pause()
                    track.flush()
                    diagnostics.audioTrackFlushed(generation)
                } catch (t: Throwable) {
                    diagnostics.event("AUDIO_FLUSH_ERROR ${t.message}")
                }
            }
        }
        totalFramesWritten.set(0L)
        isFlushing.set(false)
    }

    fun getPlaybackPositionUs(): Long? {
        return clock.positionUs(audioTrack)
    }

    fun getTrack(): AudioTrack? = audioTrack
    fun playbackHeadFrames(): Long = audioTrack?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: 0L
    fun generation(): Long = currentGeneration.get()
    fun invalidateGeneration(generation: Long) { currentGeneration.set(generation) }
    fun setProjectRevision(revision: Long) { currentProjectRevision.set(revision) }

    private fun registerDeviceCallback() {
        if (Build.VERSION.SDK_INT >= 23 && deviceCallback != null) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.registerAudioDeviceCallback(deviceCallback, null)
        }
    }

    private fun unregisterDeviceCallback() {
        if (Build.VERSION.SDK_INT >= 23 && deviceCallback != null) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        }
    }

    override fun close() {
        isRunning.set(false)
        isPlaying.set(false)
        outputThread?.interrupt()
        unregisterDeviceCallback()
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching {
                track.pause()
                track.flush()
                track.release()
            }
            diagnostics.event("AUDIO_TRACK_RELEASE")
        }
    }
}
