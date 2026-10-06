package com.termex.replay15.editor.render

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.*
import androidx.media3.effect.*
import com.recly.editor.engine.R
import com.termex.replay15.editor.assets.BuiltInTransitions
import com.termex.replay15.editor.assets.TransitionDefinition
import com.termex.replay15.editor.assets.TransitionInstance

private const val PASS_THROUGH_FRAG = """
    precision mediump float;
    uniform sampler2D uTex;
    varying vec2 vUv;
    void main() {
        gl_FragColor = texture2D(uTex, vUv);
    }
"""

internal object TransitionShaderHeaders {
    private var cachedVertexHeader: String? = null
    private var cachedFragmentHeader: String? = null
    private var cachedFragmentFooter: String? = null

    fun getVertexHeader(context: Context): String =
        cachedVertexHeader ?: synchronized(this) {
            cachedVertexHeader ?: context.resources.openRawResource(R.raw.studio_vertex)
                .bufferedReader().use { it.readText() }.also { cachedVertexHeader = it }
        }

    fun getFragmentHeader(context: Context): String =
        cachedFragmentHeader ?: synchronized(this) {
            cachedFragmentHeader ?: context.resources.openRawResource(R.raw.transition_header)
                .bufferedReader().use { it.readText() }.also { cachedFragmentHeader = it }
        }

    fun getFragmentFooter(context: Context): String =
        cachedFragmentFooter ?: synchronized(this) {
            cachedFragmentFooter ?: context.resources.openRawResource(R.raw.transition_footer)
                .bufferedReader().use { it.readText() }.also { cachedFragmentFooter = it }
        }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TransitionCaptureEffect(
    private val transitionId: String,
    private val bridgeKey: String,
    private val captureStartUs: Long,
    private val clipDurationUs: Long,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        CaptureProgram(context, transitionId, bridgeKey, captureStartUs, clipDurationUs)

    private class CaptureProgram(
        context: Context,
        private val transitionId: String,
        private val bridgeKey: String,
        private val captureStartUs: Long,
        private val clipDurationUs: Long,
    ) : BaseGlShaderProgram(false, 1) {
        private var width = 1
        private var height = 1
        private val previousFramebuffer = IntArray(1)
        private val previousViewport = IntArray(4)

        private val passThroughGl = GlProgram(TransitionShaderHeaders.getVertexHeader(context), PASS_THROUGH_FRAG).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        }

        init {
            TransitionBridge.retain(bridgeKey)
        }

        private var hasCapturedAny = false

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                // Media3 can supply composition or item presentation timestamps depending on
                // the sequence path. Keep the latest available source frame for either clock.
                val shouldCapture = !hasCapturedAny ||
                    presentationTimeUs >= captureStartUs - 1_500_000L ||
                    presentationTimeUs >= clipDurationUs - 1_500_000L ||
                    presentationTimeUs >= (clipDurationUs * 0.7f).toLong()

                if (shouldCapture && width > 0 && height > 0) {
                    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
                    GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)

                    val (_, fboId) = TransitionBridge.acquireCaptureTarget(bridgeKey, width, height)
                    if (fboId != 0) {
                        try {
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                            GLES20.glViewport(0, 0, width, height)
                            passThroughGl.use()
                            passThroughGl.setSamplerTexIdUniform("uTex", inputTexId, 0)
                            passThroughGl.bindAttributesAndUniforms()
                            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                            GlUtil.checkGlError()
                            GLES20.glFlush()
                            TransitionBridge.markCaptured(bridgeKey)
                            hasCapturedAny = true
                        } finally {
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
                            GLES20.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
                        }
                    }
                }
                passThroughGl.use()
                passThroughGl.setSamplerTexIdUniform("uTex", inputTexId, 0)
                passThroughGl.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: Exception) {
                Log.e("TransitionEffect", "CaptureProgram.drawFrame failed at timeUs=$presentationTimeUs trans=$transitionId: ${e.message}", e)
                // Fallback: draw passthrough without killing player
                runCatching {
                    passThroughGl.use()
                    passThroughGl.setSamplerTexIdUniform("uTex", inputTexId, 0)
                    passThroughGl.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
            }
        }

        override fun release() {
            try {
                passThroughGl.delete()
                TransitionBridge.release(bridgeKey)
            } finally {
                super.release()
            }
        }
    }
}

object TransitionShaderBuilder {
    private val UNIFORM_REGEX = Regex("""uniform\s+\w+\s+([A-Za-z0-9_]+)\s*;""")
    private val BODY_PARAM_REGEX = Regex("""\bp_([a-zA-Z0-9_]+)\b""")

