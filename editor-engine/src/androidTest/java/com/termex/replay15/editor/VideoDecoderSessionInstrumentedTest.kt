package com.termex.replay15.editor

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.termex.replay15.editor.preview.engine.SeekMailbox
import com.termex.replay15.editor.preview.engine.VideoDecoderSession
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Requires an Android device. Validates actual codec output into an EGL-owned OES texture. */
class VideoDecoderSessionInstrumentedTest {
    @Test fun longGopPreciseSeekBackwardAndRepeatedFramesKeepOneCodec() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val source = File(app.cacheDir, "preview-instrumentation-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("preview-long-gop.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val thread = HandlerThread("PreviewTestGL").apply { start() }
        val gl = Handler(thread.looper)
        fun <T> onGl(block: () -> T): T {
            val task = FutureTask(Callable(block))
            check(gl.post(task))
            return task.get(10, TimeUnit.SECONDS)
        }
        val mailbox = SeekMailbox()
        val submitted = AtomicReference<VideoDecoderSession.Frame?>()
        val frames = LinkedBlockingQueue<Pair<Long, Long>>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val events = ConcurrentLinkedQueue<String>()
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        var eglContext = EGL14.EGL_NO_CONTEXT
        var pbuffer = EGL14.EGL_NO_SURFACE
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
        val texture = IntArray(1)
        var decoder: VideoDecoderSession? = null
        try {
            onGl {
                check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
                check(count[0] > 0)
                eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0],
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext))
                GLES20.glGenTextures(1, texture, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0])
                surfaceTexture = SurfaceTexture(texture[0]).apply {
                    setOnFrameAvailableListener({ st ->
                        val frame = submitted.getAndSet(null)
                        try {
                            st.updateTexImage()
                            check(frame != null) { "Unassociated SurfaceTexture callback" }
                            check(kotlin.math.abs(st.timestamp / 1_000 - frame.presentationTimeUs) <= 1)
                            mailbox.publishIfCurrent(frame.generation) {
                                frames.offer(frame.generation to frame.presentationTimeUs)
                            }
                        } catch (failure: Throwable) { errors.add(failure) }
                        finally { frame?.acknowledge() }
                    }, gl)
                }
                surface = Surface(surfaceTexture)
            }
            decoder = VideoDecoderSession(app, source.toURI().toString(), requireNotNull(surface),
                mailbox::isCurrent,
                { frame -> check(submitted.compareAndSet(null, frame)) { "More than one outstanding output" } },
                errors::add, events::add)
            val session = decoder
            fun seek(timeUs: Long, mode: SeekMailbox.Mode): Pair<Long, Long> {
                val request = mailbox.submit(timeUs, mode)
                mailbox.take()
                session.request(VideoDecoderSession.Target(timeUs, request.generation, mode))
                val result = frames.poll(15, TimeUnit.SECONDS)
                assertTrue(errors.joinToString(), errors.isEmpty())
                assertNotNull("No decoded frame at $timeUs; events=$events", result)
                assertEquals(request.generation, result!!.first)
                return result
            }
            assertEquals(0L, seek(0, SeekMailbox.Mode.PRECISE).second)
            val initialCreates = events.count { it.startsWith("DECODER_CREATE") }
            assertTrue(initialCreates >= 1) // Initial configuration fallback is permitted.
            val longGop = seek(8_900_000, SeekMailbox.Mode.PRECISE).second
            assertTrue("Precise seek stopped before target: $longGop", longGop >= 8_900_000)
            assertTrue(longGop <= 8_934_000)
            assertTrue(seek(1_000_000, SeekMailbox.Mode.PRECISE).second in 1_000_000..1_034_000)
            val token = mailbox.currentGeneration()
            repeat(20) { index ->
                val target = 1_100_000L + index * 100_000
                session.request(VideoDecoderSession.Target(target, token, SeekMailbox.Mode.PRECISE))
                val frame = frames.poll(5, TimeUnit.SECONDS)
                assertNotNull(frame)
                assertEquals(token, frame!!.first)
                assertTrue(frame.second >= target)
            }
            assertEquals(1, events.count { it.startsWith("DECODER_START") })
            assertEquals(initialCreates, events.count { it.startsWith("DECODER_CREATE") })
            val last = seek(9_999_999, SeekMailbox.Mode.PRECISE).second
            assertTrue("Last frame must survive EOS", last in 9_960_000..9_999_999)
        } finally {
            mailbox.close()
            val released = CountDownLatch(1)
            decoder?.close { released.countDown() } ?: released.countDown()
            check(released.await(10, TimeUnit.SECONDS)) { "Decoder did not release" }
            onGl {
                surface?.release()
                surfaceTexture?.release()
                if (texture[0] != 0) GLES20.glDeleteTextures(1, texture, 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
            thread.join(5_000)
            source.delete()
        }
    }
}
