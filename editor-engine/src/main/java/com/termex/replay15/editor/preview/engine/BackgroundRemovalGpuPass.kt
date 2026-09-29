package com.termex.replay15.editor.preview.engine

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import com.termex.replay15.editor.backgroundremoval.MlKitHumanSegmenter
import com.termex.replay15.editor.backgroundremoval.SegmentationEngine
import com.termex.replay15.editor.backgroundremoval.SegmentationFrame
import com.termex.replay15.editor.backgroundremoval.SegmentationMaskCache
import com.termex.replay15.editor.backgroundremoval.SegmentationMaskTexture
import com.termex.replay15.editor.backgroundremoval.SegmentationScheduler
import com.termex.replay15.editor.domain.BackgroundRemovalEffect
import com.termex.replay15.editor.domain.BackgroundMode
import com.termex.replay15.editor.domain.SegmentationQuality
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GPU-side background-removal pass used by the interactive preview.
 *
 * The GL thread only downsamples/captures and uploads masks. ML Kit runs in one bounded
 * single-flight executor per source clip; a missing or stale mask falls back to the original
 * texture, so segmentation can never turn a decoder failure into a black frame.
 */
internal class BackgroundRemovalGpuPass(
    private val context: Context,
    private val onMaskReady: () -> Unit,
) : AutoCloseable {
    private data class Target(val width: Int, val height: Int, val texture: Int, val fbo: Int)
    private class CaptureBuffer(val bitmap: Bitmap) {
        var inUse = false
    }
    private data class Entry(
        val key: String,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val captureWidth: Int,
        val captureHeight: Int,
        val image: Boolean,
        val quality: SegmentationQuality,
        val capture: Target,
        val output: Target,
        val mask: SegmentationMaskTexture,
        val cache: SegmentationMaskCache,
        val scheduler: SegmentationScheduler,
        val buffers: List<CaptureBuffer>,
        val pixels: ByteBuffer,
        val argb: IntArray,
        var lastRequestedTimestampUs: Long = Long.MIN_VALUE,
        var uploadedTimestampUs: Long = Long.MIN_VALUE,
    )

    private val entries = HashMap<String, Entry>()
    private val program = compile(VERTEX, FRAGMENT)
    private val captureProgram = compile(VERTEX, CAPTURE_FRAGMENT)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }
    private val ready = AtomicBoolean(true)

    fun apply(
        sourceTexture: Int,
        key: String,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceTimestampUs: Long,
        effect: BackgroundRemovalEffect,
        image: Boolean,
    ): Int {
        if (!effect.enabled) return sourceTexture
        // The first shipped mode is transparent removal. Other modes remain persisted in the
        // model so adding background compositing does not change the segmentation contract.
        if (effect.mode != BackgroundMode.REMOVE) return sourceTexture
        val entry = entry(key, sourceWidth, sourceHeight, effect.quality, image)
        val cached = entry.cache.nearest(sourceTimestampUs)
        if (cached != null) {
            if (entry.uploadedTimestampUs != cached.timestampUs) {
                entry.mask.upload(cached)
                entry.uploadedTimestampUs = cached.timestampUs
            }
            captureForInference(entry, sourceTexture, sourceTimestampUs)
            return drawMasked(entry, sourceTexture, effect)
        }
        captureForInference(entry, sourceTexture, sourceTimestampUs)
        return sourceTexture
    }

    private fun entry(key: String, sourceWidth: Int, sourceHeight: Int, quality: SegmentationQuality, image: Boolean): Entry {
        val existing = entries[key]
        if (existing != null && existing.sourceWidth == sourceWidth && existing.sourceHeight == sourceHeight && existing.quality == quality) return existing
        existing?.let(::releaseEntry)
        val (captureWidth, captureHeight) = inputSize(sourceWidth, sourceHeight, quality)
        val captureTexture = texture2d(captureWidth, captureHeight)
        val outputTexture = texture2d(sourceWidth.coerceAtLeast(2), sourceHeight.coerceAtLeast(2))
        val created = Entry(
            key = key,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            captureWidth = captureWidth,
            captureHeight = captureHeight,
            image = image,
            quality = quality,
            capture = Target(captureWidth, captureHeight, captureTexture, framebuffer(captureTexture, captureWidth, captureHeight)),
            output = Target(sourceWidth.coerceAtLeast(2), sourceHeight.coerceAtLeast(2), outputTexture,
                framebuffer(outputTexture, sourceWidth.coerceAtLeast(2), sourceHeight.coerceAtLeast(2))),
            mask = SegmentationMaskTexture(),
            cache = SegmentationMaskCache(if (image) 2 else 24),
            scheduler = SegmentationScheduler(MlKitHumanSegmenter(context, streamMode = !image), maxFrequencyHz = if (image) 1 else 12),
            buffers = List(3) { CaptureBuffer(Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)) },
            pixels = ByteBuffer.allocateDirect(captureWidth * captureHeight * 4).order(ByteOrder.nativeOrder()),
            argb = IntArray(captureWidth * captureHeight),
        )
        entries[key] = created
        return created
    }

    private fun captureForInference(entry: Entry, sourceTexture: Int, timestampUs: Long) {
        if (timestampUs == entry.lastRequestedTimestampUs) return
        val buffer = entry.buffers.firstOrNull { !it.inUse } ?: return
        buffer.inUse = true
        entry.lastRequestedTimestampUs = timestampUs

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, entry.capture.fbo)
        GLES20.glViewport(0, 0, entry.capture.width, entry.capture.height)
        GLES20.glDisable(GLES20.GL_BLEND)
        useQuad(captureProgram)
        uniformMatrix(captureProgram, "uMvp", IDENTITY)
        uniformMatrix(captureProgram, "uTexMatrix", IDENTITY)
        bindTexture(captureProgram, "uTexture", sourceTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        entry.pixels.clear()
        GLES20.glReadPixels(0, 0, entry.capture.width, entry.capture.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, entry.pixels)
        val bytes = entry.pixels
        for (y in 0 until entry.capture.height) {
            val destinationRow = entry.capture.height - 1 - y
            val row = y * entry.capture.width * 4
            for (x in 0 until entry.capture.width) {
                val offset = row + x * 4
                val r = bytes.get(offset).toInt() and 0xFF
                val g = bytes.get(offset + 1).toInt() and 0xFF
                val b = bytes.get(offset + 2).toInt() and 0xFF
                val a = bytes.get(offset + 3).toInt() and 0xFF
                entry.argb[destinationRow * entry.capture.width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        buffer.bitmap.setPixels(entry.argb, 0, entry.capture.width, 0, 0, entry.capture.width, entry.capture.height)
        entry.scheduler.submit(
            SegmentationFrame(buffer.bitmap, release = { buffer.inUse = false }),
            timestampUs,
        ) { result ->
            if (result != null) entry.cache.put(result)
            if (ready.compareAndSet(true, false)) {
                ready.set(true)
                onMaskReady()
            }
        }
    }

    private fun drawMasked(entry: Entry, sourceTexture: Int, effect: BackgroundRemovalEffect): Int {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, entry.output.fbo)
        GLES20.glViewport(0, 0, entry.output.width, entry.output.height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        useQuad(program)
        uniformMatrix(program, "uMvp", IDENTITY)
        uniformMatrix(program, "uTexMatrix", IDENTITY)
        bindTexture(program, "uTexture", sourceTexture, 0)
        bindTexture(program, "uMaskTexture", entry.mask.id, 1)
        uniform2(program, "uMaskTexel", 1f / entry.mask.width.coerceAtLeast(1), 1f / entry.mask.height.coerceAtLeast(1))
        uniform1(program, "uThreshold", effect.threshold)
        uniform1(program, "uFeather", effect.feather.coerceAtLeast(.001f))
        uniform1(program, "uEdgeSmoothing", effect.edgeSmoothing)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        return entry.output.texture
    }

    private fun inputSize(width: Int, height: Int, quality: SegmentationQuality): Pair<Int, Int> {
        val longest = when (quality) {
            SegmentationQuality.FAST -> 256
            SegmentationQuality.BALANCED -> 384
            SegmentationQuality.HIGH -> 512
        }
        val scale = longest.toFloat() / max(width, height).coerceAtLeast(1)
        return ((width * scale).roundToInt().coerceAtLeast(2)) to ((height * scale).roundToInt().coerceAtLeast(2))
    }

    private fun releaseEntry(entry: Entry) {
        entry.scheduler.close()
        entry.mask.release()
        entry.buffers.forEach { it.bitmap.recycle() }
        GLES20.glDeleteTextures(1, intArrayOf(entry.capture.texture), 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(entry.capture.fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(entry.output.texture), 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(entry.output.fbo), 0)
    }

    override fun close() {
        entries.values.toList().forEach(::releaseEntry)
        entries.clear()
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteProgram(captureProgram)
    }

    private fun texture2d(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        return ids[0]
    }

    private fun framebuffer(texture: Int, width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Background-removal FBO incomplete ${width}x$height"
        }
        return ids[0]
    }

    private fun useQuad(p: Int) {
        GLES20.glUseProgram(p)
        quad.position(0)
        val position = GLES20.glGetAttribLocation(p, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        val uv = GLES20.glGetAttribLocation(p, "aUv")
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
    }

    private fun bindTexture(p: Int, name: String, texture: Int, unit: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, name), unit)
    }
    private fun uniformMatrix(p: Int, name: String, value: FloatArray) = GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, name), 1, false, value, 0)
    private fun uniform1(p: Int, name: String, value: Float) {
        val location = GLES20.glGetUniformLocation(p, name)
        if (location >= 0) GLES20.glUniform1f(location, value)
    }
    private fun uniform2(p: Int, name: String, x: Float, y: Float) {
        val location = GLES20.glGetUniformLocation(p, name)
        if (location >= 0) GLES20.glUniform2f(location, x, y)
    }
    private fun compile(vertexSource: String, fragmentSource: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            check(IntArray(1).also { GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, it, 0) }[0] != 0) {
                GLES20.glGetShaderInfoLog(id)
            }
            return id
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(IntArray(1).also { GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, it, 0) }[0] != 0) {
            GLES20.glGetProgramInfoLog(program)
        }
        return program
    }

    private companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        const val VERTEX = "attribute vec2 aPosition; attribute vec2 aUv; uniform mat4 uMvp; uniform mat4 uTexMatrix; varying vec2 vUv; void main(){ gl_Position=uMvp*vec4(aPosition,0.,1.); vUv=(uTexMatrix*vec4(aUv,0.,1.)).xy; }"
        const val CAPTURE_FRAGMENT = "precision mediump float; uniform sampler2D uTexture; varying vec2 vUv; void main(){ gl_FragColor=texture2D(uTexture,vUv); }"
        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexture;
            uniform sampler2D uMaskTexture;
            uniform vec2 uMaskTexel;
            uniform float uThreshold;
            uniform float uFeather;
            uniform float uEdgeSmoothing;
            varying vec2 vUv;
            float maskAt(vec2 p) { return texture2D(uMaskTexture, clamp(p, 0.0, 1.0)).r; }
            void main() {
                vec4 video = texture2D(uTexture, vUv);
                float center = maskAt(vUv);
                float neighbours = (
                    maskAt(vUv + vec2(uMaskTexel.x, 0.0)) + maskAt(vUv - vec2(uMaskTexel.x, 0.0)) +
                    maskAt(vUv + vec2(0.0, uMaskTexel.y)) + maskAt(vUv - vec2(0.0, uMaskTexel.y))
                ) * 0.25;
                float mask = mix(center, neighbours, clamp(uEdgeSmoothing, 0.0, 1.0) * 0.35);
                float alpha = smoothstep(uThreshold - uFeather, uThreshold + uFeather, mask);
                gl_FragColor = vec4(video.rgb, video.a * alpha);
            }
        """
    }
}
