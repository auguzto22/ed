package com.termex.replay15.editor.render

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import com.termex.replay15.editor.domain.VideoClip

/** Media3 timestamps here are relative to the trimmed source, not the project. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ClipSpeedProvider(clip: VideoClip) : SpeedProvider {
    private val mapping = clip.timeMap
    override fun getSpeed(timeUs: Long): Float = mapping.speedAtInput(timeUs)
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = mapping.nextInputChange(timeUs) ?: C.TIME_UNSET
}
