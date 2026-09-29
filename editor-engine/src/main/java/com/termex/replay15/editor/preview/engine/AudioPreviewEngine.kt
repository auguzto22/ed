package com.termex.replay15.editor.preview.engine

import android.content.Context
import com.termex.replay15.editor.preview.engine.audio.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Persistent audio decoder/mixer/output pipeline driven only by project time. */
class AudioPreviewEngine(context: Context, private val onError: (Throwable) -> Unit) : AutoCloseable {
    private data class Timeline(val revision: Long, val resolver: ActiveClipResolver)
    private data class Seek(val projectTimeUs: Long, val generation: Long)

    val diagnostics = AudioDiagnostics(context)
    private val clock = AudioPlaybackClock(SAMPLE_RATE)
    private val pcmQueue = PcmBufferQueue(MAX_QUEUE_US)
    private val decoderManager = AudioDecoderManager(context, diagnostics)
    private val mixer = AudioMixer(SAMPLE_RATE, BLOCK_FRAMES)
    private val audioOutput = AudioOutput(context, AudioBufferPolicy(SAMPLE_RATE), pcmQueue, clock, diagnostics)
    private val running = AtomicBoolean(true)
    private val playing = AtomicBoolean(false)
    private val scrubbing = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val timeline = AtomicReference<Timeline?>()
    private val pendingSeek = AtomicReference<Seek?>()
    private val wake = Object()
    @Volatile private var nextMixProjectUs = 0L
    @Volatile private var transportHintUs = 0L
    @Volatile private var playbackAnchorUs = 0L
    @Volatile private var outputStarted = false
    @Volatile private var prebuffer = false
    @Volatile private var activeSourceIds = emptySet<String>()
    @Volatile private var activeSources = emptyList<ResolvedAudioSource>()
    private val configuredSourceIds = linkedSetOf<String>()
    private val decodeThread = Thread(::decodeLoop, "ReclyAudioDecode").apply { isDaemon = true; start() }

    fun updateTimeline(snapshot: ProjectSnapshot, resolver: ActiveClipResolver) {
        timeline.set(Timeline(snapshot.revision, resolver)); audioOutput.setProjectRevision(snapshot.revision); signal()
    }

    /** Updates demand visibility/gain without seeking or recreating decoders. */
    fun updateProjectPosition(projectTimeUs: Long) { transportHintUs = projectTimeUs; signal() }

    fun play(projectTimeUs: Long) {
        if (!running.get() || scrubbing.get()) return
        playbackAnchorUs = projectTimeUs; transportHintUs = projectTimeUs
        if (playing.compareAndSet(false, true)) {
            // Never anchor to nextMixProjectUs: it is ahead by the queued duration.
            clock.anchor(projectTimeUs, audioOutput.playbackHeadFrames())
            outputStarted = false; signal()
        }
    }

    fun pause() {
        if (playing.compareAndSet(true, false)) {
            audioOutput.pause() // pause is deliberately not flush
            outputStarted = false
        }
    }

    fun seek(projectTimeUs: Long, transportGeneration: Long) {
        if (transportGeneration < generation.get()) return
        generation.set(transportGeneration)
        transportHintUs = projectTimeUs; playbackAnchorUs = projectTimeUs; nextMixProjectUs = projectTimeUs
        audioOutput.pause(); outputStarted = false
        audioOutput.flush(transportGeneration)
        pcmQueue.clear(); diagnostics.audioQueuedDurationUs.set(0L)
        clock.reset(projectTimeUs); configuredSourceIds.clear(); prebuffer = true
        pendingSeek.set(Seek(projectTimeUs, transportGeneration))
        diagnostics.seekExecuted(projectTimeUs, transportGeneration); signal()
    }

    /** Fast scrub invalidates PCM but avoids repeated hardware flushes. */
    fun scrubTo(projectTimeUs: Long, transportGeneration: Long) {
        generation.set(transportGeneration); transportHintUs = projectTimeUs; nextMixProjectUs = projectTimeUs
        audioOutput.invalidateGeneration(transportGeneration); pcmQueue.clear(); diagnostics.audioQueuedDurationUs.set(0L)
        clock.reset(projectTimeUs); configuredSourceIds.clear()
    }

    fun beginScrub() { scrubbing.set(true); pause() }
    fun endScrub() { scrubbing.set(false); signal() }

    fun getPlaybackPositionUs(): Long? {
        if (!playing.get() || scrubbing.get() || !outputStarted || activeSourceIds.isEmpty()) return null
        return audioOutput.getPlaybackPositionUs()
    }

