package com.termex.replay15.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.VideoFilter
import com.termex.replay15.editor.render.FilterColorMath
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FilterPreviewController(
    private val context: Context,
    private val onThumbnailReady: (filter: VideoFilter, bitmap: Bitmap) -> Unit,
) : AutoCloseable {

    companion object {
        private const val TAG = "ReclyFilterPreview"
        private const val MAX_CONCURRENT_RENDERS = 3
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isDebug = context.isEditorDebuggable()

    // Concurrency control: max 3 parallel thumbnail generations
    private val renderSemaphore = Semaphore(MAX_CONCURRENT_RENDERS)

    private val controllerJob = SupervisorJob()
    private val controllerScope = CoroutineScope(Dispatchers.Default + controllerJob)

    private var activeSession: Session? = null

    private data class Session(
        val sessionId: String,
        val clip: VideoClip,
        val timestampUs: Long,
        val job: Job,
        @Volatile var baseFrame: Bitmap? = null,
        val renderedFilters: ConcurrentHashMap<VideoFilter, Boolean> = ConcurrentHashMap(),
    )

    private val decodeMsTotal = AtomicInteger(0)
    private val renderMsTotal = AtomicInteger(0)
    private val rendersCount = AtomicInteger(0)

    /**
     * Starts a new thumbnail generation session for a clip at a fixed playhead timestamp.
     * Cancels any previously running session and its background workers.
     */
    fun startSession(
        clip: VideoClip,
        sessionTimestampUs: Long,
        initialFilter: VideoFilter? = null,
    ) {
        closeCurrentSession()

        val sessionJob = Job(controllerJob)
        val sessionId = UUID.randomUUID().toString()
        val session = Session(
            sessionId = sessionId,
            clip = clip,
            timestampUs = sessionTimestampUs,
            job = sessionJob,
        )
        activeSession = session

        controllerScope.launch(sessionJob) {
            val startDecode = System.currentTimeMillis()
            val base = RepresentativeFrameProvider.extractBaseFrame(
                context = context,
                clip = clip,
                requestedTimeUs = sessionTimestampUs,
            )
            val decodeDuration = (System.currentTimeMillis() - startDecode).toInt()
            decodeMsTotal.addAndGet(decodeDuration)

            if (!isActive || base == null) {
                if (isDebug && base == null) {
                    Log.w(TAG, "FILTER THUMBNAIL FAILED - Could not extract base frame for clip=${clip.id}")
                }
                return@launch
            }

            session.baseFrame = base

            // 1. Publish ORIGINAL immediately
            publishThumbnail(session, VideoFilter.ORIGINAL, base)

            // 2. High priority: the current active filter (if not ORIGINAL)
            val targetFilter = initialFilter ?: VideoFilter.entries.getOrNull(clip.filter)
            if (targetFilter != null && targetFilter != VideoFilter.ORIGINAL) {
                renderFilterThumbnail(session, targetFilter)
            }
        }
    }

    /**
     * Requests thumbnail generation for a specific filter on the active session.
     * Can be called as items become visible during list scrolling (lazy loading).
     */
    fun requestFilterThumbnail(filter: VideoFilter) {
        val session = activeSession ?: return
        if (filter == VideoFilter.ORIGINAL) {
            session.baseFrame?.let { publishThumbnail(session, filter, it) }
            return
        }

        // Check in-memory cache first
        val cacheKey = FilterThumbnailCache.makeKey(session.clip, session.timestampUs, filter)
        val cached = FilterThumbnailCache.get(cacheKey)
        if (cached != null) {
            publishThumbnail(session, filter, cached)
            return
        }

        if (session.renderedFilters.putIfAbsent(filter, true) == true) {
            // Already queued or rendered
            return
        }

        controllerScope.launch(session.job) {
            renderFilterThumbnail(session, filter)
        }
    }

    private suspend fun renderFilterThumbnail(session: Session, filter: VideoFilter) {
        if (!session.job.isActive || activeSession?.sessionId != session.sessionId) return

        // Check cache once more
        val cacheKey = FilterThumbnailCache.makeKey(session.clip, session.timestampUs, filter)
        FilterThumbnailCache.get(cacheKey)?.let { cached ->
            publishThumbnail(session, filter, cached)
            return
        }

        renderSemaphore.withPermit {
            if (!session.job.isActive || activeSession?.sessionId != session.sessionId) return@withPermit

            // Wait until baseFrame is ready if not yet available
            var base = session.baseFrame
            while (base == null && session.job.isActive) {
                delay(15)
                base = session.baseFrame
            }
            if (base == null || !session.job.isActive) return@withPermit

            val startRender = System.currentTimeMillis()
            val filteredBitmap = runCatching {
                FilterColorMath.renderFilteredBitmap(
                    source = base,
                    preset = filter,
                    strength = 1f,
                )
            }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    if (isDebug) {
                        Log.e(TAG, "FILTER THUMBNAIL FAILED filterId=${filter.name} clipId=${session.clip.id}: ${error.message}")
                    }
                    base // Fallback to base
                },
            )

            val renderDuration = (System.currentTimeMillis() - startRender).toInt()
            renderMsTotal.addAndGet(renderDuration)
            rendersCount.incrementAndGet()

            if (isDebug && filteredBitmap !== base) {
                if (FilterColorMath.isPotentialNoOp(base, filteredBitmap)) {
                    Log.d(TAG, "Potential no-op filter detected for ${filter.name} on clip ${session.clip.id}")
                }
            }

            FilterThumbnailCache.put(cacheKey, filteredBitmap)
            publishThumbnail(session, filter, filteredBitmap)
        }
    }

    private fun publishThumbnail(session: Session, filter: VideoFilter, bitmap: Bitmap) {
        mainHandler.post {
            // Guard against stale results from previous sessions or clip switches
            if (activeSession?.sessionId != session.sessionId || activeSession?.clip?.id != session.clip.id) {
                return@post
            }
            onThumbnailReady(filter, bitmap)
        }
    }

    private fun closeCurrentSession() {
        activeSession?.let { s ->
            s.job.cancel()
            // Note: We do NOT recycle baseFrame here if it is cached or in use by UI
            s.baseFrame = null
        }
        activeSession = null
    }

    fun dumpMetrics(): String {
        val totalRenders = rendersCount.get()
        val avgRender = if (totalRenders > 0) renderMsTotal.get() / totalRenders else 0
        return "FilterPreviewController [renders=$totalRenders, avgRenderMs=$avgRender, ${FilterThumbnailCache.metrics()}]"
    }

    override fun close() {
        closeCurrentSession()
        controllerJob.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
