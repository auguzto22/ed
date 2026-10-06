package com.termex.replay15.editor.detection

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ML Kit face detection. It never touches the preview or decoder threads: the caller
 * supplies a bitmap and receives normalized boxes on the engine's own executor.
 */
class MlKitFaceDetector(
    accurate: Boolean = false,
) : DetectionEngine {

    override val id: String = if (accurate) "mlkit-face-accurate" else "mlkit-face-fast"
    override val target: DetectionTarget = DetectionTarget.FACE

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Recly-FaceDetection").apply { isDaemon = true }
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(
                if (accurate) FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
                else FaceDetectorOptions.PERFORMANCE_MODE_FAST
            )
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
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
        // Face exposes no image size, so the input's own dimensions are captured here. They
        // already account for the rotation handed to InputImage, which is why normalizing
        // against them (not against the bitmap) keeps the 0..1 contract correct.
        val imageWidth = image.width
        val imageHeight = image.height
        detector.process(image)
            .addOnSuccessListener(executor) { faces ->
                callback(faces.mapNotNull { it.toObservation(timestampUs, imageWidth, imageHeight) })
            }
            // A failed detection yields no subject; it must never break the caller.
            .addOnFailureListener(executor) { callback(emptyList()) }
    }

    // ML Kit's face API exposes no detection score, so a reported face is reported at full
    // confidence. The observation stays valid because downstream consumers only use
    // confidence to weight a track, never to gate it.
    private fun Face.toObservation(timestampUs: Long, imageWidth: Int, imageHeight: Int): DetectionObservation? {
        val box = boundingBox
        return normalizedObservation(
            timeUs = timestampUs,
            left = box.left.toFloat(),
            top = box.top.toFloat(),
            right = box.right.toFloat(),
            bottom = box.bottom.toFloat(),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            confidence = 1f,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        detector.close()
        executor.shutdownNow()
    }
}
