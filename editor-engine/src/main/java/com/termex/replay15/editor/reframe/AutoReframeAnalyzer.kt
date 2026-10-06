package com.termex.replay15.editor.reframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.termex.replay15.editor.detection.DetectionEngine
import com.termex.replay15.editor.detection.DetectionFrame
import com.termex.replay15.editor.detection.DetectionObservation
import com.termex.replay15.editor.detection.MlKitFaceDetector
import com.termex.replay15.editor.detection.MlKitPoseDetector
import com.termex.replay15.editor.domain.TransformKeyframe
import com.termex.replay15.editor.domain.VideoClip
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Local, bounded frame sampling for the explicit Auto Reframe action. */
class AutoReframeAnalyzer(context: Context) {
    private val appContext = context.applicationContext

    fun analyze(
        clip: VideoClip,
        target: ReframeAspect,
        isCancelled: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): AutoReframeAnalysis {
        require(!clip.image) { "Auto Reframe requer um clipe de video" }
        require(target != ReframeAspect.ORIGINAL) { "Escolha uma proporcao de destino" }
        val durationUs = clip.durationUs
        require(durationUs > 0L)

        val sampleTimes = sampleTimes(durationUs)
        val retriever = MediaMetadataRetriever()
        val faceDetector = MlKitFaceDetector()
        val poseDetector = MlKitPoseDetector(appContext)
        val observations = ArrayList<ReframeSubject>(sampleTimes.size)
        try {
            retriever.setDataSource(appContext, Uri.parse(clip.uri))
            val rawRatio = clip.width.toFloat() / clip.height
            val decodedRatio = if (clip.rotation % 180 == 0) rawRatio else 1f / rawRatio
            val sampleWidth = 360
            val sampleHeight = (sampleWidth / decodedRatio).roundToInt().coerceIn(120, 640)

            sampleTimes.forEachIndexed { index, localUs ->
                checkNotCancelled(isCancelled)
                val sourceUs = clip.timeMap.sourceAt(localUs)
                    .coerceIn(clip.inUs, (clip.outUs - 1L).coerceAtLeast(clip.inUs))
                val decoded = retriever.getScaledFrameAtTime(
                    sourceUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    sampleWidth,
                    sampleHeight,
                ) ?: retriever.getScaledFrameAtTime(
                    sourceUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    sampleWidth,
                    sampleHeight,
                )
                if (decoded != null) {
                    val frameBitmap = applyClipOrientationAndCrop(decoded, clip)
                    if (frameBitmap !== decoded) decoded.recycle()
                    try {
                        val frame = DetectionFrame(bitmap = frameBitmap, timeUs = localUs)
                        val faces = awaitDetection(faceDetector, frame, isCancelled)
                        val subject = faces.maxByOrNull { it.width * it.height * it.confidence }
                            ?: awaitDetection(poseDetector, frame, isCancelled)
                                .maxByOrNull { it.width * it.height * it.confidence }
                        subject?.let { observations += ReframeSubject.from(it) }
                    } finally {
                        if (!frameBitmap.isRecycled) frameBitmap.recycle()
                    }
                }
                onProgress(index + 1, sampleTimes.size)
            }
        } finally {
            runCatching { retriever.release() }
            runCatching { faceDetector.close() }
            runCatching { poseDetector.close() }
        }

        checkNotCancelled(isCancelled)
        val orientedSize = orientedDimensions(clip)
        val engine = AutoReframeEngine(
            sourceWidth = orientedSize.first,
            sourceHeight = orientedSize.second,
            target = target,
        )
        val path = engine.plan(observations, durationUs)
        return AutoReframeAnalysis(
            subjectsDetected = observations.size,
            sampledFrames = sampleTimes.size,
            points = path,
            keyframes = engine.toKeyframes(clip, path),
            target = target,
        )
    }

    private fun sampleTimes(durationUs: Long): List<Long> {
        val count = ceil(durationUs / SAMPLE_INTERVAL_US.toDouble()).toInt()
            .coerceIn(2, MAX_SAMPLES)
        return List(count) { index ->
            (index.toLong() * durationUs / (count - 1)).coerceIn(0L, durationUs)
        }
    }

    private fun awaitDetection(
        detector: DetectionEngine,
        frame: DetectionFrame,
        isCancelled: () -> Boolean,
    ): List<DetectionObservation> {
        val done = CountDownLatch(1)
        val result = AtomicReference<List<DetectionObservation>>(emptyList())
        detector.process(frame) {
            result.set(it)
            done.countDown()
        }
        // Keep the bitmap alive until ML Kit invokes its callback. Cancellation is checked
        // immediately afterward so neither a cancelled job nor a slow device can race a recycle.
        while (!done.await(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) Unit
        checkNotCancelled(isCancelled)
        return result.get().filter { it.timeUs == frame.timeUs }
    }

    private fun applyClipOrientationAndCrop(source: Bitmap, clip: VideoClip): Bitmap {
        var current = source
        if (clip.rotation != 0) {
            val matrix = Matrix().apply { postRotate(clip.rotation.toFloat()) }
            current = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
        }
        val crop = clip.crop
        if (crop.left != 0f || crop.top != 0f || crop.right != 1f || crop.bottom != 1f) {
            val left = (crop.left * current.width).roundToInt().coerceIn(0, current.width - 1)
            val top = (crop.top * current.height).roundToInt().coerceIn(0, current.height - 1)
            val right = (crop.right * current.width).roundToInt().coerceIn(left + 1, current.width)
            val bottom = (crop.bottom * current.height).roundToInt().coerceIn(top + 1, current.height)
            val cropped = Bitmap.createBitmap(current, left, top, right - left, bottom - top)
            if (current !== source && current !== cropped) current.recycle()
            current = cropped
        }
        return current
    }

    private fun orientedDimensions(clip: VideoClip): Pair<Int, Int> {
        val croppedWidth = max(1, (clip.width * (clip.crop.right - clip.crop.left)).roundToInt())
        val croppedHeight = max(1, (clip.height * (clip.crop.bottom - clip.crop.top)).roundToInt())
        return if (clip.rotation % 180 == 0) croppedWidth to croppedHeight else croppedHeight to croppedWidth
    }

    private fun checkNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw AutoReframeCancelledException()
    }

    companion object {
        private const val SAMPLE_INTERVAL_US = 750_000L
        private const val MAX_SAMPLES = 160
        private const val WAIT_SLICE_MS = 100L
    }
}

data class AutoReframeAnalysis(
    val subjectsDetected: Int,
    val sampledFrames: Int,
    val points: List<ReframePoint>,
    val keyframes: List<TransformKeyframe>,
    val target: ReframeAspect,
)

class AutoReframeCancelledException : RuntimeException("Reenquadramento cancelado")
