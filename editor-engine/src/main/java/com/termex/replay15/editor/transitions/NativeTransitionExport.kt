package com.termex.replay15.editor.transitions

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.preview.engine.NativePreviewBridge
import com.termex.replay15.editor.render.ExportClock
import org.json.JSONObject
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/** Android lifetime adapter. Textures, capture queue, shaders and drawing are owned by Rust. */
internal object NativeTransitionSessions {
    class Session {
        private var handle = 0L
        private var references = 0
        private var closed = false
        private val results = TreeMap<Long, Boolean>()
        @Synchronized fun retain() { check(!closed); references++ }
        @Synchronized fun <T> draw(action: (Long) -> T): T {
            check(!closed) { "Transition session closed" }
            if (handle == 0L) handle = NativePreviewBridge.nativeCreate().also { check(it != 0L) }
            return action(handle)
        }
        @Synchronized fun presented(timeUs: Long, composited: Boolean) {
            results[timeUs] = composited
            while (results.size > 1024) results.pollFirstEntry()
        }
        @Synchronized fun composited(timeUs: Long): Boolean {
            val candidates = listOfNotNull(results.floorEntry(timeUs), results.ceilingEntry(timeUs))
            return candidates.minByOrNull { kotlin.math.abs(it.key - timeUs) }
                ?.takeIf { kotlin.math.abs(it.key - timeUs) <= 2_000L }?.value == true
        }
        @Synchronized fun release() {
            references--
            if (references == 0 && handle != 0L) { NativePreviewBridge.nativeDestroy(handle); handle = 0L }
        }
        @Synchronized fun abandon() {
            closed = true
            if (handle != 0L) { NativeTransitionBridge.nativeAbandon(handle); handle = 0L }
            results.clear()
        }
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    fun register(key: String): Session = sessions.computeIfAbsent(key) { Session() }.also { it.retain() }
    fun composited(key: String, timeUs: Long): Boolean = sessions[key]?.composited(timeUs) == true
    fun clearStaleSessions(active: Long) {
        sessions.keys.filter { it.substringBefore(':').toLongOrNull()?.let { id -> id < active } == true }
            .forEach { sessions.remove(it)?.abandon() }
    }
    /** Called when the Media3 export has ended or been cancelled; no live draw may use these sessions. */
    fun clear() { sessions.values.forEach { it.abandon() }; sessions.clear() }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NativeTransitionCaptureEffect(
    key: String, private val fromUs: Long, private val untilUs: Long,
    private val itemStartUs: Long, private val itemEndUs: Long,
) : GlEffect {
    private val session = NativeTransitionSessions.register(key)
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : NativePass() {
        override fun renderFrame(inputTexId: Int, presentationTimeUs: Long) {
            val time = ExportClock.projectTimeUs(presentationTimeUs, itemStartUs, itemEndUs)
            session.draw { handle ->
                if (time in fromUs until untilUs) NativeTransitionBridge.nativeCapture(handle, inputTexId, time, width, height)
                else NativeTransitionBridge.nativeCopy(handle, inputTexId, width, height, false)
            }
        }
        override fun release() { try { session.release() } finally { super.release() } }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NativeTransitionRenderEffect(
    private val window: TransitionWindow, private val outgoingEndUs: Long, key: String,
    private val itemStartUs: Long, private val itemEndUs: Long,
) : GlEffect {
    private val session = NativeTransitionSessions.register(key)
    private val parameters = JSONObject(window.transition.parameters).toString()
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : NativePass() {
        override fun renderFrame(inputTexId: Int, presentationTimeUs: Long) {
            val time = ExportClock.projectTimeUs(presentationTimeUs, itemStartUs, itemEndUs)
            session.draw { handle ->
                // After the outgoing source ends, the edit deliberately holds its final picture.
                // Match that boundary sample while progress continues in composition time.
                val outgoingSampleUs = minOf(time, outgoingEndUs - 1L)
                val drawn = if (window.contains(time)) NativeTransitionBridge.nativeDraw(handle, inputTexId, outgoingSampleUs,
                    width, height, window.transition.transitionId, window.progress(time), parameters)
                else { NativeTransitionBridge.nativeCopy(handle, inputTexId, width, height, false); false }
                session.presented(time, drawn)
            }
        }
        override fun release() { try { session.release() } finally { super.release() } }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private abstract class NativePass : BaseGlShaderProgram(false, 1) {
    protected var width = 0
    protected var height = 0
    protected abstract fun renderFrame(inputTexId: Int, presentationTimeUs: Long)
    final override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try { renderFrame(inputTexId, presentationTimeUs) }
        catch (error: Exception) { throw VideoFrameProcessingException.from(error, presentationTimeUs) }
    }
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth; height = inputHeight
        return Size(width, height)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NativeHoldFrameEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : NativePass() {
        private val handle = NativePreviewBridge.nativeCreate().also { check(it != 0L) }
        override fun renderFrame(inputTexId: Int, presentationTimeUs: Long) = NativeTransitionBridge.nativeCopy(handle, inputTexId, width, height, true)
        override fun release() { try { NativePreviewBridge.nativeDestroy(handle) } finally { super.release() } }
    }
}

/** Old project edge fades use the same native shading rule as interactive preview. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NativeClipFadeEffect(private val clip: VideoClip, private val clipStartUs: Long,
    private val itemStartUs: Long, private val itemEndUs: Long) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = object : NativePass() {
        private val handle = NativePreviewBridge.nativeCreate().also { check(it != 0L) }
        override fun renderFrame(inputTexId: Int, presentationTimeUs: Long) {
            val time = ExportClock.projectTimeUs(presentationTimeUs, itemStartUs, itemEndUs)
            NativeTransitionBridge.nativeApplyFade(handle, inputTexId, width, height, clip.transitionIn.ordinal,
                clip.transitionOut.ordinal, clip.transitionDurationUs, clip.durationUs, time - clipStartUs)
        }
        override fun release() { try { NativePreviewBridge.nativeDestroy(handle) } finally { super.release() } }
    }
}
