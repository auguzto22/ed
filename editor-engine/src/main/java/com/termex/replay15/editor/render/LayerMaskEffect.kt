package com.termex.replay15.editor.render

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.maskAt

/** Export counterpart of [com.termex.replay15.editor.preview.engine.LayerMaskGpuPass]. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class LayerMaskEffect(private val clip: VideoClip, private val startUs: Long) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        Program(useHdr, clip, startUs)

    private class Program(
        useHdr: Boolean,
        private val clip: VideoClip,
        private val startUs: Long,
    ) : BaseGlShaderProgram(useHdr, 1) {
        private val gl = GlProgram(VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        }
        private val centerSize = FloatArray(4)
        private val params = FloatArray(4)
        private val meta = FloatArray(3)
        private val path = Array(MaskState.MAX_MASK_PATH_POINTS) { FloatArray(2) }

        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val durationUs = clip.durationUs.coerceAtLeast(1L)
                val projectTimeUs = if (presentationTimeUs in startUs..(startUs + durationUs)) {
                    presentationTimeUs
                } else {
                    startUs + presentationTimeUs
                }
                val timelineUs = (projectTimeUs - startUs).coerceIn(0L, durationUs)
                val sourceUs = clip.timeMap.sourceAt(timelineUs)
                val mask = requireNotNull(clip.maskAt(sourceUs))
                gl.use()
                gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                setMask(mask)
                gl.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (error: Exception) {
                throw VideoFrameProcessingException(error, presentationTimeUs)
            }
        }

        private fun setMask(mask: MaskState) {
            centerSize[0] = mask.centerX
            centerSize[1] = mask.centerY
            centerSize[2] = mask.width
            centerSize[3] = mask.height
            params[0] = Math.toRadians(mask.rotation.toDouble()).toFloat()
            params[1] = mask.feather
            params[2] = mask.expansion
            params[3] = mask.opacity
            meta[0] = mask.type.ordinal + 1f
            meta[1] = if (mask.inverted) 1f else 0f
            meta[2] = mask.customPath.size.toFloat()
            gl.setFloatsUniform("uLayerMaskCenterSize", centerSize)
            gl.setFloatsUniform("uLayerMaskParams", params)
            gl.setFloatsUniform("uLayerMaskMeta", meta)
            for (index in path.indices) {
                val point = mask.customPath.getOrNull(index)
                path[index][0] = point?.x ?: .5f
                path[index][1] = point?.y ?: .5f
                gl.setFloatsUniform("uLayerMaskPath$index", path[index])
            }
        }

        override fun release() {
            try {
                gl.delete()
            } catch (error: Exception) {
                throw VideoFrameProcessingException(error)
            } finally {
                super.release()
            }
        }
    }

    private companion object {
        const val VERTEX = "attribute vec4 aFramePosition; varying vec2 vUv; void main(){gl_Position=aFramePosition;vUv=(aFramePosition.xy+1.0)*0.5;}"
        val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            varying vec2 vUv;
            ${LayerMaskShader.UNIFORMS}
            ${LayerMaskShader.FUNCTIONS}
            void main(){
                vec4 source=texture2D(uTexSampler,vUv);
                gl_FragColor=vec4(source.rgb,source.a*reclyLayerMaskAlpha(vUv));
            }
        """.trimIndent()
    }
}