    private fun decodeLoop() {
        while (running.get()) {
            try {
                val currentTimeline = timeline.get()
                if (currentTimeline == null || scrubbing.get()) { await(); continue }
                pendingSeek.getAndSet(null)?.let { command ->
                    if (command.generation != generation.get()) return@let
                    val active = currentTimeline.resolver.audioAt(command.projectTimeUs, command.generation)
                    val upcoming = currentTimeline.resolver.upcomingAudio(command.projectTimeUs, PREWARM_US, command.generation)
                    decoderManager.prepareAndSeek(active, command.generation)
                    val prewarmed = upcoming.filter { next -> active.none { it.id == next.id } }
                    decoderManager.prewarm(prewarmed, command.generation)
                    configuredSourceIds += active.map { it.id }
                    configuredSourceIds += prewarmed.map { it.id }
                    nextMixProjectUs = command.projectTimeUs
                }
                if (!playing.get() && !prebuffer) { await(); continue }
                if (pcmQueue.queuedDurationUs() >= TARGET_QUEUE_US) {
                    prebuffer = false; startOutputIfReady(); await(4L); continue
                }
                val gen = generation.get()
                if (activeSourceIds.isEmpty() && transportHintUs > nextMixProjectUs) nextMixProjectUs = transportHintUs
                val demands = currentTimeline.resolver.audioAt(nextMixProjectUs, gen)
                val ids = demands.mapTo(linkedSetOf()) { it.id }
                val previousIds = activeSourceIds
                val disappeared = previousIds - ids
                val unexpected = activeSources.filter { it.id in disappeared && nextMixProjectUs < it.projectEndUs }
                if (unexpected.isNotEmpty()) logMissing(unexpected, nextMixProjectUs, gen)
                if (demands.isEmpty()) { prebuffer = false; await(8L); continue }
                val newSources = demands.filter { it.id !in configuredSourceIds }
                if (newSources.isNotEmpty()) {
                    decoderManager.prepareAndSeek(newSources, gen); configuredSourceIds += newSources.map { it.id }
                }
                val sources = demands.mapNotNull { demand ->
                    decoderManager.getOrCreateSession(demand.key, demand.uri)?.let { session ->
                        AudioMixer.Source(demand, session, demand.volume, demand.speed, demand.preservePitch)
                    }
                }
                activeSources = sources.map { it.resolved }
                activeSourceIds = activeSources.mapTo(linkedSetOf()) { it.id }
                diagnostics.activeAudioSourceCount.set(activeSourceIds.size.toLong())
                if (sources.isEmpty()) { prebuffer = false; await(8L); continue }
                if (previousIds.isEmpty()) playbackAnchorUs = nextMixProjectUs
                val block = mixer.mix(sources, nextMixProjectUs, gen, currentTimeline.revision)
                if (block.generation != generation.get()) {
                    diagnostics.staleBlockDropped(); PcmBlock.release(block); continue
                }
                if (pcmQueue.offer(block, 20L)) {
                    nextMixProjectUs += block.durationUs
                    diagnostics.audioQueuedDurationUs.set(pcmQueue.queuedDurationUs()); startOutputIfReady()
                } else PcmBlock.release(block)
            } catch (interrupted: InterruptedException) {
                if (!running.get()) break
            } catch (failure: Throwable) {
                diagnostics.event("AUDIO_PIPELINE_ERROR ${failure.javaClass.simpleName}: ${failure.message}")
                onError(failure); await(20L)
            }
        }
    }

    private fun startOutputIfReady() {
        if (!playing.get() || outputStarted || pcmQueue.queuedDurationUs() < START_QUEUE_US || activeSourceIds.isEmpty()) return
        // Prebuffer may have happened while the monotonic transport kept advancing. Drop complete
        // stale blocks instead of starting audible content behind the shared project clock.
        while (true) {
            val first = pcmQueue.peek() ?: return
            if (first.projectStartUs + first.durationUs > transportHintUs) break
            pcmQueue.poll(0L)?.let { diagnostics.staleBlockDropped(); PcmBlock.release(it) }
        }
        pcmQueue.trimHeadTo(transportHintUs)
        val firstProjectUs = pcmQueue.peek()?.projectStartUs ?: return
        playbackAnchorUs = firstProjectUs
        clock.anchor(playbackAnchorUs, audioOutput.playbackHeadFrames())
        audioOutput.play(); outputStarted = true
    }

    private fun logMissing(sources: List<ResolvedAudioSource>, projectUs: Long, gen: Long) {
        diagnostics.event("AUDIO_SOURCE_MISSING sourceId=${sources.joinToString { it.id }} generation=$gen projectTimeUs=$projectUs " +
            "sourceTimeUs=${sources.joinToString { it.sourceTimeUs.toString() }} resolverActive=0 decoderState=missing " +
            "EOS=unknown mute=false volume=${sources.joinToString { it.volume.toString() }} queueUs=${pcmQueue.queuedDurationUs()} " +
            "lastDecodedPts=unknown lastMixedPts=$nextMixProjectUs lastWrittenPts=${getPlaybackPositionUs()} " +
            "AudioTrackState=${audioOutput.getTrack()?.playState} underrunCount=${diagnostics.audioUnderrunCount.get()}")
    }

    private fun signal() = synchronized(wake) { wake.notifyAll() }
    private fun await(ms: Long = 50L) = synchronized(wake) { wake.wait(ms) }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        playing.set(false); scrubbing.set(false); signal(); decodeThread.interrupt()
        pcmQueue.close(); audioOutput.close(); decoderManager.close()
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val BLOCK_FRAMES = 1024
        private const val MAX_QUEUE_US = 120_000L
        private const val TARGET_QUEUE_US = 85_000L
        private const val START_QUEUE_US = 40_000L
        private const val PREWARM_US = 350_000L
    }
}
