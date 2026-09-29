package com.termex.replay15.editor.ui

data class TransitionPreviewRegion(
    val startUs: Long,
    val endUs: Long,
)

object TransitionPreviewHelper {
    const val DEFAULT_PRE_ROLL_US = 750_000L
    const val DEFAULT_POST_ROLL_US = 1_250_000L

    fun calculatePreviewRegion(
        cutUs: Long,
        projectDurationUs: Long,
        preRollUs: Long = DEFAULT_PRE_ROLL_US,
        postRollUs: Long = DEFAULT_POST_ROLL_US,
    ): TransitionPreviewRegion {
        val startUs = maxOf(0L, cutUs - preRollUs)
        val endUs = minOf(projectDurationUs, cutUs + postRollUs)
        return TransitionPreviewRegion(startUs, maxOf(startUs, endUs))
    }
}
