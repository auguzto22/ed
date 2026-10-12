package com.termex.replay15.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.platform.app.InstrumentationRegistry
import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.render.ProjectComposition
import com.termex.replay15.editor.transitions.NativeTransitionSessions
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Media3 scheduling, native A/B pass, encoder and decoded output pixels. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class NativeTransitionExportInstrumentedTest {
    @Test fun centeredDissolveExportsBothInputsAtTheCut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "native-transition-${System.nanoTime()}").apply { mkdirs() }
        var transformer: Transformer? = null
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        try {
            fun image(name: String, color: Int): VideoClip {
                val file = File(directory, "$name.png")
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                return VideoClip(id = name, uri = file.toURI().toString(), name = name, sourceUs = 3 * SECOND,
                    width = 64, height = 64, image = true)
            }
            val project = Project(videos = listOf(image("red", Color.RED), image("blue", Color.BLUE)),
                transitions = listOf(TransitionInstance(id = "cut", transitionId = "cross_dissolve", leftClipId = "red", rightClipId = "blue",
                    durationUs = 2 * SECOND, easing = Easing.LINEAR, parameters = mapOf("softness" to 0f))),
                export = ExportSettings(shortSide = 480, fps = 24, bitrate = 2_000_000))
            val output = File(directory, "result.mp4")
            instrumentation.runOnMainSync {
                try {
                    transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { completed.countDown() }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                failure.set(exportException); completed.countDown()
                            }
                        }).build()
                    transformer!!.start(ProjectComposition.build(context, project), output.absolutePath)
                } catch (error: Throwable) { failure.set(error); completed.countDown() }
            }
            assertTrue("Export timed out", completed.await(90, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Native transition export failed", it) }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.absolutePath)
                fun pixel(time: Long): Int {
                    val frame = requireNotNull(reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST))
                    return frame.getPixel(frame.width / 2, frame.height / 2).also { frame.recycle() }
                }
                val before = pixel(SECOND); val middle = pixel(3 * SECOND); val after = pixel(5 * SECOND)
                assertTrue("Outgoing frame missing", Color.red(before) > 200 && Color.blue(before) < 30)
                assertTrue("Cut did not blend both inputs: ${Integer.toHexString(middle)}", Color.red(middle) in 75..185 && Color.blue(middle) in 75..185)
                assertTrue("Incoming frame missing", Color.blue(after) > 200 && Color.red(after) < 30)
            } finally { reader.release() }
        } finally {
            instrumentation.runOnMainSync { transformer?.cancel(); NativeTransitionSessions.clear() }
            directory.deleteRecursively()
        }
    }
}