    fun validateShaderBody(body: String, definition: TransitionDefinition) {
        val declaredUniforms = UNIFORM_REGEX.findAll(body).map { it.groupValues[1] }.toList()
        require(declaredUniforms.size == declaredUniforms.distinct().size) {
            "TRANSITION SHADER VALIDATION FAILED: Shader '${definition.shaderFile}' declares duplicate uniforms."
        }
    }

    fun findBodyParamUniforms(body: String): Set<String> {
        return BODY_PARAM_REGEX.findAll(body).map { it.groupValues[1] }.toSet()
    }

    fun buildParameterUniforms(
        body: String,
        definition: TransitionDefinition
    ): String {
        val bodyParams = findBodyParamUniforms(body)
        val declaredIds = UNIFORM_REGEX.findAll(body).map { it.groupValues[1] }
            .filter { it.startsWith("p_") }.map { it.removePrefix("p_") }.toSet()
        val allParamIds = (definition.parameters.map { it.id }.toSet() + bodyParams - declaredIds).sorted()
        return allParamIds.joinToString("\n") { "uniform float p_$it;" }
    }

    fun validateNoDuplicateUniforms(shader: String) {
        val names = UNIFORM_REGEX
            .findAll(shader)
            .map { it.groupValues[1] }
            .toList()

        val duplicates = names
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }

