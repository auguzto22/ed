package com.termex.replay15.editor.render

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.termex.replay15.editor.domain.Camera3D

/** Camera on the composed scene, including text and stickers; timestamp is project-global. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SceneCameraEffect(private val track: Camera3D, private val background: Int) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean) = Program(track, background, useHdr)

    class Program(private val track: Camera3D, private val background: Int, hdr: Boolean) : BaseGlShaderProgram(hdr, 1) {
        private val matrix = Camera3D.SceneMatrix()
        private var aspect = 1f
        private val gl = GlProgram(
            "attribute vec4 aPosition; uniform mat4 uMvp; varying vec2 vUv; void main() { gl_Position = uMvp * aPosition; vUv = (aPosition.xy + 1.0) * 0.5; }",
            "precision mediump float; uniform sampler2D uTexture; varying vec2 vUv; void main() { gl_FragColor = texture2D(uTexture, vUv); }"
        ).apply { setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4) }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            aspect = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            check(inputTexId != 0) { "Missing scene texture at projectTimeUs=$presentationTimeUs" }
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(Color.red(background)/255f, Color.green(background)/255f, Color.blue(background)/255f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            gl.use()
            gl.setFloatsUniform("uMvp", matrix.evaluate(track.cameraAt(presentationTimeUs), aspect))
            gl.setSamplerTexIdUniform("uTexture", inputTexId, 0)
            gl.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }

        override fun release() {
            try { gl.delete() } finally { super.release() }
        }
    }
}
