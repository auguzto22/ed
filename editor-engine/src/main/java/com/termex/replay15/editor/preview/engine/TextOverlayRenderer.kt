package com.termex.replay15.editor.preview.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.GLES20
import android.opengl.GLUtils
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.domain.SubtitleAnimationEvaluator
import com.termex.replay15.editor.render.LayerMaskShader
import com.termex.replay15.editor.render.TextLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPU text compositor. Content/style is rasterized only when its revision changes; position,
 * rotation, animation and opacity remain GPU transforms. Android bitmaps are premultiplied-alpha,
 * so drawing uses GL_ONE / GL_ONE_MINUS_SRC_ALPHA.
 */
class TextOverlayRenderer(
    private val context: Context,
    private val worker: ExecutorService,
    private val postGl: ((() -> Unit) -> Unit),
    private val currentState: () -> PreviewRenderState?,
    private val requestRender: () -> Unit,
    private val diagnostics: PreviewDiagnostics,
) {
    private data class Key(val id: String, val revision: Long, val width: Int, val height: Int)
    private data class Texture(val key: Key, val texture: Int, val width: Int, val height: Int)
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<Key, Texture>(16, .75f, true) {}
    private val pending = ConcurrentHashMap.newKeySet<Key>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)); position(0)
    }
    private val mvp = FloatArray(16)
    private val maskCenterSize = FloatArray(4)
    private val maskParams = FloatArray(4)
    private val maskMeta = FloatArray(3)
    private val maskPath = Array(MaskState.MAX_MASK_PATH_POINTS) { FloatArray(2) }
    private var program = 0
    private val closed = AtomicBoolean()
    private var lastDrawn = emptySet<String>()
    private var lastActive = emptySet<String>()

    fun update(state: PreviewRenderState) {
        if (closed.get()) return
        diagnostics.textActive(state.textLayers.count { it.visible })
        val active = state.textLayers.filter { it.visible }.mapTo(linkedSetOf()) { it.id }
        (active - lastActive).forEach { diagnostics.event("TEXT_ACTIVE id=$it at=${state.projectTimeUs}") }
        (lastActive - active).forEach { diagnostics.event("TEXT_INACTIVE id=$it at=${state.projectTimeUs}") }
        lastActive = active
        val removed = synchronized(cacheLock) { cache.filterKeys { it.id !in state.textIds }.keys.toList() }
        if (removed.isNotEmpty()) {
            postGl {
                synchronized(cacheLock) {
                    removed.forEach(::remove)
                    trim()
                }
            }
        }
        val toRasterize = mutableListOf<Pair<PreviewRenderState.TextRenderState, Key>>()
        state.textLayers.filter { it.visible }.forEach { text ->
            val key = Key(text.id, text.contentRevision, state.width, state.height)
            val cached = synchronized(cacheLock) { cache.containsKey(key) }
            if (cached) {
                diagnostics.textTextureCacheHit()
            } else if (pending.add(key)) {
                toRasterize += (text to key)
            }
        }
        toRasterize.forEach { (text, key) ->
            worker.execute { rasterize(state.projectRevision, state.projectTimeUs, text, key) }
        }
    }

    private fun rasterize(projectRevision: Long, timeUs: Long, text: PreviewRenderState.TextRenderState, key: Key) {
        val rasterSource = text.source.copy(opacity = 1f)
        val block = runCatching { TextLayout.create(rasterSource, key.width, key.height, context, timeUs) }
            .recoverCatching { TextLayout.create(rasterSource, key.width, key.height, null, timeUs) }.getOrNull()
        val bitmap = block?.let {
            Bitmap.createBitmap(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bitmap ->
                it.draw(Canvas(bitmap))
            }
        }
        postGl {
            pending.remove(key)
            val current = currentState()
            val stillWanted = current != null && current.width == key.width && current.height == key.height &&
                current.textLayers.any { it.id == text.id && it.visible }
            if (bitmap != null && stillWanted && !closed.get()) {
                synchronized(cacheLock) {
                    val oldKeys = cache.keys.filter { it.id == key.id && it != key }.toList()
                    val ids = IntArray(1)
                    GLES20.glGenTextures(1, ids, 0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    cache[key] = Texture(key, ids[0], bitmap.width, bitmap.height)
                    oldKeys.forEach(::remove)
                    diagnostics.textTextureCreated(key.id)
                    trim()
                }
                requestRender()
            }
            bitmap?.recycle()
        }
    }

    fun draw(state: PreviewRenderState, viewportWidth: Int, viewportHeight: Int) {
        if (closed.get()) return
        if (state.textLayers.isEmpty()) { lastDrawn = emptySet(); return }
        if (program == 0) program = createProgram()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        quad.position(0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        val drawn = linkedSetOf<String>()
        state.textLayers.filter { it.visible }.forEach { text ->
            val key = Key(text.id, text.contentRevision, state.width, state.height)
            val texture = synchronized(cacheLock) {
                cache[key] ?: cache.values.lastOrNull { it.key.id == text.id }
            } ?: return@forEach
            val motion = SubtitleAnimationEvaluator.evaluate(text.source, state.projectTimeUs, viewportWidth, viewportHeight)
            val center = PreviewCoordinates.canvasToNdc(text.x, text.y)
            val sx = texture.width.toFloat() / viewportWidth.coerceAtLeast(1) * text.scale * motion[1]
            val sy = texture.height.toFloat() / viewportHeight.coerceAtLeast(1) * text.scale * motion[1]
            val radians = Math.toRadians((-text.rotation).toDouble())
            val c = cos(radians).toFloat(); val s = sin(radians).toFloat()
            mvp.fill(0f)
            mvp[0] = sx * c; mvp[1] = sx * s; mvp[4] = -sy * s; mvp[5] = sy * c
            mvp[10] = 1f
            mvp[12] = center.x + motion[2] * 2f / viewportWidth.coerceAtLeast(1)
            mvp[13] = center.y - motion[3] * 2f / viewportHeight.coerceAtLeast(1)
            mvp[15] = 1f
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uOpacity"), motion[0])
            setMaskUniforms(text.mask)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            drawn += text.id
            if (text.id !in lastDrawn) diagnostics.textRendered(text.id)
        }
        lastDrawn = drawn
    }

    private fun setMaskUniforms(mask: MaskState?) {
        if (mask == null) {
            maskMeta.fill(0f)
            GLES20.glUniform3fv(GLES20.glGetUniformLocation(program, "uLayerMaskMeta"), 1, maskMeta, 0)
            return
        }
        maskCenterSize[0] = mask.centerX
        maskCenterSize[1] = mask.centerY
        maskCenterSize[2] = mask.width
        maskCenterSize[3] = mask.height
        maskParams[0] = Math.toRadians(mask.rotation.toDouble()).toFloat()
        maskParams[1] = mask.feather
        maskParams[2] = mask.expansion
        maskParams[3] = mask.opacity
        maskMeta[0] = mask.type.ordinal + 1f
        maskMeta[1] = if (mask.inverted) 1f else 0f
        maskMeta[2] = mask.customPath.size.toFloat()
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uLayerMaskCenterSize"), 1, maskCenterSize, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uLayerMaskParams"), 1, maskParams, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(program, "uLayerMaskMeta"), 1, maskMeta, 0)
        for (index in maskPath.indices) {
            val point = mask.customPath.getOrNull(index)
            maskPath[index][0] = point?.x ?: .5f
            maskPath[index][1] = point?.y ?: .5f
            GLES20.glUniform2fv(GLES20.glGetUniformLocation(program, "uLayerMaskPath$index"), 1, maskPath[index], 0)
        }
    }

    private fun trim() {
        while (cache.size > MAX_TEXTURES) remove(cache.entries.first().key)
    }

    private fun remove(key: Key) {
        cache.remove(key)?.let {
            GLES20.glDeleteTextures(1, intArrayOf(it.texture), 0)
            diagnostics.textTextureReleased(key.id)
        }
    }

    fun release() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(cacheLock) {
            cache.keys.toList().forEach(::remove)
            cache.clear()
        }
        pending.clear()
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
    }

    private fun createProgram(): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type); GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            check(IntArray(1).also { GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, it, 0) }[0] != 0) { GLES20.glGetShaderInfoLog(id) }
            return id
        }
        val vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX); val fs = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
            check(IntArray(1).also { GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, it, 0) }[0] != 0) { GLES20.glGetProgramInfoLog(p) }
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs); diagnostics.shaderCompiled()
        }
    }

    companion object {
        private const val MAX_TEXTURES = 64
        private const val VERTEX = "attribute vec2 aPosition; attribute vec2 aUv; uniform mat4 uMvp; varying vec2 vUv; void main(){gl_Position=uMvp*vec4(aPosition,0.,1.);vUv=aUv;}"
        private val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexture;
            uniform float uOpacity;
            varying vec2 vUv;
            ${LayerMaskShader.UNIFORMS}
            ${LayerMaskShader.FUNCTIONS}
            void main(){
                float alpha = reclyLayerMaskAlpha(vUv);
                gl_FragColor = texture2D(uTexture,vUv) * uOpacity * alpha;
            }
        """.trimIndent()
    }
}
