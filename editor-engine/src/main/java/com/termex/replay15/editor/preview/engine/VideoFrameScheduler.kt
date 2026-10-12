package com.termex.replay15.editor.preview.engine

class VideoFrameScheduler(
    private val lateDropThresholdUs: Long = 50_000L,
    private val earlyToleranceUs: Long = 2_000L,
) {
    sealed interface Decision {
        data object Present : Decision
        data class Wait(val delayUs: Long) : Decision
        data class Drop(val latenessUs: Long) : Decision
    }

    fun decide(videoProjectPtsUs: Long, projectTimeUs: Long): Decision {
        val deltaUs = if (NativePreviewBridge.available) NativePreviewBridge.nativeFrameDecision(
            videoProjectPtsUs, projectTimeUs, lateDropThresholdUs, earlyToleranceUs
        ) else (videoProjectPtsUs - projectTimeUs).let { delta ->
            when {
                delta < -lateDropThresholdUs -> delta
                delta > earlyToleranceUs -> delta
                else -> 0L
            }
        }
        return when {
            deltaUs < 0 -> Decision.Drop(-deltaUs)
            deltaUs > 0 -> Decision.Wait(deltaUs)
            else -> Decision.Present
        }
    }
}
