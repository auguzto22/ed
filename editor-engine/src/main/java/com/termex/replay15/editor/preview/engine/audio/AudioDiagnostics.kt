package com.termex.replay15.editor.preview.engine.audio

import android.content.Context
import android.util.Log
import com.termex.replay15.editor.core.isEditorDebuggable
import java.util.concurrent.atomic.AtomicLong

class AudioDiagnostics(context: Context) {
    private val enabled = context.isEditorDebuggable()

    val audioTrackCreateCount = AtomicLong()
    val audioTrackFlushCount = AtomicLong()
    val audioDecoderCreateCount = AtomicLong()
    val audioDecoderFlushCount = AtomicLong()
    val audioSeekCount = AtomicLong()
    val audioStaleBlockDropCount = AtomicLong()
    val audioUnderrunCount = AtomicLong()
    val audioQueuedDurationUs = AtomicLong()
    val audioPlayedUs = AtomicLong()
    val videoPresentedUs = AtomicLong()
    val avDriftUs = AtomicLong()
    val activeAudioSourceCount = AtomicLong()

    private val lastSyncLogNs = AtomicLong()

    fun event(value: String) {
        if (enabled) Log.d(TAG, value)
    }

    fun audioTrackCreated() {
        audioTrackCreateCount.incrementAndGet()
        event("AUDIO_TRACK_CREATE")
    }

    fun audioTrackFlushed(generation: Long) {
        audioTrackFlushCount.incrementAndGet()
        event("AUDIO_TRACK_FLUSH generation=$generation")
    }

    fun decoderCreated(uri: String) {
        audioDecoderCreateCount.incrementAndGet()
        event("AUDIO_DECODER_CREATE uri=$uri")
    }

    fun decoderFlushed() {
        audioDecoderFlushCount.incrementAndGet()
        event("AUDIO_DECODER_FLUSH")
    }

    fun seekExecuted(targetUs: Long, generation: Long) {
        audioSeekCount.incrementAndGet()
        event("AUDIO_SEEK_EXECUTE targetUs=$targetUs gen=$generation")
    }

    fun staleBlockDropped() {
        audioStaleBlockDropCount.incrementAndGet()
        event("AUDIO_PCM_STALE_DROP")
    }

    fun underrun(projectUs: Long, queuedMs: Long) {
        audioUnderrunCount.incrementAndGet()
        event("AUDIO_UNDERRUN projectUs=$projectUs queuedMs=$queuedMs")
    }

    fun syncSample(projectUs: Long, playedAudioUs: Long, presentedVideoUs: Long, deltaUs: Long, queuedMs: Long) {
        audioPlayedUs.set(playedAudioUs)
        videoPresentedUs.set(presentedVideoUs)
        avDriftUs.set(deltaUs)
        if (enabled) {
            val now = System.nanoTime()
            val last = lastSyncLogNs.get()
            if (now - last > 500_000_000L && lastSyncLogNs.compareAndSet(last, now)) {
                Log.d(
                    TAG,
                    "AV_SYNC project=%.3fs audioPlayed=%.3fs videoPresented=%.3fs delta=%dms queuedAudio=%dms underruns=%d"
                        .format(
                            projectUs / 1e6,
                            playedAudioUs / 1e6,
                            presentedVideoUs / 1e6,
                            deltaUs / 1000,
                            queuedMs,
                            audioUnderrunCount.get()
                        )
                )
            }
        }
    }

    fun summary(): String =
        "trackCreate=${audioTrackCreateCount.get()} trackFlush=${audioTrackFlushCount.get()} decCreate=${audioDecoderCreateCount.get()} " +
            "decFlush=${audioDecoderFlushCount.get()} seekCount=${audioSeekCount.get()} " +
            "staleDrops=${audioStaleBlockDropCount.get()} underruns=${audioUnderrunCount.get()} " +
            "queuedUs=${audioQueuedDurationUs.get()} activeSources=${activeAudioSourceCount.get()} avDriftUs=${avDriftUs.get()}"

    companion object {
        private const val TAG = "ReclyAudio"
    }
}
