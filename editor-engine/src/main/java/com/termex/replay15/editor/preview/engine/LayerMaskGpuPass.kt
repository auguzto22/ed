package com.termex.replay15.editor.preview.engine

import android.opengl.GLES20
import com.termex.replay15.editor.domain.MaskState
import com.termex.replay15.editor.render.LayerMaskShader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Persistent alpha-mask pass. It owns no decoder, Surface or orientation state. */
internal class LayerMaskGpuPass : AutoCloseable {
    private data class Target(val width: Int, val height: Int, val texture: Int, val framebuffer: Int)

    private val targets = HashMap<String, Target>()
    private val program = compile(VERTEX, FRAGMENT)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }
    private val centerSize = FloatArray(4)
    private val params = FloatArray(4)
    private val meta = FloatArray(3)
    private val path = Array(MaskState.MAX_MASK_PATH_POINTS) { FloatArray(2) }

    fun apply(sourceTexture: Int, key: String, width: Int, height: Int, mask: MaskState?): Int {
        if (mask == null || mask.opacity <= 0f) return sourceTexture
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        var target = targets[key]
        if (target == null || target.width != w || target.height != h) {
            target?.let(::release)
            target = createTarget(w, h)
            targets[key] = target
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        useQuad()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        setMaskUniforms(mask)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        return target.texture
    }

    fun remove(key: String) {
        targets.remove(key)?.let(::release)
    }

    fun transfer(from: String, to: String) {
        if (from == to) return
        targets.remove(to)?.let(::release)
        targets.remove(from)?.let { targets[to] = it }
    }

    private fun setMaskUniforms(mask: MaskState) {
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
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uLayerMaskCenterSize"), 1, centerSize, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uLayerMaskParams"), 1, params, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(program, "uLayerMaskMeta"), 1, meta, 0)
        for (index in path.indices) {
            val point = mask.customPath.getOrNull(index)
            path[index][0] = point?.x ?: .5f
            path[index][1] = point?.y ?: .5f
            GLES20.glUniform2fv(GLES20.glGetUniformLocation(program, "uLayerMaskPath$index"), 1, path[index], 0)
        }
    }

    private fun createTarget(width: Int, height: Int): Target {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val framebufferIds = IntArray(1)
        GLES20.glGenFramebuffers(1, framebufferIds, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferIds[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textureIds[0], 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Layer-mask FBO incomplete ${width}x$height"
        }
        return Target(width, height, textureIds[0], framebufferIds[0])
    }

    private fun useQuad() {
        GLES20.glUseProgram(program)
        quad.position(0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
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
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(IntArray(1).also { GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, it, 0) }[0] != 0) {
            GLES20.glGetProgramInfoLog(result)
        }
        return result
    }

    private fun release(target: Target) {
        GLES20.glDeleteTextures(1, intArrayOf(target.texture), 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
    }

    override fun close() {
        targets.values.toList().forEach(::release)
        targets.clear()
        GLES20.glDeleteProgram(program)
    }

    private companion object {
        const val VERTEX = "attribute vec2 aPosition; attribute vec2 aUv; varying vec2 vUv; void main(){gl_Position=vec4(aPosition,0.,1.);vUv=aUv;}"
        val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            ${LayerMaskShader.UNIFORMS}
            ${LayerMaskShader.FUNCTIONS}
            void main() {
                vec4 source = texture2D(uTexture, vUv);
                gl_FragColor = vec4(source.rgb, source.a * reclyLayerMaskAlpha(vUv));
            }
        """.trimIndent()
    }
}
