package com.termex.replay15.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.recly.editor.engine.R
import com.termex.replay15.editor.backgroundremoval.SegmentationMaskTexture
import com.termex.replay15.editor.backgroundremoval.SegmentationResult
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.SegmentationQuality
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Synchronous Media3 export pass. Export has no playback deadline, so it waits for one ML Kit
 * result per frame and feeds the same real confidence mask into the GPU alpha shader.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class BackgroundRemovalExportEffect(
    private val settings: BackgroundRemovalEffect,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        Program(context, useHdr, settings)

    private class Program(
        context: Context,
        hdr: Boolean,
        private val settings: BackgroundRemovalEffect,
    ) : BaseGlShaderProgram(hdr, 1) {
        private val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .enableRawSizeMask()
                .build()
        )
        private val captureGl = GlProgram(VERTEX, CAPTURE)
        private val outputGl = GlProgram(VERTEX, OUTPUT)
        private val originalGl = GlProgram(VERTEX, ORIGINAL)
        private val maskTexture = SegmentationMaskTexture()
        private var captureTexture = 0
        private var captureFbo = 0
        private var captureWidth = 1
        private var captureHeight = 1
        private var inputWidth = 1
        private var inputHeight = 1
        private var pixels: ByteBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        private var argb = IntArray(1)
        private var bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val previousFramebuffer = IntArray(1)

        init {
            listOf(captureGl, outputGl, originalGl).forEach {
                it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            this.inputWidth = inputWidth.coerceAtLeast(2)
            this.inputHeight = inputHeight.coerceAtLeast(2)
            val (w, h) = inputSize(this.inputWidth, this.inputHeight)
            if (w != captureWidth || h != captureHeight) {
                if (captureTexture != 0) GlResourcePool.releaseTexture(captureTexture, captureWidth, captureHeight)
                if (captureFbo != 0) GlResourcePool.releaseFramebuffer(captureFbo)
                bitmap.recycle()
                captureWidth = w; captureHeight = h
                captureTexture = GlResourcePool.acquireTexture(w, h)
                captureFbo = GlResourcePool.acquireFramebuffer()
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
                argb = IntArray(w * h)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, captureTexture, 0)
            return Size(this.inputWidth, this.inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                capture(inputTexId)
                val mlMask = Tasks.await(
                    segmenter.process(InputImage.fromBitmap(bitmap, 0)),
                    10L,
                    TimeUnit.SECONDS,
                )
                val mask = mlMask.buffer.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }
                val values = FloatArray(mlMask.width * mlMask.height)
                for (index in values.indices) values[index] = mask.float.coerceIn(0f, 1f)
                maskTexture.upload(SegmentationResult(mlMask.width, mlMask.height, presentationTimeUs, values))
                outputGl.use()
                outputGl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                outputGl.setSamplerTexIdUniform("uMaskTexture", maskTexture.id, 1)
                outputGl.setFloatsUniform("uMaskTexel", floatArrayOf(1f / maskTexture.width, 1f / maskTexture.height))
                outputGl.setFloatUniform("uThreshold", settings.threshold)
                outputGl.setFloatUniform("uFeather", settings.feather.coerceAtLeast(.001f))
                outputGl.setFloatUniform("uEdgeSmoothing", settings.edgeSmoothing)
                outputGl.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (failure: Throwable) {
                // A transient model/device failure must preserve the source frame in the export.
                drawOriginal(inputTexId)
            }
        }

        private fun capture(inputTexId: Int) {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
            try {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFbo)
                GLES20.glViewport(0, 0, captureWidth, captureHeight)
                GLES20.glDisable(GLES20.GL_BLEND)
                captureGl.use()
                captureGl.setSamplerTexIdUniform("uTexture", inputTexId, 0)
                captureGl.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                pixels.clear()
                GLES20.glReadPixels(0, 0, captureWidth, captureHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                for (y in 0 until captureHeight) {
                    val destinationRow = captureHeight - 1 - y
                    val row = y * captureWidth * 4
                    for (x in 0 until captureWidth) {
                        val offset = row + x * 4
                        val r = pixels.get(offset).toInt() and 0xFF
                        val g = pixels.get(offset + 1).toInt() and 0xFF
                        val b = pixels.get(offset + 2).toInt() and 0xFF
                        val a = pixels.get(offset + 3).toInt() and 0xFF
                        argb[destinationRow * captureWidth + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                bitmap.setPixels(argb, 0, captureWidth, 0, 0, captureWidth, captureHeight)
            } finally {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
            }
        }

        private fun drawOriginal(inputTexId: Int) {
            originalGl.use()
            originalGl.setSamplerTexIdUniform("uTexture", inputTexId, 0)
            originalGl.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            runCatching { segmenter.close() }
            maskTexture.release()
            if (captureTexture != 0) GlResourcePool.releaseTexture(captureTexture, captureWidth, captureHeight)
            if (captureFbo != 0) GlResourcePool.releaseFramebuffer(captureFbo)
            bitmap.recycle()
            captureGl.delete(); outputGl.delete(); originalGl.delete()
            super.release()
        }

        private fun inputSize(width: Int, height: Int): Pair<Int, Int> {
            val longest = when (settings.quality) {
                SegmentationQuality.FAST -> 256
                SegmentationQuality.BALANCED -> 384
                SegmentationQuality.HIGH -> 512
            }
            val scale = longest.toFloat() / max(width, height).coerceAtLeast(1)
            return (width * scale).roundToInt().coerceAtLeast(2) to (height * scale).roundToInt().coerceAtLeast(2)
        }
    }

    private companion object {
        private const val VERTEX = "attribute vec4 aFramePosition; varying vec2 vUv; void main(){ gl_Position=aFramePosition; vUv=(aFramePosition.xy+1.0)*0.5; }"
        private const val CAPTURE = "precision mediump float; uniform sampler2D uTexture; varying vec2 vUv; void main(){ gl_FragColor=texture2D(uTexture,vUv); }"
        private const val ORIGINAL = "precision mediump float; uniform sampler2D uTexture; varying vec2 vUv; void main(){ gl_FragColor=texture2D(uTexture,vUv); }"
        private const val OUTPUT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uMaskTexture;
            uniform vec2 uMaskTexel;
            uniform float uThreshold;
            uniform float uFeather;
            uniform float uEdgeSmoothing;
            varying vec2 vUv;
            float maskAt(vec2 p) { return texture2D(uMaskTexture, clamp(p, 0.0, 1.0)).r; }
            void main() {
                vec4 source = texture2D(uTexSampler, vUv);
                float center = maskAt(vUv);
                float around = (maskAt(vUv + vec2(uMaskTexel.x,0.0)) + maskAt(vUv - vec2(uMaskTexel.x,0.0)) +
                    maskAt(vUv + vec2(0.0,uMaskTexel.y)) + maskAt(vUv - vec2(0.0,uMaskTexel.y))) * 0.25;
                float mask = mix(center, around, clamp(uEdgeSmoothing,0.0,1.0) * 0.35);
                float alpha = smoothstep(uThreshold-uFeather, uThreshold+uFeather, mask);
                gl_FragColor = vec4(source.rgb, source.a * alpha);
            }
        """
    }
}
