package com.termex.replay15.editor.render

import com.termex.replay15.editor.core.PreviewPacingProbe
import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.recly.editor.engine.R
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.domain.*
import kotlin.math.pow

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class StudioEffect(private val clip: VideoClip, private val background: Int, private val startUs: Long = 0L,
    private val preserveAlpha: Boolean = false) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        Program(context, useHdr, clip, background, startUs, preserveAlpha)

    private class Program(context: Context, hdr: Boolean, private val initialClip: VideoClip, background: Int,
        private val startUs: Long, preserveAlpha: Boolean) : BaseGlShaderProgram(hdr, 1) {
        private val compileStartedNs = if (PreviewPacingProbe.enabled) System.nanoTime() else 0L
        private val gl = try { GlProgram(context, R.raw.studio_vertex, R.raw.studio_fragment) }
            catch (e: Exception) { throw VideoFrameProcessingException(e) }
        private var lutTexture = 0
        init {
            if (context.isEditorDebuggable()) {
                Log.d("ReclyEffects", "SHADER_COMPILED asset=studio version=1")
            }
            if (compileStartedNs != 0L) PreviewPacingProbe.operation(
                "SHADER_COMPILE", "name=StudioEffect durationNs=${System.nanoTime() - compileStartedNs}")
            gl.setFloatUniform("uPreserveAlpha", if (preserveAlpha) 1f else 0f)
            gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            val g = initialClip.grade
            if (g.lutPath.isNotBlank()) {
                try {
                    val lut = com.termex.replay15.editor.media.CubeLut.parse(java.io.File(g.lutPath).reader())
                    val bitmap = android.graphics.Bitmap.createBitmap(lut.pixels(1f), lut.size, lut.size * lut.size, android.graphics.Bitmap.Config.ARGB_8888)
                    try { lutTexture = GlUtil.createTexture(bitmap) } finally { bitmap.recycle() }
                    gl.setFloatUniform("uLutSize", lut.size.toFloat())
                } catch (e: Exception) { throw VideoFrameProcessingException(e) }
            } else gl.setFloatUniform("uLutSize", 0f)
            gl.setFloatsUniform("uBackground", floatArrayOf(Color.red(background) / 255f, Color.green(background) / 255f,
                Color.blue(background) / 255f).map { it.pow(2.2f) }.toFloatArray())
        }
        private var inputWidth = 1
        private var inputHeight = 1
        private val lightUniform = FloatArray(3)
        private val detailUniform = FloatArray(3)
        private val curveUniform = FloatArray(4)
        private val channelUniforms = Array(3) { FloatArray(4) }
        private val channelEndsUniform = FloatArray(3)
        private val hslUniforms = Array(8) { FloatArray(3) }
        private val maskTransformUniform = FloatArray(4)
        private val keyEdgeUniform = FloatArray(3)
        private val chromaUniform = FloatArray(4)
        private val maskUniform = FloatArray(4)
        private val filterAdjustUniform = FloatArray(4)
        private val filterColorUniform = FloatArray(4)
        private val clipAdjustUniform = FloatArray(4)
        private val clipColorUniform = FloatArray(4)
        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            this.inputWidth = inputWidth; this.inputHeight = inputHeight
            gl.setFloatsUniform("uTexel", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            return Size(inputWidth, inputHeight)
        }
        private fun updateGrade(g: StudioGrade) {
            lightUniform[0] = g.exposure; lightUniform[1] = g.shadows; lightUniform[2] = g.highlights
            gl.setFloatsUniform("uLight", lightUniform)
            gl.setFloatUniform("uLutStrength", g.lutStrength)
            detailUniform[0] = g.vignette; detailUniform[1] = g.grain; detailUniform[2] = g.sharpen
            gl.setFloatsUniform("uDetail", detailUniform)
            for (i in 0..3) curveUniform[i] = g.curve[i]
            gl.setFloatsUniform("uCurve", curveUniform)
            gl.setFloatUniform("uCurveEnd", g.curve[4])
            g.channelCurves.forEachIndexed { index, curve ->
                for (i in 0..3) channelUniforms[index][i] = curve[i]
                channelEndsUniform[index] = curve[4]
                gl.setFloatsUniform("uChannel$index", channelUniforms[index])
            }
            gl.setFloatsUniform("uChannelEnds", channelEndsUniform)
            g.hsl.forEachIndexed { index, band ->
                hslUniforms[index][0] = band.hue / 360f
                hslUniforms[index][1] = band.saturation
                hslUniforms[index][2] = band.luminance
                gl.setFloatsUniform("uHsl$index", hslUniforms[index])
            }
            maskTransformUniform[0] = g.maskX; maskTransformUniform[1] = -g.maskY
            maskTransformUniform[2] = Math.toRadians(g.maskRotation.toDouble()).toFloat(); maskTransformUniform[3] = g.maskAspect
            gl.setFloatsUniform("uMaskTransform", maskTransformUniform)
            gl.setFloatUniform("uMaskOpacity", g.maskOpacity)
            val chroma = g.chromaKeyState()
            keyEdgeUniform[0] = chroma.smoothness; keyEdgeUniform[1] = chroma.spill; keyEdgeUniform[2] = chroma.edgeFeather
            gl.setFloatsUniform("uKeyEdge", keyEdgeUniform)
            chromaUniform[0] = Color.red(chroma.keyColor) / 255f; chromaUniform[1] = Color.green(chroma.keyColor) / 255f
            chromaUniform[2] = Color.blue(chroma.keyColor) / 255f; chromaUniform[3] = if (chroma.enabled) chroma.similarity else 0f
            gl.setFloatsUniform("uChroma", chromaUniform)
            val maskSize = if (g.mask == MaskShape.CINEMA) (inputWidth / inputHeight.toFloat() / 2.35f / 2f).coerceAtMost(.5f) else g.maskSize
            maskUniform[0] = g.mask.ordinal.toFloat(); maskUniform[1] = maskSize; maskUniform[2] = g.feather
            maskUniform[3] = if (g.invertMask) 1f else 0f
            gl.setFloatsUniform("uMask", maskUniform)
        }
        private fun updateFilter(clip: VideoClip) {
            val preset = VideoFilter.entries[clip.filter]
            val saturation = if (preset == VideoFilter.MONO) -1f else preset.saturation / 100f
            filterAdjustUniform[0] = preset.brightness; filterAdjustUniform[1] = preset.contrast
            filterAdjustUniform[2] = saturation; filterAdjustUniform[3] = preset.lightness / 100f
            gl.setFloatsUniform("uFilterAdjust", filterAdjustUniform)
            filterColorUniform[0] = Math.toRadians(preset.hue.toDouble()).toFloat()
            filterColorUniform[1] = preset.red; filterColorUniform[2] = preset.green; filterColorUniform[3] = preset.blue
            gl.setFloatsUniform("uFilterColor", filterColorUniform)
            gl.setFloatUniform("uFilterStrength", if (preset == VideoFilter.ORIGINAL) 0f else clip.filterStrength)
            gl.setFloatUniform("uFilterMode", when (preset) {
                VideoFilter.NEGATIVE -> 2f
                VideoFilter.MONO -> 1f
                else -> 0f
            })
        }
        private fun updateClipAdjustments(clip: VideoClip) {
            clipAdjustUniform.fill(0f)
            clipColorUniform[0] = 0f
            clipColorUniform[1] = 1f
            clipColorUniform[2] = 1f
            clipColorUniform[3] = 1f
            gl.setFloatsUniform("uClipAdjust", clipAdjustUniform)
            gl.setFloatsUniform("uClipColor", clipColorUniform)
        }
        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val started = PreviewPacingProbe.begin("Recly.StudioEffect.submitCPU")
            try { drawMeasured(inputTexId, presentationTimeUs) }
            finally { PreviewPacingProbe.end("Recly.StudioEffect.submitCPU", started) }
        }
        private fun drawMeasured(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val clip = initialClip
                gl.use()
                updateGrade(clip.grade)
                updateFilter(clip)
                updateClipAdjustments(clip)
                gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                gl.setSamplerTexIdUniform("uLut", if (lutTexture == 0) inputTexId else lutTexture, 1)
                val durationUs = clip.durationUs.coerceAtLeast(1L)
                val projectTimeUs = if (presentationTimeUs in startUs..(startUs + durationUs)) {
                    presentationTimeUs
                } else {
                    startUs + presentationTimeUs
                }
                val timelineUs = (projectTimeUs - startUs).coerceIn(0L, durationUs)
                gl.setFloatUniform("uTime", timelineUs / 1_000_000f)
                gl.setFloatUniform("uOpacity", clip.transformAt(clip.timeMap.sourceAt(timelineUs)).opacity)
                gl.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: Exception) { throw VideoFrameProcessingException(e, presentationTimeUs) }
        }
        override fun release() {
            try { if (lutTexture != 0) GlUtil.deleteTexture(lutTexture); gl.delete() }
            catch (e: Exception) { throw VideoFrameProcessingException(e) } finally { super.release() }
        }
    }
}
