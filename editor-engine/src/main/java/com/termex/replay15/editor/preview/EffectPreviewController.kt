package com.termex.replay15.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termex.replay15.editor.assets.EffectDefinition
import com.termex.replay15.editor.assets.EffectPreset
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.domain.VideoClip
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

class EffectPreviewController(
    private val context: Context,
    private val onPresetThumbnailReady: (preset: EffectPreset, bitmap: Bitmap) -> Unit,
    private val onEffectThumbnailReady: ((effect: EffectDefinition, bitmap: Bitmap) -> Unit)? = null,
) : AutoCloseable {

    companion object {
        private const val TAG = "ReclyEffectPreview"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isDebug = context.isEditorDebuggable()
    private val renderer = EffectThumbnailRenderer(context)

    private val controllerJob = SupervisorJob()
    private val controllerScope = CoroutineScope(Dispatchers.Default + controllerJob)

    private var activeSession: Session? = null

    private data class Session(
        val sessionId: String,
        val clip: VideoClip,
        val timestampUs: Long,
        val job: Job,
        @Volatile var baseFrame: Bitmap? = null,
        val pendingRenders: ConcurrentHashMap<String, Future<*>> = ConcurrentHashMap(),
    )

    fun startSession(
        clip: VideoClip,
        sessionTimestampUs: Long,
        initialPreset: EffectPreset? = null,
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
            val base = RepresentativeFrameProvider.extractBaseFrame(
                context = context,
                clip = clip,
                requestedTimeUs = sessionTimestampUs,
            )

            if (!isActive || base == null) {
                if (isDebug && base == null) {
                    Log.w(TAG, "EFFECT THUMBNAIL FAILED - Could not extract base frame for clip=${clip.id}")
                }
                return@launch
            }

            session.baseFrame = base

            // If an initial preset was specified, prioritize rendering it
            if (initialPreset != null) {
                requestPresetThumbnail(initialPreset)
            }
        }
    }

    fun requestPresetThumbnail(preset: EffectPreset, globalIntensity: Float = 1.0f) {
        val session = activeSession ?: return

        // 1. Check in-memory LRU cache
        val cacheKey = EffectThumbnailCache.makePresetKey(
            session.clip, session.timestampUs, preset, globalIntensity
        )
        val cached = EffectThumbnailCache.get(cacheKey)
        if (cached != null) {
            publishPresetThumbnail(session, preset, cached)
            return
        }

        // 2. If baseFrame is already ready, render directly
        val base = session.baseFrame
        if (base != null) {
            dispatchPresetRender(session, preset, base, globalIntensity)
        } else {
            // Wait for baseFrame to become available
            controllerScope.launch(session.job) {
                while (session.baseFrame == null && isActive) {
                    delay(20)
                }
                session.baseFrame?.let { currentBase ->
                    dispatchPresetRender(session, preset, currentBase, globalIntensity)
                }
            }
        }
    }

    private fun dispatchPresetRender(
        session: Session,
        preset: EffectPreset,
        baseFrame: Bitmap,
        globalIntensity: Float,
    ) {
        if (session.sessionId != activeSession?.sessionId) return

        val future = renderer.renderPreset(baseFrame, preset, globalIntensity)
        session.pendingRenders[preset.id] = future

        controllerScope.launch(session.job) {
            val result = runCatching { future.get() }.getOrNull()
            session.pendingRenders.remove(preset.id)

            if (result != null && session.sessionId == activeSession?.sessionId) {
                val cacheKey = EffectThumbnailCache.makePresetKey(
                    session.clip, session.timestampUs, preset, globalIntensity
                )
                EffectThumbnailCache.put(cacheKey, result)
                publishPresetThumbnail(session, preset, result)
            }
        }
    }

    fun requestEffectThumbnail(definition: EffectDefinition, intensity: Float = 0.7f) {
        val session = activeSession ?: return

        val cacheKey = EffectThumbnailCache.makeEffectKey(
            session.clip, session.timestampUs, definition, intensity
        )
        val cached = EffectThumbnailCache.get(cacheKey)
        if (cached != null) {
            publishEffectThumbnail(session, definition, cached)
            return
        }

        val base = session.baseFrame
        if (base != null) {
            dispatchEffectRender(session, definition, base, intensity)
        } else {
            controllerScope.launch(session.job) {
                while (session.baseFrame == null && isActive) {
                    delay(20)
                }
                session.baseFrame?.let { currentBase ->
                    dispatchEffectRender(session, definition, currentBase, intensity)
                }
            }
        }
    }

    private fun dispatchEffectRender(
        session: Session,
        definition: EffectDefinition,
        baseFrame: Bitmap,
        intensity: Float,
    ) {
        if (session.sessionId != activeSession?.sessionId) return

        val future = renderer.renderEffect(baseFrame, definition, intensity)
        session.pendingRenders[definition.id] = future

        controllerScope.launch(session.job) {
            val result = runCatching { future.get() }.getOrNull()
            session.pendingRenders.remove(definition.id)

            if (result != null && session.sessionId == activeSession?.sessionId) {
                val cacheKey = EffectThumbnailCache.makeEffectKey(
                    session.clip, session.timestampUs, definition, intensity
                )
                EffectThumbnailCache.put(cacheKey, result)
                publishEffectThumbnail(session, definition, result)
            }
        }
    }

    private fun publishPresetThumbnail(session: Session, preset: EffectPreset, bitmap: Bitmap) {
        if (session.sessionId != activeSession?.sessionId) return
        mainHandler.post {
            if (session.sessionId == activeSession?.sessionId) {
                onPresetThumbnailReady(preset, bitmap)
            }
        }
    }

    private fun publishEffectThumbnail(session: Session, definition: EffectDefinition, bitmap: Bitmap) {
        if (session.sessionId != activeSession?.sessionId) return
        mainHandler.post {
            if (session.sessionId == activeSession?.sessionId) {
                onEffectThumbnailReady?.invoke(definition, bitmap)
            }
        }
    }

    private fun closeCurrentSession() {
        activeSession?.let { s ->
            s.job.cancel()
            s.pendingRenders.values.forEach { it.cancel(true) }
            s.pendingRenders.clear()
        }
        activeSession = null
    }

    override fun close() {
        closeCurrentSession()
        controllerJob.cancel()
        renderer.close()
    }
}
