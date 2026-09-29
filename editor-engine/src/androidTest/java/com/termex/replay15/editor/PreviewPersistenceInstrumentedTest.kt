package com.termex.replay15.editor

import android.os.SystemClock
import android.view.SurfaceView
import androidx.test.platform.app.InstrumentationRegistry
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.preview.engine.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real MediaCodec -> SurfaceTexture -> cached GPU texture, including asynchronous cold start. */
class PreviewPersistenceInstrumentedTest {
    @Test fun pausedColdStartAndSourceReplacementKeepLatestRequestAndOwnedSurfaces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val source = File(app.cacheDir, "persistence-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("preview-long-gop.mp4").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val replacement = File(app.cacheDir, "replacement-${System.nanoTime()}.mp4")
        source.copyTo(replacement)
        val diagnostics = PreviewDiagnostics(app)
        val mailbox = SeekMailbox()
        val errors = ConcurrentLinkedQueue<Throwable>()
        lateinit var renderer: GpuPreviewRenderer
        lateinit var manager: VideoDecoderManager
        var project = Project(videos = listOf(VideoClip(
            id = "clip", uri = source.toURI().toString(), name = "fixture", sourceUs = 5_000_000,
            width = 320, height = 180,
        )))
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun request(timeUs: Long) {
            val generation = mailbox.submit(timeUs, SeekMailbox.Mode.PRECISE).generation
            mailbox.take()
            val resolver = ActiveClipResolver(project)
            val snapshot = resolver.resolve(timeUs)
            renderer.update(RenderStateEvaluator.evaluate(snapshot, project, generation, generation, 320, 180))
            manager.update(snapshot, emptyList(), generation, SeekMailbox.Mode.PRECISE)
        }
        fun awaitFrame(previous: Long) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (diagnostics.decodedFrameCount.get() <= previous && errors.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(10) // Test polling only; no production timing workaround.
            }
            assertTrue("Pipeline failure: $errors", errors.isEmpty())
            assertTrue("Paused request did not deliver a GPU frame", diagnostics.decodedFrameCount.get() > previous)
        }
        try {
            main {
                renderer = GpuPreviewRenderer(app, SurfaceView(app), mailbox::isCurrent, { 0L }, diagnostics, { false }, {}, errors::add)
                manager = VideoDecoderManager(app, renderer, mailbox, diagnostics, { errors.add(it.cause ?: IllegalStateException(it.toString())) }, 2)
                // No later play/tick rescues this request: createInput completes after this callback.
                request(0)
            }
            awaitFrame(0)
            assertEquals(1L, diagnostics.decoderCreateCount.get())
            assertEquals(1L, diagnostics.decoderSurfaceCreateCount.get())
            val first = diagnostics.decodedFrameCount.get()
            main { repeat(50) { request(it * 10_000L) } }
            awaitFrame(first)
            assertEquals(1L, diagnostics.decoderCreateCount.get())
            assertEquals(1L, diagnostics.decoderSurfaceCreateCount.get())
            val beforeReplacement = diagnostics.decodedFrameCount.get()
            main {
                project = project.copy(videos = listOf(project.videos.single().copy(uri = replacement.toURI().toString())))
                request(0)
                // Repeated demand while old codec is releasing must not bind to its Surface.
                repeat(50) { request(100_000L) }
            }
            awaitFrame(beforeReplacement)
            assertEquals(2L, diagnostics.decoderCreateCount.get())
            assertEquals(2L, diagnostics.decoderSurfaceCreateCount.get())
            assertEquals(0L, diagnostics.surfaceFailureCount.get())
        } finally {
            val closed = CountDownLatch(1)
            main { manager.close { renderer.close(); closed.countDown() }; mailbox.close() }
            assertTrue("Decoder shutdown timed out", closed.await(10, TimeUnit.SECONDS))
            source.delete(); replacement.delete()
        }
    }
}
