package com.termex.replay15.editor.preview.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.termex.replay15.editor.assets.AssetRepository
import com.termex.replay15.editor.assets.BuiltInTransitions
import com.termex.replay15.editor.assets.TransitionCatalog
import com.termex.replay15.editor.backgroundremoval.GeminiBitmapMaskApplier
import com.termex.replay15.editor.domain.CanvasFill
import com.termex.replay15.editor.domain.TextAnimation
import com.termex.replay15.editor.domain.chromaKeyState
import com.termex.replay15.editor.domain.maskAt
import com.termex.replay15.editor.render.EffectShaderHeaders
import com.termex.replay15.editor.render.CanvasLayerMask
import com.termex.replay15.editor.render.TextLayout
import com.termex.replay15.editor.render.TransitionShaderBuilder
import com.termex.replay15.editor.render.TransitionShaderHeaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.sin

/** Persistent EGL renderer. Paused rendering is event-driven and accepted OES frames are cached in 2D textures. */
class GpuPreviewRenderer(
    private val context: Context,
    private val view: SurfaceView,
    private val isGenerationCurrent: (Long) -> Boolean,
    private val transportPositionUs: () -> Long,
    private val diagnostics: PreviewDiagnostics,
    private val isPlaying: () -> Boolean,
    private val onFramePresented: (Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) : SurfaceHolder.Callback, AutoCloseable {
    data class InputTarget(val surface: Surface)
    private data class Slot(
        val key: ActiveClipResolver.ClipKey,
        val width: Int,
        val height: Int,
        val oes: Int,
        val texture: Int,
        val fbo: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        val pending: AtomicReference<VideoDecoderSession.Frame?> = AtomicReference(),
        var hasFrame: Boolean = false,
        var frameGeneration: Long = -1,
    )
    private data class EffectTarget(val width:Int,val height:Int,val textures:IntArray,val fbos:IntArray)
    private data class EffectProgram(val id:Int,val defaults:Map<String,Float>)
    private data class TransitionProgram(val id:Int,val defaults:Map<String,Float>,val supplemental:Set<String>)

    private val thread = HandlerThread("PreviewRender").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(context.mainLooper)
    private val imageWorker = Executors.newSingleThreadExecutor()
    private val released = AtomicBoolean()
    private val state = AtomicReference<PreviewRenderState?>(null)
    private val renderScheduled = AtomicBoolean()
    private val displayGeneration = AtomicLong()
    private val slots = LinkedHashMap<ActiveClipResolver.ClipKey, Slot>()
    private val effectTargets = HashMap<ActiveClipResolver.ClipKey,EffectTarget>()
    private val effectPrograms = HashMap<String,EffectProgram>()
    private val transitionPrograms=HashMap<String,TransitionProgram>()
    private val failedEffects = HashSet<String>()
    private var transitionTarget:EffectTarget?=null
    private var transitionCompositeTarget:EffectTarget?=null
    private var sceneTarget: EffectTarget? = null
    private var sceneFramebuffer = 0
    private val cameraMatrix = com.termex.replay15.editor.domain.Camera3D.SceneMatrix()
    private val assets by lazy { AssetRepository(context) }
    private val imageTextures = HashMap<String, Int>()
    private val imageSizes=HashMap<String,Pair<Int,Int>>()
    private val imagePending = ConcurrentHashMap.newKeySet<String>()
    private val overlayPending=AtomicBoolean()
    private var overlayWorkBitmap:Bitmap?=null
    private val overlayStickerBitmaps=HashMap<String,Bitmap>()
    private val overlayStickerSources=HashMap<String,Bitmap>()
    private var overlayMaskApplier: GeminiBitmapMaskApplier? = null
    private val overlayTextLayouts=HashMap<Int,TextLayout.Block>()
    private var overlayTexture = 0
    private var overlaySignature = Long.MIN_VALUE
    private var display = EGL14.EGL_NO_DISPLAY
    @Volatile private var eglContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    @Volatile private var window = EGL14.EGL_NO_SURFACE
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var windowSurface: Surface? = null
    @Volatile private var outputWidth = 1
    @Volatile private var outputHeight = 1
    private var oesProgram = 0
    private var drawProgram = 0
    private var overlayProgram = 0
    private var backgroundRemovalPass: BackgroundRemovalGpuPass? = null
    private var layerMaskPass: LayerMaskGpuPass? = null
    private var textRenderer: TextOverlayRenderer? = null
    private val frameScheduler = VideoFrameScheduler()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }
    private val stMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    private val motionBuf = FloatArray(4) // reusable buffer for overlayMotion, avoids per-frame allocation
    @Volatile private var lastRenderedState: PreviewRenderState? = null

    @Volatile private var decoderSurfaceCount = 0
    private lateinit var choreographer: Choreographer

    init {
        val initialSurface = view.holder.surface
        val initialWidth = view.width
        val initialHeight = view.height
        view.holder.addCallback(this)
        handler.post {
            runCatching {
                choreographer = Choreographer.getInstance()
                initEgl()
                if (displayGeneration.get() == 0L && initialSurface.isValid)
                    attach(initialSurface, initialWidth.coerceAtLeast(1), initialHeight.coerceAtLeast(1))
            }.onFailure(::fail)
        }
    }

    val width: Int get() = outputWidth
    val height: Int get() = outputHeight
    val isDisplayAttached: Boolean get() = window != EGL14.EGL_NO_SURFACE
    val isGlReady: Boolean get() = eglContext != EGL14.EGL_NO_CONTEXT
    fun hasAnyDecoderSurface(): Boolean = decoderSurfaceCount > 0

    override fun surfaceCreated(holder: SurfaceHolder) = attachOutput(holder.surface, view.width, view.height)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = attachOutput(holder.surface, width, height)
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val gen = displayGeneration.incrementAndGet()
        // SurfaceHolder requires rendering to stop before this callback returns.
        val detached = CountDownLatch(1)
        if (handler.post {
                try { detachWindow() } finally { detached.countDown() }
            }) detached.await()
        diagnostics.displayDetached(gen)
    }

    private fun attachOutput(surface: Surface, width: Int, height: Int) {
        outputWidth = width.coerceAtLeast(1); outputHeight = height.coerceAtLeast(1)
        val gen = displayGeneration.incrementAndGet()
        handler.post {
            if (released.get() || gen != displayGeneration.get()) return@post // stale callback
            runCatching { attach(surface, width, height) }.onFailure(::fail)
            diagnostics.displayAttached(gen)
        }
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val choices = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE),
            0, choices, 0, 1, count, 0) && count[0] > 0)
        config = choices[0]
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext))
        oesProgram = program(VERTEX, OES_COPY)
        drawProgram = program(VERTEX, DRAW)
        overlayProgram = program(VERTEX, OVERLAY)
        backgroundRemovalPass = BackgroundRemovalGpuPass(context) { requestRender() }
        layerMaskPass = LayerMaskGpuPass()
        textRenderer = TextOverlayRenderer(context, imageWorker, { task -> handler.post { task() } }, { state.get() },
            { requestRender() }, diagnostics)
        diagnostics.eglCreated()
    }

    private fun attach(surface: Surface, width: Int, height: Int) {
        if (released.get() || display == EGL14.EGL_NO_DISPLAY) return
        if (!surface.isValid) return
        if (windowSurface !== surface || window == EGL14.EGL_NO_SURFACE) {
            detachWindow()
            windowSurface = surface
            window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
            diagnostics.surfaceCreated()
        }
        makeCurrent()
        outputWidth = width.coerceAtLeast(1); outputHeight = height.coerceAtLeast(1)
        diagnostics.event("DISPLAY_SURFACE_ATTACH width=$outputWidth height=$outputHeight generation=${displayGeneration.get()}")
        state.updateAndGet { current -> current?.copy(width = outputWidth, height = outputHeight) }
        // Reuse cached textures, and refresh raster work for the new viewport.
        requestRender()
    }

    private fun detachWindow() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
        windowSurface = null
    }

    fun createInput(key: ActiveClipResolver.ClipKey, width: Int, height: Int, ready: (InputTarget) -> Unit) {
        handler.post {
            if (released.get()) return@post
            slots[key]?.let { existing ->
                diagnostics.event("DECODER_SURFACE_REUSED key=$key")
                main.post { ready(InputTarget(existing.surface)) }
                return@post
            }
            try {
                makeCurrent()
                val oes = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
                val texture = texture2d(width, height)
                val fbo = framebuffer(texture, width, height)
                val st = SurfaceTexture(oes).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener({ source -> consume(key, source) }, handler)
                }
                val surface = Surface(st)
                slots[key] = Slot(key, width, height, oes, texture, fbo, st, surface)
                decoderSurfaceCount = slots.size
                diagnostics.decoderSurfaceCreated(key)
                main.post { ready(InputTarget(surface)) }
            } catch (error: Throwable) { fail(error) }
        }
    }

    fun submit(key: ActiveClipResolver.ClipKey, frame: VideoDecoderSession.Frame) {
        handler.post {
            val slot = slots[key]
            if (slot == null || !slot.pending.compareAndSet(null, frame)) frame.acknowledge()
        }
    }

    private fun consume(key: ActiveClipResolver.ClipKey, source: SurfaceTexture) {
        val slot = slots[key]?.takeIf { it.surfaceTexture === source } ?: return
        val frame = slot.pending.getAndSet(null)
        try {
            makeCurrent()
            slot.surfaceTexture.updateTexImage()
            if (frame != null && isGenerationCurrent(frame.generation)) {
                slot.surfaceTexture.getTransformMatrix(stMatrix)
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.fbo)
                GLES20.glViewport(0, 0, slot.width, slot.height)
                useQuad(oesProgram)
                uniformMatrix(oesProgram, "uMvp", IDENTITY)
                uniformMatrix(oesProgram, "uTexMatrix", stMatrix)
                bindTexture(oesProgram, "uTexture", GLES11Ext.GL_TEXTURE_EXTERNAL_OES, slot.oes, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                slot.hasFrame = true
                slot.frameGeneration = frame.generation
                diagnostics.frameAvailable(frame.presentationTimeUs)
                if (!isPlaying()) requestRender() else when (val decision = frameScheduler.decide(frame.projectPresentationTimeUs, transportPositionUs())) {
                    VideoFrameScheduler.Decision.Present -> requestRender()
                    is VideoFrameScheduler.Decision.Wait -> handler.postDelayed({ requestRender() }, (decision.delayUs / 1000L).coerceAtLeast(1L))
                    is VideoFrameScheduler.Decision.Drop -> {
                        diagnostics.frameDroppedByPacing(frame.projectPresentationTimeUs, decision.latenessUs)
                        requestRender()
                    }
                }
            } else if (frame != null) diagnostics.staleSeekDropped()
        } catch (error: Throwable) { fail(error) }
        finally { frame?.acknowledge() }
    }

    fun transferInput(fromKey: ActiveClipResolver.ClipKey, toKey: ActiveClipResolver.ClipKey) {
        if (released.get() || fromKey == toKey) return
        handler.post {
            val slot = slots.remove(fromKey)
            if (slot != null) {
                slot.pending.getAndSet(null)?.acknowledge()
                // The FBO still holds the outgoing clip's last pixels until a new frame lands,
                // so the transferred slot must not advertise itself as holding a valid frame for
                // the incoming clip: doing so presented the previous clip under the new clip's
                // transform, and could satisfy a transition's generation check with foreign
                // content. The layer is simply skipped until the decoder delivers a real frame.
                val transferred = slot.copy(key = toKey, hasFrame = false, frameGeneration = -1)
                slot.surfaceTexture.setOnFrameAvailableListener({ source -> consume(toKey, source) }, handler)
                slots[toKey] = transferred
                effectTargets.remove(fromKey)?.let { effectTargets[toKey] = it }
                layerMaskPass?.transfer(maskKey(fromKey), maskKey(toKey))
                diagnostics.event("DECODER_SURFACE_TRANSFERRED from=$fromKey to=$toKey")
            }
        }
    }

    fun removeInput(key: ActiveClipResolver.ClipKey, afterSurfaceReleased: () -> Unit = {}) {
        handler.post {
            if (released.get()) { main.post(afterSurfaceReleased); return@post }
            makeCurrent()
            slots.remove(key)?.let { slot ->
                slot.pending.getAndSet(null)?.acknowledge()
                slot.surface.release(); slot.surfaceTexture.release()
                GLES20.glDeleteTextures(1, intArrayOf(slot.oes), 0)
                GLES20.glDeleteTextures(1, intArrayOf(slot.texture), 0)
                GLES20.glDeleteFramebuffers(1, intArrayOf(slot.fbo), 0)
            }
            decoderSurfaceCount = slots.size
            effectTargets.remove(key)?.let(::deleteEffectTarget)
            layerMaskPass?.remove(maskKey(key))
            main.post(afterSurfaceReleased)
        }
    }

    fun update(newState: PreviewRenderState) {
        if (released.get()) return
        state.set(newState)
        requestRender()
    }

    fun requestRender() {
        if (released.get() || !renderScheduled.compareAndSet(false, true)) return
        handler.post {
            if (released.get() || !::choreographer.isInitialized) { renderScheduled.set(false); return@post }
            choreographer.postFrameCallback {
                // Consume before drawing: an update during draw schedules the next vsync.
                renderScheduled.set(false)
                if (!released.get()) runCatching {
                    state.get()?.let { ensureImages(it); ensureOverlay(it); textRenderer?.update(it) }
                    draw()
                }.onFailure(::fail)
            }
        }
    }

    private fun draw() {
        val render = state.get() ?: lastRenderedState ?: return
        if (released.get() || window == EGL14.EGL_NO_SURFACE || windowSurface?.isValid != true || !isGenerationCurrent(render.generation)) return
        if (diagnostics.traceCameraAt(render.projectTimeUs)) {
            diagnostics.event("CAMERA_FRAME projectTimeUs=${render.projectTimeUs} generation=${render.generation} " +
                "camera=${render.camera.copy(keyframes = emptyList())} layers=" + render.layers.joinToString { layer ->
                    val slot = slots[layer.key]
                    "${layer.key.clipId}:sourceTimeUs=${layer.sourceTimeUs},texture=${if (layer.image) imageTextures[layer.uri] else slot?.texture},ready=${slot?.hasFrame},frameGeneration=${slot?.frameGeneration}"
                })
        }
        // Keep the presented buffer while a seek waits for its own decoded texture.
        // An intentional empty timeline still draws its background and overlays.
        if (render.layers.isNotEmpty() && render.layers.none { layer ->
                if (layer.image) imageTextures.containsKey(layer.uri)
                else slots[layer.key]?.hasFrame == true
            }) return
        makeCurrent()
        val started = System.nanoTime()
        val cameraActive = !render.camera.isDefault
        if (cameraActive) {
            val old = sceneTarget
            if (old == null || old.width != outputWidth || old.height != outputHeight) {
                old?.let(::deleteEffectTarget)
                sceneTarget = createEffectTarget(outputWidth, outputHeight)
            }
        }
        sceneFramebuffer = if (cameraActive) sceneTarget!!.fbos[0] else 0
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFramebuffer)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(Color.red(render.backgroundColor)/255f, Color.green(render.backgroundColor)/255f,
            Color.blue(render.backgroundColor)/255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val transition = render.transition
        val transitionOutput =
            transition?.let {
                renderTransition(render, it)
            }

        // A transition with one pending input shows the current available side until both
        // inputs are ready. Never compose a frame from a previous seek generation.
        val fallbackKey = if (transition != null && transitionOutput == null) {
            val pair = render.layers.filter { it.key == transition.left || it.key == transition.right }
            fun ready(layer: PreviewRenderState.Layer): Boolean =
                if (layer.image) (imageTextures[layer.uri] ?: 0) > 0
                else slots[layer.key]?.hasFrame == true
            pair.firstOrNull(::ready)?.key
        } else null

        render.layers.forEach { layer ->
            if (transition != null && transitionOutput == null &&
                (layer.key == transition.left || layer.key == transition.right) && layer.key != fallbackKey) return@forEach
            if (
                transitionOutput != null &&
                (
                    layer.key == transition!!.left ||
                        layer.key == transition.right
                    )
            ) {
                if (layer.key == transition.right) {
                    drawTransitionOutput(transitionOutput)
                }

                return@forEach
            }

            val texture =
                if (layer.image) {
                    imageTextures[layer.uri]
                } else {
                    slots[layer.key]
                        // Generation must be checked here too, not only on the transition path:
                        // after a seek the FBO still holds the previous generation's pixels, and
                        // drawing them put stale content on screen instead of holding the last
                        // good frame.
                        ?.takeIf { it.hasFrame && it.frameGeneration == render.generation }
                        ?.texture
                }

            if (texture == null || texture == 0) return@forEach

            drawLayer(
                render,
                layer,
                applyLayerMask(layer, applyEffects(layer, applyBackgroundRemoval(layer, texture))),
                layer.opacity
            )
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFramebuffer)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glEnable(GLES20.GL_BLEND)
        if (overlayTexture != 0 && (overlaySignature == signature(render) || render.stickers.isNotEmpty())) {
            // Canvas/ImageDecoder bitmaps are premultiplied-alpha.
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            useQuad(overlayProgram)
            uniformMatrix(overlayProgram, "uMvp", IDENTITY)
            uniformMatrix(overlayProgram, "uTexMatrix", FBO_FLIP_Y)
            bindTexture(overlayProgram, "uTexture", GLES20.GL_TEXTURE_2D, overlayTexture, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        // Explicit z-order: background -> videos/effects -> stickers -> text -> editor Views/HUD.
        textRenderer?.draw(render, outputWidth, outputHeight)
        if (cameraActive) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(Color.red(render.backgroundColor)/255f, Color.green(render.backgroundColor)/255f,
                Color.blue(render.backgroundColor)/255f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            useQuad(overlayProgram)
            uniformMatrix(overlayProgram, "uMvp", cameraMatrix.evaluate(render.camera, outputWidth.toFloat() / outputHeight))
            uniformMatrix(overlayProgram, "uTexMatrix", IDENTITY)
            bindTexture(overlayProgram, "uTexture", GLES20.GL_TEXTURE_2D, sceneTarget!!.textures[0], 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        // Do not publish a draw superseded by a newer seek during GPU work.
        if (!isGenerationCurrent(render.generation)) return
        // Safe swap: window may have been invalidated between draw start and swap.
        if (window != EGL14.EGL_NO_SURFACE && !EGL14.eglSwapBuffers(display, window)) {
            diagnostics.event("EGL_SWAP_FAILED error=0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        lastRenderedState = render
        // A retained texture can be drawn during seek, but must not report seek completion.
        val currentFrames = render.layers.all { layer ->
            if (layer.image) imageTextures.containsKey(layer.uri)
            else slots[layer.key]?.let { it.hasFrame && it.frameGeneration == render.generation } == true
        }
        if (currentFrames) {
            diagnostics.frameRendered((System.nanoTime() - started) / 1_000_000.0, render.timeUs)
            main.post { if (!released.get() && isGenerationCurrent(render.generation)) onFramePresented(render.timeUs) }
        }
    }

    /** Each clip is first drawn into a full-canvas texture with its own crop and transform.
     * The transition shader therefore receives two equally sized, equally oriented scenes. */
    private fun renderTransition(render: PreviewRenderState, transition: PreviewRenderState.Transition): Int? {
        val left = render.layers.firstOrNull { it.key == transition.left } ?: return null
        val right = render.layers.firstOrNull { it.key == transition.right } ?: return null
        fun texture(layer: PreviewRenderState.Layer): Int? =
            if (layer.image) imageTextures[layer.uri]
            else slots[layer.key]?.takeIf { it.hasFrame && it.frameGeneration == render.generation }?.texture
        val rawA = texture(left)?.takeIf { it > 0 } ?: return null
        val rawB = texture(right)?.takeIf { it > 0 } ?: return null
        val mode = diagnostics.transitionDebugMode
        val shaderTransition = if (mode == PreviewDiagnostics.TransitionDebugMode.CROSSFADE)
            transition.copy(assetId = "cross_dissolve", parameters = mapOf("mode" to 0f, "softness" to 0f)) else transition
        val compiled = transitionProgram(shaderTransition)
        val w = outputWidth.coerceAtLeast(2)
        val h = outputHeight.coerceAtLeast(2)
        fun target(old: EffectTarget?): EffectTarget = if (old?.width == w && old.height == h) old
            else { old?.let(::deleteEffectTarget); createEffectTarget(w, h) }
        transitionTarget = target(transitionTarget)
        transitionCompositeTarget = target(transitionCompositeTarget)
        val inputs = transitionTarget!!
        val composite = transitionCompositeTarget!!
        val sourceTextures = intArrayOf(
            applyLayerMask(left, applyEffects(left, applyBackgroundRemoval(left, rawA))),
            applyLayerMask(right, applyEffects(right, applyBackgroundRemoval(right, rawB))),
        )
        listOf(left, right).forEachIndexed { index, layer ->
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, inputs.fbos[index])
            GLES20.glViewport(0, 0, w, h)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawLayer(render, layer, sourceTextures[index], layer.opacity, inputs.fbos[index])
        }
        if (mode == PreviewDiagnostics.TransitionDebugMode.INPUT_A) return inputs.textures[0]
        if (mode == PreviewDiagnostics.TransitionDebugMode.INPUT_B) return inputs.textures[1]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, composite.fbos[0])
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        useQuad(compiled.id)
        uniformMatrix(compiled.id, "uMvp", IDENTITY)
        uniformMatrix(compiled.id, "uTexMatrix", IDENTITY)
        bindTexture(compiled.id, "uTexA", GLES20.GL_TEXTURE_2D, inputs.textures[0], 0)
        bindTexture(compiled.id, "uTexB", GLES20.GL_TEXTURE_2D, inputs.textures[1], 1)
        uniform2(compiled.id, "uResolution", w.toFloat(), h.toFloat())
        uniform1(compiled.id, "uProgress", transition.progress)
        compiled.defaults.forEach { (name, value) -> uniform1(compiled.id, "p_$name", shaderTransition.parameters[name] ?: value) }
        compiled.supplemental.forEach { name -> uniform1(compiled.id, "p_$name", when (name) {
            "perspective" -> 1f; "radius" -> 16f; "softness" -> .1f; else -> 1f
        }) }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (diagnostics.traceTransitionAt(render.projectTimeUs)) {
            diagnostics.event("TRANSITION_FRAME t=${render.projectTimeUs} startUs=${transition.startUs} endUs=${transition.endUs} progress=${transition.progress} " +
                "A=${left.key.clipId}:${left.sourceTimeUs}:$rawA B=${right.key.clipId}:${right.sourceTimeUs}:$rawB " +
                "inputs=${inputs.fbos.joinToString()} outputFbo=${composite.fbos[0]} viewport=0,0,$w,$h size=${w}x$h shader=${shaderTransition.assetId}")
        }
        return composite.textures[0]
    }

    private fun drawTransitionOutput(texture: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFramebuffer)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        useQuad(overlayProgram)
        uniformMatrix(overlayProgram, "uMvp", IDENTITY)
        uniformMatrix(overlayProgram, "uTexMatrix", IDENTITY)
        bindTexture(overlayProgram, "uTexture", GLES20.GL_TEXTURE_2D, texture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun transitionProgram(transition:PreviewRenderState.Transition):TransitionProgram{
        transitionPrograms[transition.assetId]?.let{return it}
        return runCatching{
            val definition=requireNotNull(TransitionCatalog.findById(context,transition.assetId)) { "Unknown transition ${transition.assetId}" }
            val body=BuiltInTransitions.shader(context,definition.shaderFile)
            val fragment=TransitionShaderBuilder.buildFragmentShader(TransitionShaderHeaders.getFragmentHeader(context),body,TransitionShaderHeaders.getFragmentFooter(context),definition)
            val declared=definition.parameters.map{it.id}.toSet();val supplemental=TransitionShaderBuilder.findBodyParamUniforms(body)-declared
            TransitionProgram(program(VERTEX,fragment),definition.parameters.associate{it.id to it.default},supplemental).also{transitionPrograms[transition.assetId]=it}
        }.onFailure{diagnostics.shaderFailure(transition.assetId, it)}.getOrThrow()
    }

    private fun drawLayer(
        render: PreviewRenderState,
        layer: PreviewRenderState.Layer,
        texture: Int,
        alpha: Float,
        outputFramebuffer: Int = sceneFramebuffer,
    ) {

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,outputFramebuffer)
        GLES20.glViewport(0,0,outputWidth,outputHeight)
        useQuad(drawProgram)
        val cropW = (layer.crop.right - layer.crop.left).coerceAtLeast(.05f)
        val cropH = (layer.crop.bottom - layer.crop.top).coerceAtLeast(.05f)
        val rotated = (layer.fixedRotation / 90) % 2 != 0
        val sourceAspect = if (rotated) layer.sourceHeight * cropH / (layer.sourceWidth * cropW)
            else layer.sourceWidth * cropW / (layer.sourceHeight * cropH)
        val outputAspect = outputWidth.toFloat() / outputHeight
        var sx = 1f; var sy = 1f
        if (render.canvasFill == CanvasFill.FIT) {
            if (sourceAspect > outputAspect) sy = outputAspect / sourceAspect else sx = sourceAspect / outputAspect
        } else {
            if (sourceAspect > outputAspect) sx = sourceAspect / outputAspect else sy = outputAspect / sourceAspect
        }
        val radians = Math.toRadians((layer.rotation + layer.fixedRotation).toDouble())
        val c = cos(radians).toFloat(); val s = sin(radians).toFloat()
        val xScale = sx * layer.zoom * if (layer.flip) -1f else 1f
        val yScale = sy * layer.zoom
        mvp.fill(0f)
        mvp[0]=xScale*c; mvp[1]=xScale*s; mvp[4]=-yScale*s; mvp[5]=yScale*c
        mvp[10]=1f; mvp[12]=layer.x*2f; mvp[13]=-layer.y*2f; mvp[15]=1f
        uniformMatrix(drawProgram, "uMvp", mvp)
        uniformMatrix(drawProgram, "uTexMatrix", IDENTITY)
        bindTexture(drawProgram, "uTexture", GLES20.GL_TEXTURE_2D, texture, 0)
        uniform4(drawProgram, "uCrop", layer.crop.left, layer.crop.top, layer.crop.right, layer.crop.bottom)
        val preset = layer.filter
        uniform4(drawProgram, "uAdjust", layer.brightness + preset.brightness * layer.filterStrength,
            layer.contrast + preset.contrast * layer.filterStrength,
            (layer.saturation + preset.saturation * layer.filterStrength) / 100f,
            (layer.lightness + preset.lightness * layer.filterStrength) / 100f)
        uniform4(drawProgram, "uColor", (layer.hue + preset.hue * layer.filterStrength) / 360f,
            1f + layer.temperature*.18f, 1f + layer.temperature*.03f, 1f - layer.temperature*.18f)
        uniform4(drawProgram, "uPreset", preset.red, preset.green, preset.blue,
            when (preset.name) { "MONO" -> 1f; "NEGATIVE" -> 2f; else -> 0f })
        uniform4(drawProgram, "uGrade", layer.grade.exposure, layer.grade.shadows,
            layer.grade.highlights, layer.grade.vignette)
        val chroma = layer.grade.chromaKeyState()
        uniform4(drawProgram, "uChroma", Color.red(chroma.keyColor) / 255f,
            Color.green(chroma.keyColor) / 255f, Color.blue(chroma.keyColor) / 255f,
            if (chroma.enabled) chroma.similarity else 0f)
        uniform3(drawProgram, "uKeyEdge", chroma.smoothness, chroma.spill, chroma.edgeFeather)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(drawProgram,"uOpacity"), alpha.coerceIn(0f,1f))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
    }

    private fun applyBackgroundRemoval(layer: PreviewRenderState.Layer, input: Int): Int {
        val effect = layer.backgroundRemoval
        if (!effect.enabled) return input
        val actualSize = if (layer.image) imageSizes[layer.uri] else slots[layer.key]?.let { it.width to it.height }
        return runCatching {
            backgroundRemovalPass?.apply(
                sourceTexture = input,
                key = "${layer.key.trackId}:${layer.key.clipId}",
                sourceWidth = actualSize?.first ?: layer.sourceWidth,
                sourceHeight = actualSize?.second ?: layer.sourceHeight,
                sourceTimestampUs = layer.sourceTimeUs,
                effect = effect,
                image = layer.image,
            ) ?: input
        }.onFailure { diagnostics.event("BACKGROUND_REMOVAL_PASS_FAILED ${it.message}") }.getOrDefault(input)
    }

    private fun applyLayerMask(layer: PreviewRenderState.Layer, input: Int): Int {
        val mask = layer.mask ?: return input
        val actualSize = if (layer.image) imageSizes[layer.uri] else slots[layer.key]?.let { it.width to it.height }
        return runCatching {
            layerMaskPass?.apply(
                sourceTexture = input,
                key = maskKey(layer.key),
                width = actualSize?.first ?: layer.sourceWidth,
                height = actualSize?.second ?: layer.sourceHeight,
                mask = mask,
            ) ?: input
        }.onFailure { diagnostics.event("LAYER_MASK_PASS_FAILED ${it.message}") }.getOrDefault(input)
    }

    private fun maskKey(key: ActiveClipResolver.ClipKey): String = "${key.trackId}:${key.clipId}"

    private fun applyEffects(layer:PreviewRenderState.Layer,input:Int):Int{
        val active=layer.effects.filter{it.intensity>0f}
        if(active.isEmpty())return input
        val size=if(layer.image)imageSizes[layer.uri] else slots[layer.key]?.let{it.width to it.height}
        val w=(size?.first?:layer.sourceWidth).coerceAtLeast(2);val h=(size?.second?:layer.sourceHeight).coerceAtLeast(2)
        var target=effectTargets[layer.key]
        if(target==null||target.width!=w||target.height!=h){target?.let(::deleteEffectTarget);target=createEffectTarget(w,h);effectTargets[layer.key]=target}
        var source=input
        var pass = 0
        active.forEach { effect ->
            val compiled=effectProgram(effect)?:return@forEach
            val out=pass++ and 1
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,target.fbos[out]);GLES20.glViewport(0,0,w,h)
            useQuad(compiled.id);uniformMatrix(compiled.id,"uMvp",IDENTITY);uniformMatrix(compiled.id,"uTexMatrix",IDENTITY)
            bindTexture(compiled.id,"uTexSampler",GLES20.GL_TEXTURE_2D,source,0)
            bindTexture(compiled.id,"uHistory0",GLES20.GL_TEXTURE_2D,source,1)
            bindTexture(compiled.id,"uHistory1",GLES20.GL_TEXTURE_2D,source,2)
            bindTexture(compiled.id,"uHistory2",GLES20.GL_TEXTURE_2D,source,3)
            uniform2(compiled.id,"uResolution",w.toFloat(),h.toFloat())
            uniform1(compiled.id,"uTime",layer.sourceTimeUs/1_000_000f);uniform1(compiled.id,"uProgress",0f)
            uniform1(compiled.id,"uIntensity",effect.intensity);uniform1(compiled.id,"uBlendMode",effect.blendMode.toFloat())
            uniform4(compiled.id,"uEffectMask",effect.maskShape.toFloat(),effect.maskFeather,if(effect.maskInvert)1f else 0f,effect.maskOpacity)
            uniform4(compiled.id,"uEffectMaskTransform",effect.maskX,effect.maskY,Math.toRadians(effect.maskRotation.toDouble()).toFloat(),effect.maskSize)
            uniform1(compiled.id,"uEffectMaskAspect",effect.maskAspect)
            compiled.defaults.forEach{(name,value)->uniform1(compiled.id,"p_$name",effect.parameters[name]?:value)}
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);source=target.textures[out]
        }
        return source
    }

    private fun effectProgram(effect:PreviewRenderState.Effect):EffectProgram?{
        val key="${effect.assetId}:${effect.version}"
        effectPrograms[key]?.let{return it}
        if (key in failedEffects) return null
        return runCatching{
            val (definition,body)=assets.resolve(effect.assetId,effect.version)
            val fragment=EffectShaderHeaders.getFragmentHeader(context)+"\n"+
                definition.parameters.joinToString("\n"){"uniform float p_${it.id};"}+"\n"+body+"\n"+
                EffectShaderHeaders.getFragmentFooter(context)
            EffectProgram(program(VERTEX,fragment),definition.parameters.associate{it.id to it.default})
                .also{effectPrograms[key]=it}
        }.onFailure{failedEffects.add(key);diagnostics.shaderFailure(key, it)}.getOrNull()
    }

    private fun createEffectTarget(w:Int,h:Int):EffectTarget{
        val textures=intArrayOf(texture2d(w,h),texture2d(w,h));val fbos=intArrayOf(framebuffer(textures[0],w,h),framebuffer(textures[1],w,h))
        return EffectTarget(w,h,textures,fbos)
    }
    private fun deleteEffectTarget(target:EffectTarget){GLES20.glDeleteTextures(2,target.textures,0);GLES20.glDeleteFramebuffers(2,target.fbos,0)}

    private fun ensureImages(render: PreviewRenderState) {
        render.layers.filter { it.image }.forEach { layer ->
            if (imageTextures.containsKey(layer.uri) || !imagePending.add(layer.uri)) return@forEach
            imageWorker.execute {
                val bitmap = runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(layer.uri))) {
                    decoder, info, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = minOf(1f, 2048f/maxOf(info.size.width,info.size.height))
                    if (scale < 1f) decoder.setTargetSize((info.size.width*scale).toInt(),(info.size.height*scale).toInt())
                } }.getOrNull()
                handler.post {
                    imagePending.remove(layer.uri)
                    if (released.get()) { bitmap?.recycle(); return@post }
                    if (bitmap != null) { imageTextures[layer.uri] = upload(bitmap);imageSizes[layer.uri]=bitmap.width to bitmap.height; bitmap.recycle(); requestRender() }
                }
            }
        }
    }

    private fun ensureOverlay(render: PreviewRenderState) {
        val signature = signature(render)
        if (signature == overlaySignature || !overlayPending.compareAndSet(false,true)) return
        imageWorker.execute {
            var bitmap=overlayWorkBitmap
            if(bitmap==null||bitmap.width!=render.width||bitmap.height!=render.height){bitmap?.recycle();bitmap=Bitmap.createBitmap(render.width,render.height,Bitmap.Config.ARGB_8888);overlayWorkBitmap=bitmap}
            val canvas = Canvas(bitmap); canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG); val rect = RectF()
            render.stickers.forEach { sticker ->
                // Check if it's a Lottie animation sticker
                if (com.termex.replay15.editor.lottie.LottieLayerRenderer.isLottieUri(sticker.uri)) {
                    val composition = com.termex.replay15.editor.lottie.LottieLayerRenderer.loadComposition(context, sticker.uri)
                        ?: return@forEach
                    val transform = sticker.baseTransform
                    val compBounds = composition.bounds
                    val compW = compBounds.width().toFloat().coerceAtLeast(1f)
                    val compH = compBounds.height().toFloat().coerceAtLeast(1f)
                    val h = sticker.size * render.height * transform.scaleY
                    val w = (sticker.size * render.height * compW / compH) * transform.scaleX
                    val duration = sticker.durationUs.coerceAtLeast(1L)
                    val rawProgress = (render.timeUs - sticker.startUs).toFloat() / duration.toFloat()
                    val progress = (rawProgress % 1.0f).let { if (it < 0f) it + 1f else it }
                    val motion = overlayMotion(sticker.animation, sticker.startUs, sticker.endUs, render.timeUs, render.width, render.height)
                    canvas.save()
                    canvas.translate(transform.x * render.width + motion[2], transform.y * render.height + motion[3])
                    // Negate rotation to compensate for GL FBO_FLIP_Y matrix applied when rendering the overlay texture
                    canvas.rotate(-transform.rotation)
                    canvas.scale((if (sticker.flip) -1f else 1f) * motion[1], motion[1])
                    val lottieRect = RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
                    CanvasLayerMask.draw(canvas, lottieRect, sticker.maskAt(render.timeUs)) {
                        com.termex.replay15.editor.lottie.LottieLayerRenderer.draw(
                            canvas, composition, progress, lottieRect, transform.opacity * motion[0]
                        )
                    }
                    canvas.restore()
                    return@forEach
                }
                val source = overlayStickerSources[sticker.uri] ?: runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(sticker.uri))) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.getOrNull()?.also { overlayStickerSources[sticker.uri] = it } ?: return@forEach
                val imageKey = "${sticker.uri}:${sticker.backgroundRemoval.hashCode()}"
                val image = overlayStickerBitmaps[imageKey] ?: if (sticker.backgroundRemoval.enabled) {
                    val processed = (overlayMaskApplier ?: GeminiBitmapMaskApplier(context).also { overlayMaskApplier = it })
                        .apply(source, sticker.backgroundRemoval)
                    processed?.also { overlayStickerBitmaps[imageKey] = it } ?: source
                } else source
                // The immutable render state already contains the transform for this frame.
                val transform = sticker.baseTransform
                val h = sticker.size * render.height * transform.scaleY
                val baseW = sticker.size * render.height * image.width / image.height.toFloat()
                val w = baseW * transform.scaleX
                val motion = overlayMotion(sticker.animation, sticker.startUs, sticker.endUs, render.timeUs, render.width, render.height)
                paint.alpha = (255 * transform.opacity * motion[0]).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.translate(transform.x * render.width + motion[2], transform.y * render.height + motion[3])
                // Negate rotation to compensate for GL FBO_FLIP_Y matrix applied when rendering the overlay texture
                canvas.rotate(-transform.rotation)
                canvas.scale((if (sticker.flip) -1f else 1f) * motion[1], motion[1])
                rect.set(-w / 2, -h / 2, w / 2, h / 2)
                CanvasLayerMask.draw(canvas, rect, sticker.maskAt(render.timeUs)) {
                    canvas.drawBitmap(image, null, rect, paint)
                }
                canvas.restore()
            }
            val activeStickerKeys = render.stickers.mapTo(HashSet()) { "${it.uri}:${it.backgroundRemoval.hashCode()}" }
            overlayStickerBitmaps.keys.filterNot { it in activeStickerKeys }.toList().forEach { key ->
                overlayStickerBitmaps.remove(key)?.let { if (!it.isRecycled) it.recycle() }
            }
            handler.post {
                if (!released.get()) {
                    makeCurrent()
                    if (overlayTexture == 0) overlayTexture = texture(GLES20.GL_TEXTURE_2D)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    overlaySignature = signature
                    requestRender()
                }
                overlayPending.set(false)
                if (!released.get()) state.get()?.takeIf { signature(it) != overlaySignature }?.let(::ensureOverlay)
            }
        }
    }

    private fun signature(s: PreviewRenderState): Long {
        val animated = s.stickers.any {
            com.termex.replay15.editor.lottie.LottieLayerRenderer.isLottieUri(it.uri) ||
            it.animation != TextAnimation.NONE || it.transformKeyframes.isNotEmpty() || it.mask?.keyframes?.isNotEmpty() == true
        }
        val timeBucket = if (animated) s.timeUs / 33_333L else 0L
        return ((31L * timeBucket + s.stickers.hashCode()) * 31 + s.width) * 31 + s.height
    }
    private fun overlayMotion(animation:TextAnimation,startUs:Long,endUs:Long,timeUs:Long,width:Int,height:Int):FloatArray{
        val enter=((timeUs-startUs)/350_000f).coerceIn(0f,1f);val leave=((endUs-timeUs)/250_000f).coerceIn(0f,1f);val progress=minOf(enter,leave)
        val alpha=if(animation==TextAnimation.NONE)1f else progress
        val shifted=progress-1f;val overshoot=shifted*shifted*(2.2f*shifted+2.2f)+1f
        return floatArrayOf(alpha,if(animation==TextAnimation.POP).72f+.28f*overshoot else 1f,
            if(animation==TextAnimation.SLIDE_LEFT)(1f-progress)*width*.09f else 0f,
            if(animation==TextAnimation.SLIDE_UP)(1f-progress)*height*.07f else 0f)
    }
    private fun upload(bitmap: Bitmap): Int {
        makeCurrent()
        val id = texture(GLES20.GL_TEXTURE_2D)
        val matrix = android.graphics.Matrix().apply { postScale(1f, -1f, bitmap.width / 2f, bitmap.height / 2f) }
        val flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flipped, 0)
        if (flipped !== bitmap) flipped.recycle()
        return id
    }
    private fun makeCurrent() { val target=if(window!=EGL14.EGL_NO_SURFACE)window else pbuffer; check(EGL14.eglMakeCurrent(display,target,target,eglContext)) }
    private fun texture(target:Int):Int { val ids=IntArray(1); GLES20.glGenTextures(1,ids,0); GLES20.glBindTexture(target,ids[0]); GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR); GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR); GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE); GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE); return ids[0] }
    private fun texture2d(w:Int,h:Int):Int { val id=texture(GLES20.GL_TEXTURE_2D); GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null); return id }
    private fun framebuffer(texture: Int, width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, ids, 0)
            error("Incomplete FBO id=${ids[0]} texture=$texture size=${width}x$height format=RGBA status=0x${Integer.toHexString(status)}")
        }
        return ids[0]
    }
    private fun useQuad(p:Int){ GLES20.glUseProgram(p); quad.position(0); val pos=GLES20.glGetAttribLocation(p,"aPosition"); GLES20.glEnableVertexAttribArray(pos); GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,quad); quad.position(2); val uv=GLES20.glGetAttribLocation(p,"aUv"); GLES20.glEnableVertexAttribArray(uv); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,quad) }
    private fun bindTexture(p:Int,name:String,target:Int,id:Int,unit:Int){ GLES20.glActiveTexture(GLES20.GL_TEXTURE0+unit); GLES20.glBindTexture(target,id); GLES20.glUniform1i(GLES20.glGetUniformLocation(p,name),unit) }
    private fun uniformMatrix(p:Int,n:String,v:FloatArray)=GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p,n),1,false,v,0)
    private fun uniform1(p:Int,n:String,v:Float){val loc=GLES20.glGetUniformLocation(p,n);if(loc>=0)GLES20.glUniform1f(loc,v)}
    private fun uniform2(p:Int,n:String,a:Float,b:Float){val loc=GLES20.glGetUniformLocation(p,n);if(loc>=0)GLES20.glUniform2f(loc,a,b)}
    private fun uniform3(p:Int,n:String,a:Float,b:Float,c:Float){val loc=GLES20.glGetUniformLocation(p,n);if(loc>=0)GLES20.glUniform3f(loc,a,b,c)}
    private fun uniform4(p:Int,n:String,a:Float,b:Float,c:Float,d:Float)=GLES20.glUniform4f(GLES20.glGetUniformLocation(p,n),a,b,c,d)
    private fun program(v: String, f: String): Int {
        val shaders = ArrayList<Int>(2)
        var linked = 0
        try {
            fun compile(type: Int, source: String): Int {
                val id = GLES20.glCreateShader(type)
                shaders.add(id)
                GLES20.glShaderSource(id, source)
                GLES20.glCompileShader(id)
                check(IntArray(1).also { GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, it, 0) }[0] != 0) {
                    GLES20.glGetShaderInfoLog(id)
                }
                return id
            }
            val vertex = compile(GLES20.GL_VERTEX_SHADER, v)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, f)
            linked = GLES20.glCreateProgram()
            GLES20.glAttachShader(linked, vertex); GLES20.glAttachShader(linked, fragment)
            GLES20.glLinkProgram(linked)
            check(IntArray(1).also { GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, it, 0) }[0] != 0) {
                GLES20.glGetProgramInfoLog(linked)
            }
            diagnostics.shaderCompiled()
            return linked
        } catch (failure: Throwable) {
            if (linked != 0) GLES20.glDeleteProgram(linked)
            throw failure
        } finally { shaders.forEach(GLES20::glDeleteShader) }
    }
    private fun fail(t:Throwable){ main.post { if (!released.get()) onError(t) } }

    override fun close() {
        if(!released.compareAndSet(false,true)) return
        view.holder.removeCallback(this)
        handler.post {
            // Stop producing work on GL, then drain bitmap work before destroying its resources.
            detachWindow()
            state.set(null)
            imageWorker.execute { handler.post { releaseGl() } }
            imageWorker.shutdown()
        }
    }

    private fun releaseGl() {
            makeCurrent()
            slots.values.forEach { it.pending.getAndSet(null)?.acknowledge(); it.surface.release(); it.surfaceTexture.release(); GLES20.glDeleteTextures(2,intArrayOf(it.oes,it.texture),0); GLES20.glDeleteFramebuffers(1,intArrayOf(it.fbo),0) }
            slots.clear(); decoderSurfaceCount = 0; imageTextures.values.forEach{GLES20.glDeleteTextures(1,intArrayOf(it),0)}; imageTextures.clear();imageSizes.clear()
            effectTargets.values.forEach(::deleteEffectTarget);effectTargets.clear();effectPrograms.values.forEach{GLES20.glDeleteProgram(it.id)};effectPrograms.clear()
            backgroundRemovalPass?.close();backgroundRemovalPass = null
            layerMaskPass?.close();layerMaskPass = null
            sceneTarget?.let(::deleteEffectTarget);sceneTarget=null
            transitionTarget?.let(::deleteEffectTarget);transitionTarget=null
            transitionCompositeTarget?.let(::deleteEffectTarget);transitionCompositeTarget=null;transitionPrograms.values.forEach{GLES20.glDeleteProgram(it.id)};transitionPrograms.clear()
            if(overlayTexture!=0)GLES20.glDeleteTextures(1,intArrayOf(overlayTexture),0)
            textRenderer?.release();textRenderer=null
            overlayWorkBitmap?.recycle();overlayWorkBitmap=null;overlayStickerBitmaps.values.forEach(Bitmap::recycle);overlayStickerBitmaps.clear();overlayTextLayouts.clear()
            overlayStickerSources.values.forEach { if (!it.isRecycled) it.recycle() }; overlayStickerSources.clear()
            overlayMaskApplier?.close(); overlayMaskApplier = null
            listOf(oesProgram,drawProgram,overlayProgram).filter{it!=0}.forEach(GLES20::glDeleteProgram)
            detachWindow(); if(pbuffer!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,pbuffer); if(eglContext!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,eglContext); if(display!=EGL14.EGL_NO_DISPLAY)EGL14.eglTerminate(display); thread.quitSafely(); diagnostics.eglReleased()
    }

    companion object {
        private val IDENTITY=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
        private val FBO_FLIP_Y=floatArrayOf(1f,0f,0f,0f,0f,-1f,0f,0f,0f,0f,1f,0f,0f,1f,0f,1f)
        private const val VERTEX="attribute vec2 aPosition; attribute vec2 aUv; uniform mat4 uMvp; uniform mat4 uTexMatrix; varying vec2 vUv; void main(){ gl_Position=uMvp*vec4(aPosition,0.,1.); vUv=(uTexMatrix*vec4(aUv,0.,1.)).xy; }"
        private const val OES_COPY="#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES uTexture; varying vec2 vUv; void main(){gl_FragColor=texture2D(uTexture,vUv);}"
        private const val OVERLAY="precision mediump float; uniform sampler2D uTexture; varying vec2 vUv; void main(){gl_FragColor=texture2D(uTexture,vUv);}"
        private const val DRAW="""precision highp float; uniform sampler2D uTexture; uniform vec4 uCrop; uniform vec4 uAdjust; uniform vec4 uColor; uniform vec4 uPreset; uniform vec4 uGrade; uniform vec4 uChroma; uniform vec3 uKeyEdge; uniform float uOpacity; varying vec2 vUv;
        vec3 hue(vec3 c,float h){float a=h*6.2831853; vec3 k=vec3(.57735); return c*cos(a)+cross(k,c)*sin(a)+k*dot(k,c)*(1.-cos(a));}
        void main(){if(vUv.x<uCrop.x||vUv.x>uCrop.z||vUv.y<uCrop.y||vUv.y>uCrop.w)discard; vec4 q=texture2D(uTexture,vUv); vec3 c=q.rgb*uPreset.rgb; if(uPreset.w>1.5)c=1.-c; float l=dot(c,vec3(.2126,.7152,.0722)); if(uPreset.w>.5&&uPreset.w<1.5)c=vec3(l); c+=uAdjust.x+uAdjust.w+uGrade.x*.12; c=(c-.5)*(1.+uAdjust.y)+.5; l=dot(c,vec3(.2126,.7152,.0722)); c=mix(vec3(l),c,1.+uAdjust.z); c=hue(c,uColor.x)*uColor.yzw; c=mix(c,c*c*(3.-2.*c),uGrade.z*.25); float v=smoothstep(.8,.2,length(vUv-.5)); c*=mix(1.,v,uGrade.w); float alpha=q.a*uOpacity; if(uChroma.w>0.){vec3 keyRgb=pow(max(q.rgb,vec3(0.)),vec3(1./2.2)); float threshold=max(0.,uChroma.w+uKeyEdge.z); float matte=smoothstep(threshold,threshold+uKeyEdge.x,distance(keyRgb,uChroma.xyz)); alpha*=matte; vec3 dominance=max(uChroma.xyz-max(uChroma.yzx,uChroma.zxy),vec3(0.)); dominance/=max(.0001,max(dominance.r,max(dominance.g,dominance.b))); c=mix(c,min(c,vec3(dot(c,vec3(.2126,.7152,.0722)))),dominance*uKeyEdge.y*(1.-matte));} gl_FragColor=vec4(clamp(c,0.,1.),clamp(alpha,0.,1.));}"""
    }
}
