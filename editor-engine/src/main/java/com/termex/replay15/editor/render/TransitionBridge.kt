package com.termex.replay15.editor.render

import android.opengl.GLES20
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** An RGBA texture with a framebuffer drawing into it. */
internal class GlFrameTarget private constructor(val texture: Int, val framebuffer: Int, val width: Int, val height: Int) {
    fun delete() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    /** Draws into this target with [draw], restoring the caller's framebuffer and viewport. */
    inline fun drawInto(draw: () -> Unit) {
        val previousFramebuffer = IntArray(1)
        val previousViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(0, 0, width, height)
            draw()
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
            GLES20.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
        }
    }

    companion object {
        /** Creates a target on the current GL context, or returns null when the driver refuses it. */
        fun create(width: Int, height: Int): GlFrameTarget? {
            if (width <= 0 || height <= 0) return null
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val texture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            val previous = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previous, 0)
            val framebuffers = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffers, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previous[0])
            val target = GlFrameTarget(texture, framebuffers[0], width, height)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e("TransitionBridge", "Framebuffer incomplete status=$status size=${width}x$height")
                target.delete()
                return null
            }
            return target
        }
    }
}

/**
 * Shares the outgoing clip's frames with the incoming clip's transition shader during export.
 *
 * Media3 draws every input sequence on one GL thread but does not keep them in lockstep: the
 * outgoing clip can be a few frames ahead of or behind the incoming one. Frames are therefore kept
 * in a small ring keyed by presentation time, and the shader takes the one matching its own frame
 * instead of whatever was drawn last. Once a window has been fully rendered its frames are freed,
 * so memory stays bounded however many transitions a project has.
 *
 * GL calls happen on Media3's GL thread. The compositor reads [shaderSinceUs] from its own thread.
 */
object TransitionBridge {
    private const val TAG = "TransitionBridge"

    /** Covers the scheduling skew between two Media3 inputs. */
    private const val RING_SIZE = 4

    /** A captured frame this close after the requested time still counts as "at or before" it. */
    private const val MATCH_TOLERANCE_US = 2_000L

    private class Frame(val target: GlFrameTarget) {
        var timeUs = Long.MIN_VALUE
    }

    private class Session {
        val frames = ArrayList<Frame>(RING_SIZE)
        var references = 0
        var finished = false
        var shaderFailed = false
        @Volatile var shaderSinceUs = Long.MAX_VALUE

        fun freeFrames() {
            frames.forEach { it.target.delete() }
            frames.clear()
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    private fun session(key: String): Session = sessions.computeIfAbsent(key) { Session() }

    fun retain(key: String) {
        val session = session(key)
        synchronized(session) { session.references++ }
    }

    /** Drops one reference; the last one frees the frames. Must run on the GL thread. */
    fun release(key: String) {
        val session = sessions[key] ?: return
        synchronized(session) {
            session.references--
            if (session.references > 0) return
            session.freeFrames()
        }
        sessions.remove(key, session)
        Log.d(TAG, "Released key=$key")
    }

    /**
     * Returns the target to draw the outgoing frame shown at [timeUs] into, or null once the
     * window is finished. Reuses the oldest frame of the ring.
     */
    internal fun captureTarget(key: String, width: Int, height: Int, timeUs: Long): GlFrameTarget? {
        val session = session(key)
        synchronized(session) {
            if (session.finished) return null
            if (session.frames.any { it.target.width != width || it.target.height != height }) session.freeFrames()
            session.frames.firstOrNull { it.timeUs == timeUs }?.let { return it.target }
            val frame = if (session.frames.size < RING_SIZE) {
                Frame(GlFrameTarget.create(width, height) ?: return null).also { session.frames += it }
            } else {
                session.frames.minByOrNull { it.timeUs }!!
            }
            frame.timeUs = timeUs
            return frame.target
        }
    }

    /** Forgets a frame whose capture failed, so it is never sampled. */
    fun discard(key: String, timeUs: Long) {
        val session = sessions[key] ?: return
        synchronized(session) { session.frames.filter { it.timeUs == timeUs }.forEach { it.timeUs = Long.MIN_VALUE } }
    }

    /**
     * Texture of the outgoing frame for [timeUs]: the latest one captured at or before it, or the
     * earliest available when the outgoing clip is running behind. 0 when nothing was captured.
     */
    fun frameFor(key: String, timeUs: Long): Int {
        val session = sessions[key] ?: return 0
        synchronized(session) {
            val valid = session.frames.filter { it.timeUs != Long.MIN_VALUE }
            val best = valid.filter { it.timeUs <= timeUs + MATCH_TOLERANCE_US }.maxByOrNull { it.timeUs }
                ?: valid.minByOrNull { it.timeUs }
            return best?.target?.texture ?: 0
        }
    }

    /** Called for every frame the transition shader drew; the compositor follows from the first one. */
    fun markShaderFrame(key: String, timeUs: Long) {
        val session = sessions[key] ?: return
        synchronized(session) {
            if (!session.shaderFailed && timeUs < session.shaderSinceUs) session.shaderSinceUs = timeUs
        }
    }

    /** The shader stopped working: the compositor returns to a plain cut for the rest of the window. */
    fun markShaderFailed(key: String) {
        val session = sessions[key] ?: return
        synchronized(session) {
            session.shaderFailed = true
            session.shaderSinceUs = Long.MAX_VALUE
        }
    }

    /** Project time from which the incoming clip carries the composited transition. */
    fun shaderSinceUs(key: String): Long = sessions[key]?.shaderSinceUs ?: Long.MAX_VALUE

    /** The whole window has been rendered: free its frames early. Must run on the GL thread. */
    fun finish(key: String) {
        val session = sessions[key] ?: return
        synchronized(session) {
            if (session.finished) return
            session.finished = true
            session.freeFrames()
        }
        Log.d(TAG, "Finished key=$key")
    }

    /**
     * Forgets sessions of earlier exports. Their GL objects belonged to a context that no longer
     * exists, so only the bookkeeping is dropped.
     */
    fun clearStaleSessions(activeSessionId: Long) {
        sessions.keys.filter { key -> (key.substringBefore(':').toLongOrNull() ?: activeSessionId) < activeSessionId }
            .forEach { sessions.remove(it) }
    }

    fun clear() {
        sessions.clear()
    }
}
