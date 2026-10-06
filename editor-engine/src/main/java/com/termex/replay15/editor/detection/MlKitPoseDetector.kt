package com.termex.replay15.editor.detection

import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ML Kit pose detection in stream mode, which is the mode designed for continuous video.
 *
 * A pose has no bounding box of its own, so the box is derived from the landmarks ML Kit
 * already reports in normalized image space. The result is therefore resolution independent
 * by construction.
 */
class MlKitPoseDetector(context: Context) : DetectionEngine {

    override val id: String = "mlkit-pose-stream"
    override val target: DetectionTarget = DetectionTarget.POSE

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Recly-PoseDetection").apply { isDaemon = true }
    }

    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .setExecutor(executor)
            .build()
    )

    @Volatile private var closed = false

    override fun process(frame: DetectionFrame, callback: (List<DetectionObservation>) -> Unit) {
        if (closed) {
            callback(emptyList())
            return
        }
        val image = runCatching { InputImage.fromBitmap(frame.bitmap, frame.rotationDegrees) }
            .getOrElse {
                callback(emptyList())
                return
            }
        val timestampUs = frame.timeUs
        detector.process(image)
            .addOnSuccessListener(executor) { pose ->
                callback(pose.toObservation(timestampUs)?.let(::listOf) ?: emptyList())
            }
            .addOnFailureListener(executor) { callback(emptyList()) }
    }

    private fun Pose.toObservation(timestampUs: Long): DetectionObservation? {
        // A landmark with zero in-frame likelihood is reported as out of frame in stream mode
        // and would stretch the box across the image, so it must not contribute geometry.
        val seen = allPoseLandmarks.filter { it.inFrameLikelihood > 0f }
        if (seen.isEmpty()) return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var likelihoodSum = 0f
        for (landmark in seen) {
            val x = landmark.position.x
            val y = landmark.position.y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            likelihoodSum += landmark.inFrameLikelihood
        }

        return normalizedObservation(
            timeUs = timestampUs,
            // Landmark positions are already normalized 0..1, so map them back through a
            // synthetic unit image to reuse the single clamping/normalizing path.
            left = minX,
            top = minY,
            right = maxX,
            bottom = maxY,
            imageWidth = 1,
            imageHeight = 1,
            confidence = likelihoodSum / seen.size,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        detector.close()
        executor.shutdownNow()
    }
}
