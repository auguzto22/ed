package com.termex.replay15.editor.preview.engine

/** GL handle calls use the EGL thread; stateless pacing and PCM calls may use media threads. */
internal object NativePreviewBridge {
    /** Host JVM unit tests have no Android NDK library. Android always requires the native engine. */
    val available: Boolean = System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true) ||
        System.getProperty("java.runtime.name").orEmpty().contains("Android", ignoreCase = true)
    init { if (available) System.loadLibrary("recly_preview") }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeRegisterProgram(handle: Long, key: String, fragment: String): Boolean
    external fun nativeRender(handle: Long, frameJson: String): Boolean
    external fun nativeRemoveClip(handle: Long, key: String): Int
    external fun nativeFrameDecision(videoPtsUs: Long, projectTimeUs: Long, lateDropThresholdUs: Long, earlyToleranceUs: Long): Long
    external fun nativeMixSource(output: FloatArray, input: FloatArray, frames: Int, gain: Float)
    external fun nativeLimitPcm(output: FloatArray, sampleCount: Int)
    external fun nativeSelectActive(starts: LongArray, ends: LongArray, timeUs: Long): IntArray
    external fun nativeSelectUpcoming(starts: LongArray, tracks: IntArray, timeUs: Long, endUs: Long): IntArray
    external fun nativeClipSourceAt(segments: LongArray, speeds: FloatArray, durationUs: Long,
        inUs: Long, outUs: Long, sourceUs: Long, speed: Float, reverse: Boolean,
        image: Boolean, hasSpeedCurve: Boolean, offsetUs: Long): Long
    external fun nativeClipTimelineAt(segments: LongArray, speeds: FloatArray, durationUs: Long,
        inUs: Long, outUs: Long, reverse: Boolean, sourceTimeUs: Long): Long
    external fun nativeClipSpeedAt(segments: LongArray, speeds: FloatArray,
        inUs: Long, outUs: Long, relativeSourceUs: Long): Float
}
