package com.termex.replay15.editor.render

import android.opengl.GLES20
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Reusable OpenGL ES 2.0/3.0 Texture and Framebuffer pool.
 *
 * Avoids constant glGenTextures / glDeleteTextures / glGenFramebuffers churn during
 * playback, temporal effect processing, and scrubbing.
 */
object GlResourcePool {
    private const val TAG = "ReclyGlPool"
    private const val MAX_POOL_PER_DIM = 6

    private data class DimKey(val width: Int, val height: Int)

    private val texturePool = ConcurrentHashMap<DimKey, ConcurrentLinkedQueue<Int>>()
    private val fboPool = ConcurrentLinkedQueue<Int>()

    fun acquireTexture(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        val key = DimKey(width, height)
        val queue = texturePool.getOrPut(key) { ConcurrentLinkedQueue() }
        val reused = queue.poll()
        if (reused != null && reused != 0 && GLES20.glIsTexture(reused)) {
            return reused
        }

        val texArr = IntArray(1)
        GLES20.glGenTextures(1, texArr, 0)
        val tex = texArr[0]
        if (tex != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        return tex
    }

    fun releaseTexture(textureId: Int, width: Int, height: Int) {
        if (textureId == 0 || width <= 0 || height <= 0) return
        val key = DimKey(width, height)
        val queue = texturePool.getOrPut(key) { ConcurrentLinkedQueue() }
        if (queue.size < MAX_POOL_PER_DIM) {
            queue.offer(textureId)
        } else {
            val texArr = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, texArr, 0)
        }
    }

    fun acquireFramebuffer(): Int {
        val reused = fboPool.poll()
        if (reused != null && reused != 0 && GLES20.glIsFramebuffer(reused)) {
            return reused
        }
        val fboArr = IntArray(1)
        GLES20.glGenFramebuffers(1, fboArr, 0)
        return fboArr[0]
    }

    fun releaseFramebuffer(fboId: Int) {
        if (fboId == 0) return
        if (fboPool.size < 8) {
            fboPool.offer(fboId)
        } else {
            val fboArr = intArrayOf(fboId)
            GLES20.glDeleteFramebuffers(1, fboArr, 0)
        }
    }

    fun clear() {
        try {
            texturePool.forEach { (_, queue) ->
                while (true) {
                    val tex = queue.poll() ?: break
                    if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
                }
            }
            texturePool.clear()

            while (true) {
                val fbo = fboPool.poll() ?: break
                if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            }
            fboPool.clear()
        } catch (e: Throwable) {
            Log.w(TAG, "Exception during GlResourcePool cleanup", e)
        }
    }
}
