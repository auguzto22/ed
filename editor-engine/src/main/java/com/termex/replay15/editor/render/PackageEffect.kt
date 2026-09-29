package com.termex.replay15.editor.render

import com.termex.replay15.editor.core.PreviewPacingProbe
import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.*
import androidx.media3.effect.*
import com.recly.editor.engine.R
import com.termex.replay15.editor.core.isEditorDebuggable
import com.termex.replay15.editor.assets.*
import com.termex.replay15.editor.domain.VideoClip

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PackageEffect(
    private val instance: EffectInstance,
    private val clip: VideoClip?,
    private val startUs: Long,
    private val durationUs: Long = clip?.durationUs ?: 1L,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = try {
        Program(context, useHdr, instance, clip, startUs, durationUs)
    } catch (error: Throwable) {
        val vendor = runCatching { GLES20.glGetString(GLES20.GL_VENDOR) }.getOrDefault("unknown")
        val renderer = runCatching { GLES20.glGetString(GLES20.GL_RENDERER) }.getOrDefault("unknown")
        val version = runCatching { GLES20.glGetString(GLES20.GL_VERSION) }.getOrDefault("unknown")
        Log.e("ReclyEffects", "Efeito desativado apos falha de shader asset=${instance.assetId} version=${instance.version} GPU=[$vendor, $renderer, $version]", error)
        Contrast(0f).toGlShaderProgram(context, useHdr)
    }
    private class Program(context: Context, hdr: Boolean, private val initialEffect: EffectInstance, private val initialClip: VideoClip?,
        private val startUs: Long, private val durationUs: Long) : BaseGlShaderProgram(hdr, 1) {
        private val resolved = AssetRepository(context).resolve(initialEffect.assetId, initialEffect.version)
        private val definition = resolved.first
        private val isTemporal = definition.temporal || definition.engine == EffectEngine.TEMPORAL_TRAIL
        private val resolutionUniform = FloatArray(2)
        private val maskUniform = FloatArray(4)
        private val maskTransformUniform = FloatArray(4)
        private val prevFboArr = IntArray(1)
        private val historyTextures = IntArray(3)
        private var copyFbo = 0
        private var historyInitialized = false
        private var lastPresentationTimeUs = Long.MIN_VALUE
        private var historyRing = 0
        private var inputWidth = 1
        private var inputHeight = 1

        private val compileStartedNs = if (PreviewPacingProbe.enabled) System.nanoTime() else 0L
        private val gl = GlProgram(
            getVertexHeader(context),
            getFragmentHeader(context) + "\n" +
            definition.parameters.joinToString("\n") { "uniform float p_${it.id};" } + "\n" + resolved.second +
            "\n" + getFragmentFooter(context)
        )
        init {
            gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            if (context.isEditorDebuggable()) {
                Log.d("ReclyEffects", "SHADER_COMPILED asset=${definition.id} version=${definition.version}")
            }
            if (compileStartedNs != 0L) PreviewPacingProbe.operation(
                "SHADER_COMPILE", "name=PackageEffect durationNs=${System.nanoTime() - compileStartedNs}")
        }
        private fun scalar(name: String, value: Float) { if (gl.getUniformLocation(name) >= 0) gl.setFloatUniform(name, value) }
        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            this.inputWidth = inputWidth
            this.inputHeight = inputHeight
            resolutionUniform[0] = this.inputWidth.toFloat(); resolutionUniform[1] = this.inputHeight.toFloat()
            if (gl.getUniformLocation("uResolution") >= 0) gl.setFloatsUniform("uResolution", resolutionUniform)
            if (isTemporal && historyTextures[0] == 0) {
                for (i in 0 until 3) {
                    historyTextures[i] = GlResourcePool.acquireTexture(this.inputWidth, this.inputHeight)
                }
                copyFbo = GlResourcePool.acquireFramebuffer()
                historyInitialized = false
            }
            return Size(this.inputWidth, this.inputHeight)
        }
        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val started = PreviewPacingProbe.begin("Recly.PackageEffect.submitCPU")
            try { drawMeasured(inputTexId, presentationTimeUs) }
            finally { PreviewPacingProbe.end("Recly.PackageEffect.submitCPU", started) }
        }
        private fun drawMeasured(inputTexId: Int, presentationTimeUs: Long) {
            try {
                if (isTemporal && inputWidth > 0 && inputHeight > 0 && copyFbo != 0) {
                    val jump = lastPresentationTimeUs == Long.MIN_VALUE ||
                        presentationTimeUs < lastPresentationTimeUs ||
                        (presentationTimeUs - lastPresentationTimeUs) > 300_000L
                    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFboArr, 0)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, copyFbo)
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, inputTexId, 0)
                    if (jump || !historyInitialized) {
                        for (i in 0 until 3) {
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTextures[i])
                            GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, inputWidth, inputHeight)
                        }
                        historyInitialized = true
                        historyRing = 0
                    } else {
                        historyRing = (historyRing + 1) % 3
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTextures[historyRing])
                        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, inputWidth, inputHeight)
                    }
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFboArr[0])
                    lastPresentationTimeUs = presentationTimeUs

                    val h0 = (historyRing - 1 + 3) % 3
                    val h1 = (historyRing - 2 + 3) % 3
                    val h2 = (historyRing - 3 + 3) % 3
                    if (gl.getUniformLocation("uHistory0") >= 0) gl.setSamplerTexIdUniform("uHistory0", historyTextures[h0], 1)
                    if (gl.getUniformLocation("uHistory1") >= 0) gl.setSamplerTexIdUniform("uHistory1", historyTextures[h1], 2)
                    if (gl.getUniformLocation("uHistory2") >= 0) gl.setSamplerTexIdUniform("uHistory2", historyTextures[h2], 3)
                }

                gl.use(); gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                val clip = initialClip
                val effect = clip?.effects?.firstOrNull { it.id == initialEffect.id }
                    ?: initialEffect
                val safeDurationUs = durationUs.coerceAtLeast(1L)
                val projectTimeUs = if (presentationTimeUs in startUs..(startUs + safeDurationUs)) {
                    presentationTimeUs
                } else {
                    startUs + presentationTimeUs
                }
                val time = (projectTimeUs - startUs).coerceIn(0L, safeDurationUs)
                val sourceUs = clip?.timeMap?.sourceAt(time) ?: projectTimeUs
                val intensity = if (effect.activeAt(sourceUs))
                    (effect.valueAt(EffectInstance.INTENSITY, sourceUs, effect.intensity) * effect.groupIntensity).coerceIn(0f, 1f) else 0f
                scalar("uIntensity", intensity)
                definition.parameters.forEach { parameter ->
                    scalar("p_${parameter.id}", effect.valueAt(parameter.id, sourceUs, effect.values[parameter.id] ?: parameter.default).coerceIn(parameter.min, parameter.max))
                }
                val mask = effect.mask ?: NO_MASK
                maskUniform[0] = mask.shape.ordinal.toFloat()
                maskUniform[1] = effect.valueAt(EffectInstance.MASK_FEATHER, sourceUs, mask.feather).coerceIn(.001f, .5f)
                maskUniform[2] = if (mask.invert) 1f else 0f
                maskUniform[3] = effect.valueAt(EffectInstance.MASK_OPACITY, sourceUs, mask.opacity).coerceIn(0f, 1f)
                if (gl.getUniformLocation("uEffectMask") >= 0) gl.setFloatsUniform("uEffectMask", maskUniform)
                maskTransformUniform[0] = effect.valueAt(EffectInstance.MASK_X, sourceUs, mask.x).coerceIn(-.5f, .5f)
                maskTransformUniform[1] = effect.valueAt(EffectInstance.MASK_Y, sourceUs, mask.y).coerceIn(-.5f, .5f)
                maskTransformUniform[2] = Math.toRadians(effect.valueAt(EffectInstance.MASK_ROTATION, sourceUs, mask.rotation).toDouble()).toFloat()
                maskTransformUniform[3] = effect.valueAt(EffectInstance.MASK_SIZE, sourceUs, mask.size).coerceIn(.05f, 2f)
                if (gl.getUniformLocation("uEffectMaskTransform") >= 0) gl.setFloatsUniform("uEffectMaskTransform", maskTransformUniform)
                scalar("uEffectMaskAspect", effect.valueAt(EffectInstance.MASK_ASPECT, sourceUs, mask.aspect).coerceIn(.1f, 10f))
                scalar("uBlendMode", effect.blendMode.ordinal.toFloat())
                val effectStartUs = effect.startTimeUs
                val effectEndUs = effect.endTimeUs
                val effectTimeUs = if (effectStartUs != null) (sourceUs - effectStartUs).coerceAtLeast(0L) else time
                val progress = if (effectStartUs != null && effectEndUs != null) {
                    (effectTimeUs.toFloat() / (effectEndUs - effectStartUs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                } else time.toFloat() / durationUs.coerceAtLeast(1L)
                scalar("uTime", effectTimeUs / 1_000_000f); scalar("uProgress", progress)
                gl.bindAttributesAndUniforms(); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4); GlUtil.checkGlError()
            } catch (e: Exception) { throw VideoFrameProcessingException(e, presentationTimeUs) }
        }
        override fun release() {
            try {
                if (isTemporal) {
                    if (copyFbo != 0) {
                        GlResourcePool.releaseFramebuffer(copyFbo)
                        copyFbo = 0
                    }
                    if (historyTextures[0] != 0) {
                        for (i in 0 until 3) {
                            GlResourcePool.releaseTexture(historyTextures[i], inputWidth, inputHeight)
                        }
                        historyTextures.fill(0)
                    }
                }
                gl.delete()
            } finally { super.release() }
        }
        companion object {
            private val NO_MASK = EffectMask()

            fun getVertexHeader(context: Context): String = EffectShaderHeaders.getVertexHeader(context)
            fun getFragmentHeader(context: Context): String = EffectShaderHeaders.getFragmentHeader(context)
            fun getFragmentFooter(context: Context): String = EffectShaderHeaders.getFragmentFooter(context)
        }
    }
}
