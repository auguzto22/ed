package com.termex.replay15.editor.render

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.recly.editor.engine.R
import com.termex.replay15.editor.assets.BuiltInTransitions
import com.termex.replay15.editor.assets.TransitionDefinition
import com.termex.replay15.editor.domain.TransitionWindow

private const val TAG = "TransitionEffect"

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

/** Draws a texture unchanged into the bound framebuffer. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class TextureCopy(context: Context) {
    private val program = GlProgram(TransitionShaderHeaders.getVertexHeader(context), PASS_THROUGH_FRAG).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
    }

    fun draw(texture: Int) {
        program.use()
        program.setSamplerTexIdUniform("uTex", texture, 0)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    fun delete() = program.delete()
}

/**
 * Last effect of the outgoing clip: keeps its frames from just before its transition window until
 * the end of it, for the incoming clip's shader. Its own output is unchanged; the compositor hides
 * it once the incoming clip carries the composited picture.
 *
 * [itemStartUs]..[itemEndUs] is where this media item sits in the composition, handles included.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TransitionCaptureEffect(
    private val bridgeKey: String,
    private val captureFromUs: Long,
    private val captureUntilUs: Long,
    private val itemStartUs: Long,
    private val itemEndUs: Long,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        CaptureProgram(context, bridgeKey, captureFromUs, captureUntilUs, itemStartUs, itemEndUs)

    private class CaptureProgram(
        context: Context,
        private val bridgeKey: String,
        private val captureFromUs: Long,
        private val captureUntilUs: Long,
        private val itemStartUs: Long,
        private val itemEndUs: Long,
    ) : BaseGlShaderProgram(false, 1) {
        private val copy = TextureCopy(context)
        private var width = 0
        private var height = 0

        init {
            TransitionBridge.retain(bridgeKey)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val timeUs = ExportClock.projectTimeUs(presentationTimeUs, itemStartUs, itemEndUs)
            if (timeUs >= captureFromUs && timeUs < captureUntilUs) {
                try {
                    TransitionBridge.captureTarget(bridgeKey, width, height, timeUs)?.drawInto { copy.draw(inputTexId) }
                } catch (error: Exception) {
                    TransitionBridge.discard(bridgeKey, timeUs)
                    Log.e(TAG, "Capture failed key=$bridgeKey at $timeUs: ${error.message}", error)
                }
            }
            copy.draw(inputTexId)
        }

        override fun release() {
            try {
                copy.delete()
                TransitionBridge.release(bridgeKey)
            } finally {
                super.release()
            }
        }
    }
}

/**
 * Last effect of the incoming clip: inside the transition window it draws the transition shader
 * from the outgoing frame kept by [TransitionCaptureEffect] (A) and its own frame (B). Outside the
 * window, or if the shader cannot run, its frame passes through and the compositor keeps a cut.
 *
 * [finishOnRelease] is set on the clip's main item, which always outlives the window.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TransitionRenderEffect(
    private val window: TransitionWindow,
    private val definition: TransitionDefinition,
    private val bridgeKey: String,
    private val itemStartUs: Long,
    private val itemEndUs: Long,
    private val finishOnRelease: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        RenderProgram(context, window, definition, bridgeKey, itemStartUs, itemEndUs, finishOnRelease)

    private class RenderProgram(
        context: Context,
        private val window: TransitionWindow,
        private val definition: TransitionDefinition,
        private val bridgeKey: String,
        private val itemStartUs: Long,
        private val itemEndUs: Long,
        private val finishOnRelease: Boolean,
    ) : BaseGlShaderProgram(false, 1) {
        private val copy = TextureCopy(context)
        private val resolution = FloatArray(2)
        private var uniforms: List<Pair<String, Float>> = emptyList()
        private var hasResolution = false
        private var hasProgress = false
        private var failures = 0
        private var finished = false

        private var shader: GlProgram? = try {
            val body = BuiltInTransitions.shader(context, definition.shaderFile)
            val fragment = TransitionShaderBuilder.buildFragmentShader(
                TransitionShaderHeaders.getFragmentHeader(context), body,
                TransitionShaderHeaders.getFragmentFooter(context), definition)
            GlProgram(TransitionShaderHeaders.getVertexHeader(context), fragment).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
                // Uniforms the compiler optimised away cannot be set; skip them once here.
                uniforms = TransitionShaderBuilder.uniformValues(body, definition, window.transition.parameters)
                    .filter { (name, _) -> getUniformLocation(name) >= 0 }.toList()
                hasResolution = getUniformLocation("uResolution") >= 0
                hasProgress = getUniformLocation("uProgress") >= 0
            }
        } catch (error: Exception) {
            Log.e(TAG, "TRANSITION_ERROR transitionId=${definition.id} shader=${definition.shaderFile} stage=compile " +
                "exception=${error.message} fallback=cut", error)
            null
        }

        init {
            TransitionBridge.retain(bridgeKey)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            resolution[0] = inputWidth.toFloat()
            resolution[1] = inputHeight.toFloat()
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val timeUs = ExportClock.projectTimeUs(presentationTimeUs, itemStartUs, itemEndUs)
            if (timeUs >= window.endUs) finish()
            val drawn = window.contains(timeUs) && drawTransition(inputTexId, timeUs)
            if (!drawn) copy.draw(inputTexId)
        }

        private fun drawTransition(inputTexId: Int, timeUs: Long): Boolean {
            val program = shader ?: return false
            val outgoing = TransitionBridge.frameFor(bridgeKey, timeUs)
            if (outgoing == 0) return false
            return try {
                program.use()
                program.setSamplerTexIdUniform("uTexA", outgoing, 0)
                program.setSamplerTexIdUniform("uTexB", inputTexId, 1)
                if (hasResolution) program.setFloatsUniform("uResolution", resolution)
                if (hasProgress) program.setFloatUniform("uProgress", window.progress(timeUs))
                uniforms.forEach { (name, value) -> program.setFloatUniform(name, value) }
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
                TransitionBridge.markShaderFrame(bridgeKey, timeUs)
                failures = 0
                true
            } catch (error: Exception) {
                failures++
                Log.e(TAG, "TRANSITION_ERROR transitionId=${definition.id} shader=${definition.shaderFile} stage=draw " +
                    "at=$timeUs failures=$failures exception=${error.message}", error)
                if (failures >= MAX_FAILURES) {
                    shader = null
                    program.delete()
                    TransitionBridge.markShaderFailed(bridgeKey)
                }
                false
            }
        }

        private fun finish() {
            if (finished) return
            finished = true
            TransitionBridge.finish(bridgeKey)
        }

        override fun release() {
            try {
                if (finishOnRelease) finish()
                shader?.delete()
                copy.delete()
                TransitionBridge.release(bridgeKey)
            } finally {
                super.release()
            }
        }

        private companion object {
            const val MAX_FAILURES = 3
        }
    }
}

/**
 * First effect of a hold item: repeats the item's first frame. Used where a clip has no media
 * before its in point but a transition shows it earlier, so the export holds the same frame as the
 * preview instead of freezing the other side or cutting early.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class HoldFirstFrameEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = HoldProgram(context)

    private class HoldProgram(context: Context) : BaseGlShaderProgram(false, 1) {
        private val copy = TextureCopy(context)
        private var width = 0
        private var height = 0
        private var held: GlFrameTarget? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (inputWidth != width || inputHeight != height) {
                held?.delete()
                held = null
            }
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            if (held == null) {
                held = GlFrameTarget.create(width, height)?.also { target -> target.drawInto { copy.draw(inputTexId) } }
            }
            copy.draw(held?.texture ?: inputTexId)
        }

        override fun release() {
            try {
                held?.delete()
                held = null
                copy.delete()
            } finally {
                super.release()
            }
        }
    }
}
