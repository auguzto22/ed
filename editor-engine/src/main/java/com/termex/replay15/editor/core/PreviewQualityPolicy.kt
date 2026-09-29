package com.termex.replay15.editor.core

/** Chooses a preview render target without changing source or export quality. */
object PreviewQualityPolicy {
    data class Inputs(
        val memoryClassMb: Int,
        val lowRamDevice: Boolean,
        val surfaceShortSidePx: Int,
        val simultaneousVideoLayers: Int,
        val framesPerSecond: Int,
    )

    fun choose(input: Inputs): Int {
        val baseline = when {
            input.lowRamDevice || input.memoryClassMb < 192 -> 480
            input.simultaneousVideoLayers >= 4 || input.framesPerSecond > 60 -> 480
            input.memoryClassMb >= 384 && input.surfaceShortSidePx >= 900 &&
                input.simultaneousVideoLayers <= 2 && input.framesPerSecond <= 60 -> 1080
            else -> 720
        }
        return PreviewPerformanceController.resolvePreviewShortSide(baseline)
    }
}