        require(duplicates.isEmpty()) {
            "Duplicate GLSL uniforms: ${duplicates.keys}"
        }
    }

    fun buildFragmentShader(
        header: String,
        body: String,
        footer: String,
        definition: TransitionDefinition
    ): String {
        validateShaderBody(body, definition)
        val bodyUniforms = UNIFORM_REGEX.findAll(body).map { it.groupValues[1] }.toSet()
        val sanitizedHeader = header.lines().filterNot { line ->
            val match = UNIFORM_REGEX.find(line)
            match != null && match.groupValues[1] in bodyUniforms
        }.joinToString("\n")

        val generatedUniforms = buildParameterUniforms(body, definition)
        val finalShader = if (generatedUniforms.isNotBlank()) {
            "$sanitizedHeader\n$generatedUniforms\n$body\n$footer"
        } else {
            "$sanitizedHeader\n$body\n$footer"
        }
        validateNoDuplicateUniforms(finalShader)
        return finalShader
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TransitionRenderEffect(
    private val transition: TransitionInstance,
    private val definition: TransitionDefinition,
    private val bridgeKey: String,
    private val transitionStartUs: Long,
    private val durationUs: Long,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return try {
            RenderProgram(context, transition, definition, bridgeKey, transitionStartUs, durationUs)
        } catch (error: Exception) {
            Log.e("TransitionEffect", "TRANSITION_ERROR transitionId=${definition.id} shader=${definition.shaderFile} stage=init exception=${error.message} fallback=PassThroughProgram", error)
            PassThroughProgram(context)
        }
    }

    private class PassThroughProgram(context: Context) : BaseGlShaderProgram(false, 1) {
        private val passThroughGl = GlProgram(TransitionShaderHeaders.getVertexHeader(context), PASS_THROUGH_FRAG).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        }


        override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                passThroughGl.use()
                passThroughGl.setSamplerTexIdUniform("uTex", inputTexId, 0)
                passThroughGl.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: Exception) {
                Log.e("TransitionEffect", "PassThroughProgram.drawFrame failed at timeUs=$presentationTimeUs: ${e.message}", e)
            }
        }

        override fun release() {
            try {
                passThroughGl.delete()
            } finally {
                super.release()
            }
        }
    }

    private class RenderProgram(
        context: Context,
        private val transition: TransitionInstance,
        private val definition: TransitionDefinition,
        private val bridgeKey: String,
        private val transitionStartUs: Long,
        private val durationUs: Long,
    ) : BaseGlShaderProgram(false, 1) {
        private val resolutionUniform = FloatArray(2)
        private var width = 1
        private var height = 1
        private var bodyUniformsNeeded: Set<String> = emptySet()
        private var consecutiveErrors = 0
        private val maxConsecutiveErrors = 3
        private var shaderDisabled = false

        private val gl: GlProgram? = try {
            val header = TransitionShaderHeaders.getFragmentHeader(context)
            val footer = TransitionShaderHeaders.getFragmentFooter(context)
            val shaderBody = BuiltInTransitions.shader(context, definition.shaderFile)
            bodyUniformsNeeded = TransitionShaderBuilder.findBodyParamUniforms(shaderBody)
            val fullFragment = TransitionShaderBuilder.buildFragmentShader(header, shaderBody, footer, definition)
            GlProgram(
                TransitionShaderHeaders.getVertexHeader(context),
                fullFragment
            ).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
        } catch (error: Exception) {
            Log.e("TransitionEffect", "TRANSITION_ERROR transitionId=${definition.id} shader=${definition.shaderFile} stage=compile exception=${error.message} fallback=PassThroughProgram", error)
            bodyUniformsNeeded = emptySet()
            null
        }

        private val passThroughGl = GlProgram(TransitionShaderHeaders.getVertexHeader(context), PASS_THROUGH_FRAG).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        }
        private val supplementalUniforms = bodyUniformsNeeded
            .filterNot { id -> definition.parameters.any { it.id == id } }
            .map { id ->
                id to when (id) {
                    "perspective" -> 1.0f
                    "radius" -> 16.0f
                    "softness" -> 0.1f
                    "intensity", "strength", "blur" -> 1.0f
                    else -> 0.0f
                }
            }
        private var missingCaptureLogged = false

        init {
            TransitionBridge.retain(bridgeKey)
            TransitionBridge.setRendererReady(bridgeKey, gl != null)
        }

        private fun scalar(name: String, value: Float) {
            val program = gl ?: return
            if (program.getUniformLocation(name) >= 0) program.setFloatUniform(name, value)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            resolutionUniform[0] = inputWidth.toFloat()
            resolutionUniform[1] = inputHeight.toFloat()
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val globalElapsedUs = presentationTimeUs - transitionStartUs
                val elapsedUs = if (globalElapsedUs in 0L..durationUs) globalElapsedUs else presentationTimeUs
                val inWindow = elapsedUs in 0L..durationUs
                val texA = TransitionBridge.getTexture(bridgeKey)
                val current = transition

                val transitionGl = gl
                if (inWindow && texA != 0 && transitionGl != null && !shaderDisabled) {
                    try {
                        missingCaptureLogged = false
                        transitionGl.use()
                        transitionGl.setSamplerTexIdUniform("uTexA", texA, 0)
                        transitionGl.setSamplerTexIdUniform("uTexB", inputTexId, 1)
                        if (transitionGl.getUniformLocation("uResolution") >= 0) {
                            transitionGl.setFloatsUniform("uResolution", resolutionUniform)
                        }

                        val rawProgress = (elapsedUs.toDouble() / durationUs.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
                        val progress = current.easing.apply(rawProgress, current.bezier)
                        scalar("uProgress", progress)

                        definition.parameters.forEach { param ->
                            val value = current.parameters[param.id] ?: param.default
                            scalar("p_${param.id}", value.coerceIn(param.min, param.max))
                        }

                        supplementalUniforms.forEach { (id, defaultValue) ->
                            scalar("p_$id", defaultValue)
                        }

                        transitionGl.bindAttributesAndUniforms()
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                        GlUtil.checkGlError()
                        consecutiveErrors = 0
                    } catch (drawError: Exception) {
                        consecutiveErrors++
                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            shaderDisabled = true
                            Log.e("TransitionEffect", "TRANSITION_ERROR transitionId=${definition.id} shader=${definition.shaderFile} stage=drawFrame exception=${drawError.message} fallback=disabled_permanently", drawError)
                        } else {
                            Log.e("TransitionEffect", "TRANSITION_ERROR transitionId=${definition.id} shader=${definition.shaderFile} stage=drawFrame exception=${drawError.message} fallback=PassThroughProgram", drawError)
                        }
                        passThroughGl.use()
                        passThroughGl.setSamplerTexIdUniform("uTex", inputTexId, 0)
                        passThroughGl.bindAttributesAndUniforms()
                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                        GlUtil.checkGlError()
                    }
                } else {
                    if (inWindow && !missingCaptureLogged) {
                        missingCaptureLogged = true
                        Log.w(
                            "TransitionEffect",
                            "TRANSITION_FALLBACK trans=${transition.id} key=$bridgeKey " +
                                "captureReady=${TransitionBridge.hasCapturedFrame(bridgeKey)} " +
                                "shaderReady=${transitionGl != null} shaderDisabled=$shaderDisabled",
                        )
                    }
                    passThroughGl.use()
                    passThroughGl.setSamplerTexIdUniform("uTex", inputTexId, 0)
                    passThroughGl.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GlUtil.checkGlError()
                }
            } catch (e: Exception) {
                Log.e("TransitionEffect", "RenderProgram.drawFrame failed at timeUs=$presentationTimeUs trans=${transition.id}: ${e.message}", e)
                runCatching {
                    passThroughGl.use()
                    passThroughGl.setSamplerTexIdUniform("uTex", inputTexId, 0)
                    passThroughGl.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                }
            }
        }

        override fun release() {
            try {
                TransitionBridge.setRendererReady(bridgeKey, false)
                // Exactly one release: the effect retained the key once, so a second decrement
                // freed the capture texture while TrackCompositor could still be reading it.
                TransitionBridge.release(bridgeKey)
                gl?.delete()
                passThroughGl.delete()
            } finally {
                super.release()
            }
        }
    }
}
