package com.termex.replay15.editor.preview.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.termex.replay15.editor.media.MediaSourceAccess
import com.termex.replay15.editor.media.MediaSourceStatus
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One source, one persistent codec. All platform codec/extractor operations belong to worker.
 * No play/prepare graph operation exists: callers advance the requested source timestamp.
 *
 * Output is deliberately limited to one outstanding SurfaceTexture buffer. The GL owner must
 * consume (or discard) it and acknowledge before another output can be released. This preserves
 * the association between a seek generation and the asynchronous SurfaceTexture callback.
 */
class VideoDecoderSession(
    context: Context,
    private val uri: String,
    initialClipId: String?,
    private val surfaceSupplier: () -> Surface?,
    private val sessionGeneration: Long,
    private val isCurrentSessionGeneration: (Long) -> Boolean,
    private val isCurrentGeneration: (Long) -> Boolean,
    private val sourceToProjectTimeUs: (Long) -> Long,
    private val onFrameSubmitted: (Frame) -> Unit,
    private val onError: (PreviewMediaFailure) -> Unit,
    private val event: (String) -> Unit = {},
) : AutoCloseable {
    @Volatile private var clipId: String? = initialClipId
    /** Instrumentation/backward-compatible entry point; production supplies persistent surface generations. */
    constructor(
        context: Context,
        uri: String,
        surface: Surface,
        isCurrentGeneration: (Long) -> Boolean,
        onFrameSubmitted: (Frame) -> Unit,
        onError: (Throwable) -> Unit,
        event: (String) -> Unit = {},
    ) : this(context, uri, null, { surface }, 0L, { true }, isCurrentGeneration, { it }, onFrameSubmitted,
        { failure -> onError(failure.cause ?: IllegalStateException(failure.userMessage())) }, event)

    data class Target(val sourceTimeUs: Long, val generation: Long, val mode: SeekMailbox.Mode, val projectTimeUs: Long = sourceTimeUs)
    
    // Actually, the Frame class is lightweight and the lambda capture is needed per-frame.
    // The allocation cost is minimal. Keep the current approach.
    class Frame internal constructor(
        val presentationTimeUs: Long,
        val projectPresentationTimeUs: Long,
        val generation: Long,
        private val consumed: () -> Unit,
    ) {
        private val acknowledged = AtomicBoolean()
        /** Called on GL thread after updateTexImage, including when dropping a stale frame. */
        fun acknowledge() { if (acknowledged.compareAndSet(false, true)) consumed() }
    }

    private val app = context.applicationContext
    private val worker = HandlerThread("PreviewDecoder").apply { start() }
    private val handler = Handler(worker.looper)
    private val latest = AtomicReference<Target?>()
    private val wakeQueued = AtomicBoolean()
    private val closed = AtomicBoolean()
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var pendingSurfaceFrame = false
    private var pendingSurfaceFrameTimeNs = 0L
    private var inputEnded = false
    private var outputEnded = false
    private var decodingTarget: Target? = null
    private var lastPresentationUs = Long.MIN_VALUE
    private var lastCompleted: Target? = null
    private var heldIndex = -1
    private var heldPtsUs = 0L
    private var codecSpecificData = emptyList<ByteBuffer>()
    private var pendingCsd = 0
    private var csdIndex = 0
    private var receivedOutput = false
    private val releaseWaiters = mutableListOf<() -> Unit>()
    private var releaseComplete = false
    private var failed = false
    private enum class Operation { SOURCE_OPEN, CODEC, SEEK, DECODE }
    private var operation = Operation.SOURCE_OPEN
    private var codecName: String? = null
    private var seekTargetUs = 0L
    private var lastProgressNs = System.nanoTime()
    private val info = MediaCodec.BufferInfo()

    fun resetForReuse(newClipId: String) {
        handler.post {
            clipId = newClipId
            decodingTarget = null
            lastCompleted = null
            lastPresentationUs = Long.MIN_VALUE
            pendingSurfaceFrame = false
            heldIndex = -1
            inputEnded = false
            outputEnded = false
            runCatching {
                codec?.flush()
                pendingCsd = if (!receivedOutput) codecSpecificData.size else 0
                csdIndex = 0
                receivedOutput = false
                event("DECODER_FLUSH reuse=true")
            }
            wake()
        }
    }

    fun request(target: Target) {
        require(target.sourceTimeUs >= 0)
        if (closed.get()) return
        latest.set(target)
        wake()
    }

    /** Returns whether the rebind was queued; actual codec work stays on its owner thread. */
    fun rebindSurface(newSurface: Surface): Boolean {
        if (closed.get() || !newSurface.isValid) return false
        return handler.post {
            if (!closed.get() && isCurrentSessionGeneration(sessionGeneration)) {
                runCatching { codec?.setOutputSurface(newSurface) }.onFailure { cause ->
                    onError(PreviewMediaFailure.SurfaceFailure(clipId, Uri.parse(uri), sessionGeneration, cause))
                }
            }
        }
    }

    private fun wake(delayMs: Long = 0) {
        if (!closed.get() && wakeQueued.compareAndSet(false, true)) handler.postDelayed(pump, delayMs)
    }

    private val pump = Runnable {
        wakeQueued.set(false)
        if (!isCurrentSessionGeneration(sessionGeneration)) return@Runnable
        if (!closed.get() && !failed) try {
            step()
        } catch (failure: Throwable) {
            failed = true
            releasePlatformResources()
            onError((failure as? ClassifiedFailure)?.failure ?: classify(failure))
        }
    }

    private class ClassifiedFailure(val failure: PreviewMediaFailure) : RuntimeException(failure.cause)

    private fun classify(cause: Throwable): PreviewMediaFailure {
        val parsed = Uri.parse(uri)
        return when (operation) {
            Operation.SOURCE_OPEN -> when (MediaSourceAccess.verify(app.contentResolver, parsed)) {
                MediaSourceStatus.Missing -> PreviewMediaFailure.MissingSource(clipId, parsed, sessionGeneration, cause)
                MediaSourceStatus.PermissionLost -> PreviewMediaFailure.PermissionLost(clipId, parsed, sessionGeneration, cause)
                else -> PreviewMediaFailure.SourceReadFailure(clipId, parsed, sessionGeneration, cause)
            }
            Operation.SEEK -> PreviewMediaFailure.SeekFailure(clipId, parsed, seekTargetUs, sessionGeneration, cause)
            Operation.CODEC, Operation.DECODE -> PreviewMediaFailure.DecoderFailure(clipId, parsed, codecName, sessionGeneration, cause)
        }
    }

    private fun open() {
        val parsedUri = Uri.parse(uri)
        val decoderSurface = surfaceSupplier() ?: throw ClassifiedFailure(PreviewMediaFailure.SurfaceFailure(
            clipId, parsedUri, sessionGeneration, IllegalStateException("Decoder surface unavailable")))
        if (!decoderSurface.isValid) throw ClassifiedFailure(PreviewMediaFailure.SurfaceFailure(
            clipId, parsedUri, sessionGeneration, IllegalStateException("Decoder surface is invalid")))
        event("DECODER_SURFACE_BIND valid=${decoderSurface.isValid}")
        event("MEDIA_SOURCE_OPEN clip=$clipId uri=$uri generation=$sessionGeneration")
        when (val status = MediaSourceAccess.verify(app.contentResolver, parsedUri)) {
            MediaSourceStatus.Available -> event("MEDIA_SOURCE_OK clip=$clipId generation=$sessionGeneration")
            MediaSourceStatus.Missing -> throw ClassifiedFailure(PreviewMediaFailure.MissingSource(clipId, parsedUri, sessionGeneration))
            MediaSourceStatus.PermissionLost -> throw ClassifiedFailure(PreviewMediaFailure.PermissionLost(clipId, parsedUri, sessionGeneration))
            is MediaSourceStatus.ReadError -> throw ClassifiedFailure(PreviewMediaFailure.SourceReadFailure(clipId, parsedUri, sessionGeneration, status.cause))
        }
        operation = Operation.SOURCE_OPEN
        val ext = MediaExtractor()
        extractor = ext // Ownership established before setDataSource, which can throw.
        ext.setDataSource(app, parsedUri, null)
        val track = (0 until ext.trackCount).firstOrNull {
            ext.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw ClassifiedFailure(PreviewMediaFailure.UnsupportedFormat(clipId, parsedUri, null, sessionGeneration,
            IllegalArgumentException("No video track")))
        ext.selectTrack(track)
        val format = ext.getTrackFormat(track)
        codecSpecificData = (0..2).mapNotNull { index ->
            format.getByteBuffer("csd-$index")?.let { data ->
                val duplicate = data.duplicate()
                ByteBuffer.allocate(duplicate.remaining()).apply { put(duplicate); flip() }
            }
        }
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        operation = Operation.CODEC
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            !it.isEncoder && !it.isAlias && it.supportedTypes.any { type -> type.equals(mime, true) } &&
                runCatching { it.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
        }.sortedBy { !it.isHardwareAccelerated }
        var lastFailure: Exception? = null
        for (candidate in candidates) {
            // Retry optional low-latency configuration once without hints before changing codec.
            for (hints in listOf(true, false)) {
                var opened: MediaCodec? = null
                try {
                    val configured = ext.getTrackFormat(track)
                    if (hints && Build.VERSION.SDK_INT >= 30 && candidate.getCapabilitiesForType(mime)
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                        configured.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    opened = MediaCodec.createByCodecName(candidate.name)
                    codecName = candidate.name
                    event("DECODER_CREATE name=${candidate.name}")
                    opened.configure(configured, decoderSurface, null, 0)
                    event("DECODER_CONFIGURE")
                    opened.start()
                    codec = opened
                    event("DECODER_START")
                    lastProgressNs = System.nanoTime()
                    operation = Operation.DECODE
                    return
                } catch (failure: Exception) {
                    lastFailure = failure
                    opened?.let {
                        runCatching { it.release() }
                        event("DECODER_RELEASE configureFailure=true")
                    }
                }
            }
        }
        if (candidates.isEmpty()) throw ClassifiedFailure(PreviewMediaFailure.UnsupportedFormat(
            clipId, parsedUri, mime, sessionGeneration, IllegalArgumentException("No compatible decoder for $mime")))
        throw ClassifiedFailure(PreviewMediaFailure.DecoderFailure(clipId, parsedUri, codecName, sessionGeneration,
            lastFailure ?: IllegalStateException("Decoder configure failed for $mime")))
    }

    private fun step() {
        val target = latest.get() ?: return
        if (!isCurrentGeneration(target.generation) || target == lastCompleted) return
        if (pendingSurfaceFrame) {
            if (System.nanoTime() - pendingSurfaceFrameTimeNs > 200_000_000L) {
                event("PENDING_SURFACE_FRAME_TIMEOUT resetting")
                pendingSurfaceFrame = false
            } else {
                return
            }
        }
        if (codec == null) open()
        if (closed.get()) return
        val ext = requireNotNull(extractor)
        val decoder = requireNotNull(codec)
        val previousTarget = decodingTarget
        // A target between the requested instant and the already displayed output needs no flush.
        val completed = lastCompleted
        if (completed != null && completed.generation == target.generation) {
            if (outputEnded && target.sourceTimeUs >= completed.sourceTimeUs) {
                lastCompleted = target
                return
            }
            if (lastPresentationUs != Long.MIN_VALUE) {
                val minPts = minOf(completed.sourceTimeUs, lastPresentationUs)
                val maxPts = maxOf(completed.sourceTimeUs, lastPresentationUs)
                if (target.sourceTimeUs in minPts..maxPts && target.sourceTimeUs <= lastPresentationUs) {
                    lastCompleted = target
                    return
                }
            }
        }
        if (target != previousTarget) {
            val isContinuousPlayback = previousTarget != null &&
                target.generation == previousTarget.generation &&
                target.sourceTimeUs >= previousTarget.sourceTimeUs &&
                (target.sourceTimeUs - previousTarget.sourceTimeUs < 2_000_000L)

            if (!isContinuousPlayback) {
                operation = Operation.SEEK
                seekTargetUs = target.sourceTimeUs

                ext.seekTo(
                    target.sourceTimeUs,
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC
                )
                // First configure is already flushed. Preserve codec initialization data on first use.
                if (previousTarget != null) {
                    decoder.flush()
                    // Android requires CSD resubmission when flushing before initial output.
                    pendingCsd = if (!receivedOutput) codecSpecificData.size else 0
                    csdIndex = 0
                    receivedOutput = false
                    event("DECODER_FLUSH")
                }
                heldIndex = -1 // flush invalidates all previously dequeued indices.
                inputEnded = false
                outputEnded = false
                lastPresentationUs = Long.MIN_VALUE
                lastProgressNs = System.nanoTime()
                event("SEEK_EXECUTE generation=${target.generation} sourceUs=${target.sourceTimeUs}")
                operation = Operation.DECODE
            }
            decodingTarget = target
        }
        // Bounded nonblocking batches keep release and newer seeks responsive even on long GOPs.
        repeat(8) {
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    val buffer = requireNotNull(decoder.getInputBuffer(index))
                    buffer.clear()
                    if (pendingCsd > 0) {
                        buffer.put(codecSpecificData[csdIndex++].duplicate())
                        decoder.queueInputBuffer(index, 0, buffer.position(), 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        pendingCsd--
                    } else {
                        val size = ext.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                }
            }
        }
        repeat(8) {
            if (pendingSurfaceFrame || outputEnded) return@repeat
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) receivedOutput = true
            if (index >= 0) {
                receivedOutput = true
                lastProgressNs = System.nanoTime()
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val hasImage = info.size > 0
                val pts = info.presentationTimeUs
                val currentTarget = latest.get()
                val currentGenerationValid = isCurrentGeneration(target.generation) && !closed.get()
                val isSameGeneration = currentTarget == null || currentTarget.generation == target.generation
                val isCurrent = currentGenerationValid && isSameGeneration
                val effectiveTarget = if (isSameGeneration && currentTarget != null) currentTarget else target
                val wanted = isCurrent && hasImage && (
                    target.mode == SeekMailbox.Mode.FAST_SCRUB ||
                    effectiveTarget.mode == SeekMailbox.Mode.FAST_SCRUB ||
                    pts >= effectiveTarget.sourceTimeUs ||
                    pts >= target.sourceTimeUs ||
                    (lastPresentationUs != Long.MIN_VALUE && pts > lastPresentationUs)
                )
                if (wanted) {
                    if (heldIndex >= 0) { decoder.releaseOutputBuffer(heldIndex, false); heldIndex = -1 }
                    deliver(decoder, index, pts, effectiveTarget)
                } else if (isCurrent && hasImage) {
                    if (heldIndex >= 0) decoder.releaseOutputBuffer(heldIndex, false)
                    heldIndex = index
                    heldPtsUs = pts
                } else {
                    decoder.releaseOutputBuffer(index, false)
                    if (!currentGenerationValid || !isSameGeneration) event("SEEK_STALE_DROP generation=${target.generation}")
                }
                // A target in the last frame's interval may be later than the final PTS.
                if (outputEnded && heldIndex >= 0) {
                    val held = heldIndex
                    heldIndex = -1
                    if (isCurrent) deliver(decoder, held, heldPtsUs, effectiveTarget)
                    else decoder.releaseOutputBuffer(held, false)
                }
            }
        }
        if (!pendingSurfaceFrame && !outputEnded && latest.get() != lastCompleted) {
            check(System.nanoTime() - lastProgressNs < 5_000_000_000L) { "Decoder produced no output for 5 seconds" }
            wake(2) // A local decoder poll, never a global playback/recreation delay.
        }
    }

    private fun deliver(
        decoder: MediaCodec,
        index: Int,
        pts: Long,
        target: Target
    ) {
        lastPresentationUs = pts
        lastCompleted = target
        pendingSurfaceFrame = true
        pendingSurfaceFrameTimeNs = System.nanoTime()

        val frame = Frame(
        pts,
        sourceToProjectTimeUs(pts),
        target.generation
    ) {
        if (!isCurrentSessionGeneration(sessionGeneration)) return@Frame
        if (!closed.get()) {
            handler.post {
                pendingSurfaceFrame = false
                wake()
            }
        }
    }

    onFrameSubmitted(frame)
    decoder.releaseOutputBuffer(index, true)

    event(
        "FRAME_SUBMITTED generation=${target.generation} ptsUs=$pts"
    )
}

    private fun releasePlatformResources() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
            event("DECODER_RELEASE")
        }
        codec = null
        extractor?.let { runCatching { it.release() } }
        extractor = null
    }

    /** Decoder stops before the caller releases its Surface/SurfaceTexture/EGL resources. */
    fun close(onReleased: () -> Unit) {
        synchronized(releaseWaiters) {
            if (releaseComplete) { onReleased(); return }
            releaseWaiters += onReleased
        }
        if (!closed.compareAndSet(false, true)) return
        latest.set(null)
        handler.removeCallbacks(pump)
        handler.post {
            try { releasePlatformResources() } finally {
                val callbacks = synchronized(releaseWaiters) {
                    releaseComplete = true
                    releaseWaiters.toList().also { releaseWaiters.clear() }
                }
                try { callbacks.forEach { runCatching(it) } } finally { worker.quitSafely() }
            }
        }
    }

    override fun close() = close {}
}
