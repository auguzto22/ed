package com.termex.replay15.editor.render

import android.opengl.GLES20
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object TransitionBridge {
    private const val TAG = "TransitionBridge"
    private val textures = ConcurrentHashMap<String, Int>()
    private val fbos = ConcurrentHashMap<String, Int>()
    private val dimensions = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val captured = ConcurrentHashMap.newKeySet<String>()
    private val renderers = ConcurrentHashMap.newKeySet<String>()

    fun getTexture(bridgeKey: String): Int =
        if (isReady(bridgeKey)) textures[bridgeKey] ?: 0 else 0

    fun hasCapturedFrame(bridgeKey: String): Boolean =
        bridgeKey in captured && (textures[bridgeKey] ?: 0) != 0

    fun isReady(bridgeKey: String): Boolean =
        hasCapturedFrame(bridgeKey) && bridgeKey in renderers

    fun markCaptured(bridgeKey: String) {
        if (captured.add(bridgeKey)) {
            Log.d(TAG, "Capture ready key=$bridgeKey texture=${textures[bridgeKey] ?: 0}")
        }
    }

    fun setRendererReady(bridgeKey: String, ready: Boolean) {
        if (ready) {
            if (renderers.add(bridgeKey)) Log.d(TAG, "Renderer ready key=$bridgeKey")
        } else {
            renderers.remove(bridgeKey)
        }
    }

    /** Ensures that a texture and FBO exist for [bridgeKey] with [width] x [height].
     * [texId] is attached as GL_COLOR_ATTACHMENT0 to [fboId].
     * Returns (texId, fboId), or (0, 0) if creation fails. */
    fun acquireCaptureTarget(bridgeKey: String, width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        var texId = textures[bridgeKey] ?: 0
        var fboId = fbos[bridgeKey] ?: 0
        val dims = dimensions[bridgeKey]

        if (texId == 0 || dims?.first != width || dims?.second != height) {
            captured.remove(bridgeKey)
            if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            if (fboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)

            val texArr = IntArray(1)
            GLES20.glGenTextures(1, texArr, 0)
            texId = texArr[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            val prevFbo = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)

            val fboArr = IntArray(1)
            GLES20.glGenFramebuffers(1, fboArr, 0)
            fboId = fboArr[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])

            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "Framebuffer incomplete for transition $bridgeKey: status=$status")
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                return 0 to 0
            }

            textures[bridgeKey] = texId
            fbos[bridgeKey] = fboId
            dimensions[bridgeKey] = width to height
            Log.d(TAG, "Capture target created key=$bridgeKey texture=$texId size=${width}x$height")
        }
        return texId to fboId
    }

    private val refCounts = ConcurrentHashMap<String, Int>()

    fun retain(bridgeKey: String) {
        refCounts.compute(bridgeKey) { _, count -> (count ?: 0) + 1 }
    }

    fun release(bridgeKey: String) {
        val remaining = refCounts.compute(bridgeKey) { _, count ->
            val next = (count ?: 1) - 1
            if (next <= 0) null else next
        }
        if (remaining == null) {
            releaseCapture(bridgeKey)
        }
    }

    fun releaseCapture(bridgeKey: String) {
        refCounts.remove(bridgeKey)
        captured.remove(bridgeKey)
        textures.remove(bridgeKey)?.let { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        fbos.remove(bridgeKey)?.let { GLES20.glDeleteFramebuffers(1, intArrayOf(it), 0) }
        dimensions.remove(bridgeKey)
        Log.d(TAG, "Capture released key=$bridgeKey")
    }

    fun clearStaleSessions(activeSessionId: Long) {
        val staleKeys = textures.keys().toList().filter { key ->
            val sess = key.substringBefore(':').toLongOrNull()
            sess != null && sess < activeSessionId
        }
        for (k in staleKeys) {
            releaseCapture(k)
        }
    }

    fun clear() {
        refCounts.clear()
        val hasGlContext = try {
            android.opengl.EGL14.eglGetCurrentContext() != android.opengl.EGL14.EGL_NO_CONTEXT
        } catch (_: Throwable) { false }
        if (hasGlContext) {
            textures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
            fbos.values.forEach { GLES20.glDeleteFramebuffers(1, intArrayOf(it), 0) }
        }
        textures.clear()
        fbos.clear()
        dimensions.clear()
        captured.clear()
        renderers.clear()
    }
}
