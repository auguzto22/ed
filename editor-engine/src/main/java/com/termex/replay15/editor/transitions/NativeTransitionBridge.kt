package com.termex.replay15.editor.transitions

/** Stateless planning also runs in the host JVM tests using the same Rust library. */
internal object NativeTransitionBridge {
    init { System.loadLibrary("recly_preview") }
    external fun nativeQuery(request: String): String
    external fun nativeAbandon(handle: Long)
    external fun nativeProgress(value: Float, easing: Int, x1: Float, y1: Float, x2: Float, y2: Float): Float
    external fun nativeAudioGain(value: Float, incoming: Boolean): Float
    external fun nativeHandleSource(offset: Long, duration: Long, first: Long, last: Long, total: Long,
        speed: Float, supported: Boolean, image: Boolean, bounded: Long): Long
    external fun nativeFade(input: Int, output: Int, fade: Long, duration: Long, local: Long): FloatArray
    external fun nativeCapture(handle: Long, texture: Int, timeUs: Long, width: Int, height: Int)
    external fun nativeDraw(handle: Long, texture: Int, timeUs: Long, width: Int, height: Int, id: String, progress: Float, parameters: String): Boolean
    external fun nativeCopy(handle: Long, texture: Int, width: Int, height: Int, hold: Boolean)
    external fun nativeApplyFade(handle: Long, texture: Int, width: Int, height: Int, input: Int, output: Int, fade: Long, duration: Long, local: Long)
}
