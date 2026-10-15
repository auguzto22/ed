package com.termex.replay15.editor.preview

import android.content.Context
import android.graphics.Bitmap
import android.opengl.*
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import com.termex.replay15.editor.assets.AssetRepository
import com.termex.replay15.editor.assets.EffectDefinition
import com.termex.replay15.editor.assets.EffectPreset
import com.termex.replay15.editor.assets.EffectPresetNode
import com.termex.replay15.editor.render.EffectShaderHeaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

@OptIn(UnstableApi::class)
class EffectThumbnailRenderer(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "ReclyEffectRenderer"

        /** Effect time covered by one loop (flash/strobe/punch effects pulse 1-3x per second). */
        private const val LOOP_SECONDS = 1.2f

        // Flipped quad coordinates so glReadPixels directly outputs a right-side-up Bitmap
        // without requiring CPU row reversing.
        private val FLIPPED_QUAD_COORDS = floatArrayOf(
            -1f,  1f, 0f, 1f,
             1f,  1f, 0f, 1f,
            -1f, -1f, 0f, 1f,
             1f, -1f, 0f, 1f
        )
    }

    private val repository = AssetRepository(context)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ReclyEffectThumbWorker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    @Volatile private var isClosed = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var currentWidth = 0
    private var currentHeight = 0
    private val pingPongTextures = IntArray(2)
    private val fbo = IntArray(1)
    private var pixelBuffer: ByteBuffer? = null

    private val programCache = mutableMapOf<String, GlProgram>()

    init {
        // Initialize EGL on the dedicated worker thread
        executor.submit {
            try {
                initEgl()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize offscreen EGL context", e)
            }
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) error("eglGetDisplay failed")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            error("eglInitialize failed")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            error("eglChooseConfig failed")
        }
        val config = configs[0] ?: error("EGLConfig is null")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) error("eglCreateContext failed")

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufferAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) error("eglCreatePbufferSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            error("eglMakeCurrent failed")
        }
    }

    private fun ensureSurfaceSize(width: Int, height: Int) {
        if (currentWidth == width && currentHeight == height && pingPongTextures[0] != 0) return

        if (pingPongTextures[0] != 0) {
            GLES20.glDeleteTextures(2, pingPongTextures, 0)
            pingPongTextures.fill(0)
        }
        if (fbo[0] != 0) {
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            fbo[0] = 0
        }

        currentWidth = width
        currentHeight = height

        GLES20.glGenTextures(2, pingPongTextures, 0)
        for (i in 0 until 2) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pingPongTextures[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
        }

        GLES20.glGenFramebuffers(1, fbo, 0)
        pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    }

    private fun getOrCompileProgram(definition: EffectDefinition, shaderCode: String): GlProgram {
        val key = "${definition.id}_${definition.version}"
        return programCache.getOrPut(key) {
            val vertex = EffectShaderHeaders.getVertexHeader(context)
            val fragment = EffectShaderHeaders.getFragmentHeader(context) + "\n" +
                definition.parameters.joinToString("\n") { "uniform float p_${it.id};" } + "\n" +
                shaderCode + "\n" +
                EffectShaderHeaders.getFragmentFooter(context)

            GlProgram(vertex, fragment).apply {
                setBufferAttribute("aFramePosition", FLIPPED_QUAD_COORDS, 4)
            }
        }
    }

    /**
     * Renders a multi-pass preset onto the provided base frame bitmap.
     * Returns a new Bitmap with the preset rendered, or null on error.
     */
    fun renderPreset(baseFrame: Bitmap, preset: EffectPreset, globalIntensity: Float = 1.0f): Future<Bitmap?> {
        return executor.submit(Callable {
            if (isClosed || baseFrame.isRecycled) return@Callable null
            try {
                renderPresetInternal(baseFrame, preset, globalIntensity)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to render preset thumbnail for ${preset.id}", e)
                null
            }
        })
    }

    /**
     * Renders a single effect definition onto the provided base frame bitmap.
     * Returns a new Bitmap with the effect rendered, or null on error.
     */
    fun renderEffect(baseFrame: Bitmap, definition: EffectDefinition, intensity: Float = 0.7f): Future<Bitmap?> {
        return executor.submit(Callable {
            if (isClosed || baseFrame.isRecycled) return@Callable null
            try {
                val node = EffectPresetNode(
                    assetId = definition.id,
                    version = definition.version,
                    intensity = intensity,
                    values = definition.parameters.associate { it.id to it.default },
                )
                val dummyPreset = EffectPreset(
                    id = definition.id,
                    name = definition.name,
                    category = definition.category,
                    nodes = listOf(node),
                )
                renderPresetInternal(baseFrame, dummyPreset, 1.0f)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to render effect thumbnail for ${definition.id}", e)
                null
            }
        })
    }

    /**
     * Renders a short looping clip of one effect over the animated library scene
     * ([EffectLoopBase]), so the library shows the effect moving (flashes pulse, zooms punch,
     * glitches flicker) instead of one still image.
     *
     * Frame i samples scene frame i and the three scene frames before it feed the history
     * samplers, so motion effects (trails, echo, motion blur) follow the moving balls. uTime and
     * uProgress sweep one loop of [LOOP_SECONDS].
     */
    fun renderEffectLoop(
        scene: List<Bitmap>,
        definition: EffectDefinition,
        intensity: Float = 0.7f,
    ): Future<List<Bitmap>?> {
        return executor.submit(Callable {
            if (isClosed || scene.size < 2 || scene.any { it.isRecycled }) return@Callable null
            try {
                renderLoopInternal(scene, definition, intensity)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to render effect loop for ${definition.id}", e)
                null
            }
        })
    }

    private fun renderLoopInternal(scene: List<Bitmap>, definition: EffectDefinition, intensity: Float): List<Bitmap>? {
        val frameCount = scene.size
        val w = scene[0].width
        val h = scene[0].height
        if (w <= 0 || h <= 0 || scene.any { it.width != w || it.height != h }) return null
        val (resolvedDefinition, shaderCode) = repository.resolve(definition.id, definition.version)
        val program = getOrCompileProgram(resolvedDefinition, shaderCode)
        ensureSurfaceSize(w, h)

        // One input texture per scene frame: frame i samples inputs[i], its history is i-1, i-2, i-3.
        val inputs = IntArray(frameCount)
        GLES20.glGenTextures(frameCount, inputs, 0)
        try {
            for (i in 0 until frameCount) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputs[i])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, scene[i], 0)
            }

            val frames = ArrayList<Bitmap>(frameCount)
            val effectiveIntensity = intensity.coerceIn(0f, 1f)
            GLES20.glViewport(0, 0, w, h)
            for (i in 0 until frameCount) {
                if (isClosed) { frames.forEach { it.recycle() }; return null }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, pingPongTextures[0], 0)
                program.use()
                program.sampler("uTexSampler", inputs[i], 0)
                program.sampler("uHistory0", inputs[(i - 1 + frameCount) % frameCount], 1)
                program.sampler("uHistory1", inputs[(i - 2 + frameCount) % frameCount], 2)
                program.sampler("uHistory2", inputs[(i - 3 + frameCount) % frameCount], 3)
                program.scalar("uIntensity", effectiveIntensity)
                program.vector("uResolution", floatArrayOf(w.toFloat(), h.toFloat()))
                program.scalar("uTime", LOOP_SECONDS * i / frameCount)
                program.scalar("uProgress", i / frameCount.toFloat())
                program.scalar("uBlendMode", 0f)
                program.vector("uEffectMask", floatArrayOf(0f, 0f, 0f, 0f))
                program.vector("uEffectMaskTransform", floatArrayOf(0f, 0f, 0f, 1f))
                program.scalar("uEffectMaskAspect", 1f)
                resolvedDefinition.parameters.forEach { param ->
                    program.scalar("p_${param.id}", param.default.coerceIn(param.min, param.max))
                }
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                val buf = pixelBuffer ?: return null
                buf.rewind()
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                buf.rewind()
                frames += Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf) }
            }
            return frames
        } finally {
            GLES20.glDeleteTextures(frameCount, inputs, 0)
        }
    }

    private fun renderPresetInternal(
        baseFrame: Bitmap,
        preset: EffectPreset,
        globalIntensity: Float,
    ): Bitmap? {
        val w = baseFrame.width
        val h = baseFrame.height
        if (w <= 0 || h <= 0) return null

        ensureSurfaceSize(w, h)

        // 1. Upload base frame into texture 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pingPongTextures[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, baseFrame, 0)

        var srcTexIdx = 0
        var dstTexIdx = 1

        GLES20.glViewport(0, 0, w, h)

        // 2. Execute each node sequentially via ping-pong textures
        preset.nodes.forEach { node ->
            val resolved = runCatching { repository.resolve(node.assetId, node.version) }.getOrNull()
            if (resolved != null) {
                val (definition, shaderCode) = resolved
                val program = getOrCompileProgram(definition, shaderCode)

                // Bind destination FBO
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    pingPongTextures[dstTexIdx],
                    0
                )

                // Programs are cached, so another effect's program may be current: uniforms
                // are only uploaded to the program in use.
                program.use()

                // Bind source texture
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pingPongTextures[srcTexIdx])
                program.sampler("uTexSampler", pingPongTextures[srcTexIdx], 0)

                // Also bind history samplers if shader requires them
                for (u in 1..3) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + u)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pingPongTextures[srcTexIdx])
                }
                program.sampler("uHistory0", pingPongTextures[srcTexIdx], 1)
                program.sampler("uHistory1", pingPongTextures[srcTexIdx], 2)
                program.sampler("uHistory2", pingPongTextures[srcTexIdx], 3)

                // Uniforms. Media3's GlProgram throws for a uniform the compiler removed
                // (unused by the shader); only 1 of the 72 effect shaders reads uHistory0, so
                // setting it unconditionally made every thumbnail fail (null = no preview).
                val effectiveIntensity = (node.intensity * globalIntensity).coerceIn(0f, 1f)
                program.scalar("uIntensity", effectiveIntensity)
                program.vector("uResolution", floatArrayOf(w.toFloat(), h.toFloat()))
                program.scalar("uTime", 0.5f)
                program.scalar("uProgress", 0.5f)
                program.scalar("uBlendMode", 0f)
                program.vector("uEffectMask", floatArrayOf(0f, 0f, 0f, 0f))
                program.vector("uEffectMaskTransform", floatArrayOf(0f, 0f, 0f, 1f))
                program.scalar("uEffectMaskAspect", 1f)

                definition.parameters.forEach { param ->
                    val paramVal = (node.values[param.id] ?: param.default).coerceIn(param.min, param.max)
                    program.scalar("p_${param.id}", paramVal)
                }

                // Draw quad
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                // Ping-pong for next pass
                srcTexIdx = dstTexIdx.also { dstTexIdx = srcTexIdx }
            }
        }

        // 3. Read pixels from the final result texture into Bitmap
        val finalResultTex = pingPongTextures[srcTexIdx]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            finalResultTex,
            0
        )

        val buf = pixelBuffer ?: return null
        buf.rewind()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()

        val outputBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outputBitmap.copyPixelsFromBuffer(buf)
        return outputBitmap
    }

    // Uniforms the shader does not use are skipped, like PackageEffect does for export.
    private fun GlProgram.scalar(name: String, value: Float) {
        if (getUniformLocation(name) >= 0) setFloatUniform(name, value)
    }

    private fun GlProgram.vector(name: String, value: FloatArray) {
        if (getUniformLocation(name) >= 0) setFloatsUniform(name, value)
    }

    private fun GlProgram.sampler(name: String, texId: Int, unit: Int) {
        if (getUniformLocation(name) >= 0) setSamplerTexIdUniform(name, texId, unit)
    }

    override fun close() {
        isClosed = true
        executor.submit {
            try {
                programCache.values.forEach { runCatching { it.delete() } }
                programCache.clear()

                if (pingPongTextures[0] != 0) {
                    GLES20.glDeleteTextures(2, pingPongTextures, 0)
                    pingPongTextures.fill(0)
                }
                if (fbo[0] != 0) {
                    GLES20.glDeleteFramebuffers(1, fbo, 0)
                    fbo[0] = 0
                }

                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error cleaning up offscreen EGL resources", e)
            } finally {
                executor.shutdown()
            }
        }
    }
}
