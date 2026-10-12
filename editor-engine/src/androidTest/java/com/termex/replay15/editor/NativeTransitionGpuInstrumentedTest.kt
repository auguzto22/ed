package com.termex.replay15.editor

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import com.termex.replay15.editor.preview.engine.NativePreviewBridge
import com.termex.replay15.editor.transitions.NativeTransitionBridge
import com.termex.replay15.editor.transitions.TransitionPrograms
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Exercises the production Rust compositor on the phone's real GLES driver. */
class NativeTransitionGpuInstrumentedTest {
    @Test fun everyPackageHasExactEndpointsAndMatchingPreviewExportPixels() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        var preview = 0L; var export = 0L
        val textures = IntArray(2)
        try {
            preview = NativePreviewBridge.nativeCreate(); export = NativePreviewBridge.nativeCreate()
            assertTrue(preview != 0L && export != 0L)
            GLES20.glGenTextures(2, textures, 0)
            val colors = listOf(intArrayOf(200, 30, 60, 255), intArrayOf(20, 180, 100, 255))
            textures.forEachIndexed { i, texture ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val bytes = ByteBuffer.allocateDirect(16 * 16 * 4)
                repeat(16 * 16) { colors[i].forEach { bytes.put(it.toByte()) } }; bytes.position(0)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 16, 16, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            }
            fun pixels(): IntArray {
                val buffer = ByteBuffer.allocateDirect(16 * 16 * 4)
                GLES20.glReadPixels(0, 0, 16, 16, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                return IntArray(buffer.capacity()) { buffer.get(it).toInt() and 255 }
            }
            for (definition in TransitionPrograms.catalog()) {
                for (progress in listOf(0f, .25f, .5f, .75f, 1f)) {
                    assertTrue(NativePreviewBridge.nativeRender(preview, frame(definition.id, textures, progress)))
                    val monitor = pixels()
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    NativeTransitionBridge.nativeCapture(export, textures[0], 500_000L, 16, 16)
                    assertTrue(NativeTransitionBridge.nativeDraw(export, textures[1], 500_000L, 16, 16, definition.id, progress, "{}"))
                    val encoded = pixels()
                    monitor.indices.forEach { index -> assertTrue("${definition.id} p=$progress pixel=$index preview=${monitor[index]} export=${encoded[index]}", kotlin.math.abs(monitor[index] - encoded[index]) <= 2) }
                    if (progress == 0f || progress == 1f) {
                        val expected = colors[if (progress == 0f) 0 else 1]
                        encoded.indices.forEach { index -> assertTrue("Endpoint ${definition.id}: $index", kotlin.math.abs(encoded[index] - expected[index % 4]) <= 2) }
                    }
                }
            }
            // A seek far beyond the retained capture must not composite an unrelated frame.
            assertFalse(NativeTransitionBridge.nativeDraw(export, textures[1], 2_000_000L, 16, 16, "cross_dissolve", .5f, "{}"))
        } finally {
            if (preview != 0L) NativePreviewBridge.nativeDestroy(preview)
            if (export != 0L) NativePreviewBridge.nativeDestroy(export)
            GLES20.glDeleteTextures(2, textures, 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
        }
    }

    private fun frame(id: String, textures: IntArray, progress: Float): String {
        fun a(vararg v: Number) = JSONArray(v.toList())
        val layers = JSONArray()
        textures.forEachIndexed { i, texture -> layers.put(JSONObject().put("key", "$i").put("texture", texture)
            .put("width", 16).put("height", 16).put("sourceWidth", 16).put("sourceHeight", 16)
            .put("zoom", 1).put("x", 0).put("y", 0).put("rotation", 0).put("fixedRotation", 0).put("flip", false)
            .put("opacity", 1).put("crop", a(0, 0, 1, 1)).put("adjust", a(0, 0, 0, 0))
            .put("color", a(0, 1, 1, 1)).put("preset", a(1, 1, 1, 0)).put("grade", a(0, 0, 0, 0))
            .put("chroma", a(0, 0, 0, 0)).put("keyEdge", a(0, 0, 0)).put("effects", JSONArray()).put("mask", JSONObject.NULL)) }
        return JSONObject().put("width", 16).put("height", 16).put("background", a(0, 0, 0, 1)).put("fit", true)
            .put("layers", layers).put("stickerTexture", 0).put("textTexture", 0).put("camera", JSONObject.NULL)
            .put("transition", JSONObject().put("left", "0").put("right", "1").put("program", id)
                .put("progress", progress).put("params", JSONObject())).toString()
    }
}
