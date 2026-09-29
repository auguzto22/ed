package com.termex.replay15.editor.backgroundremoval

import android.opengl.GLES20
import java.nio.ByteBuffer

/** A reusable single-channel GL texture for a low-resolution confidence mask. */
class SegmentationMaskTexture {
    var id: Int = 0
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    private var uploadBuffer: ByteBuffer? = null

    fun ensure(maskWidth: Int, maskHeight: Int) {
        require(maskWidth > 0 && maskHeight > 0)
        if (id != 0 && width == maskWidth && height == maskHeight) return
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        id = 0
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        id = ids[0]
        width = maskWidth
        height = maskHeight
        uploadBuffer = ByteBuffer.allocateDirect(maskWidth * maskHeight)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, maskWidth, maskHeight, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null,
        )
    }

    fun upload(result: SegmentationResult) {
        ensure(result.width, result.height)
        val bytes = requireNotNull(uploadBuffer).apply { clear() }
        // ML Kit exposes rows top-to-bottom. OpenGL texture data is uploaded bottom-to-top so
        // the mask uses precisely the same UV convention as the decoded 2D video texture.
        for (y in result.height - 1 downTo 0) {
            val row = y * result.width
            for (x in 0 until result.width) {
                bytes.put((result.mask[row + x].coerceIn(0f, 1f) * 255f + .5f).toInt().toByte())
            }
        }
        bytes.flip()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bytes,
        )
    }

    fun release() {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        id = 0
        width = 0
        height = 0
        uploadBuffer = null
    }
}
