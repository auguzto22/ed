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
        val deltaUs = videoProjectPtsUs - projectTimeUs
        return when {
            deltaUs < -lateDropThresholdUs -> Decision.Drop(-deltaUs)
            deltaUs > earlyToleranceUs -> Decision.Wait(deltaUs)
            else -> Decision.Present
        }
    }
}
