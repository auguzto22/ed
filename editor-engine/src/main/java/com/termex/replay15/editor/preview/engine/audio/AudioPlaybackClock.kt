package com.termex.replay15.editor.preview.engine.audio

import android.media.AudioTimestamp
import android.media.AudioTrack

/**
 * Derives project time strictly from the hardware audio presenter.
 * Uses AudioTimestamp where available, falling back to playbackHeadPosition.
 */
class AudioPlaybackClock(
    private val outputSampleRate: Int = 48000
) {
    private var projectAnchorUs = 0L
    private var outputFrameAnchor = 0L
    private var lastRawHead = 0L
    private var headWrapOffset = 0L
    private val timestamp = AudioTimestamp()
    private var anchorNs = 0L
    private var lastPositionUs = Long.MIN_VALUE

    @Synchronized
    fun anchor(projectTimeUs: Long, currentOutputFrames: Long) {
        projectAnchorUs = projectTimeUs
        outputFrameAnchor = currentOutputFrames
        lastRawHead = 0L
        headWrapOffset = 0L
        anchorNs = System.nanoTime()
        lastPositionUs = projectTimeUs
    }

    @Synchronized
    fun reset(projectTimeUs: Long) {
        projectAnchorUs = projectTimeUs
        outputFrameAnchor = 0L
        lastRawHead = 0L
        headWrapOffset = 0L
        anchorNs = System.nanoTime()
        lastPositionUs = projectTimeUs
    }

    @Synchronized
    fun positionUs(audioTrack: AudioTrack?): Long? {
        if (audioTrack == null || audioTrack.state != AudioTrack.STATE_INITIALIZED || audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
            return null
        }

        // 1. Preferred hardware presentation timestamp
        if (audioTrack.getTimestamp(timestamp) && timestamp.nanoTime >= anchorNs) {
            val framePos = timestamp.framePosition
            val playedFrames = framePos - outputFrameAnchor
            if (playedFrames >= 0) {
                // Compensate for the time elapsed between timestamp.nanoTime and now
                val nowNs = System.nanoTime()
                val deltaNs = nowNs - timestamp.nanoTime
                val deltaFrames = if (deltaNs in 0..100_000_000L) {
                    (deltaNs * outputSampleRate) / 1_000_000_000L
                } else 0L

                val totalPlayed = playedFrames + deltaFrames
                return monotonic(projectAnchorUs + (totalPlayed * 1_000_000L) / outputSampleRate)
            }
        }

        // 2. Fallback to playback head position
        val rawHead = audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (rawHead < lastRawHead && lastRawHead > 0x7FFFFFFFL) {
            headWrapOffset += 0x100000000L
        }
        lastRawHead = rawHead
        val cumulativeFrames = rawHead + headWrapOffset - outputFrameAnchor
        if (cumulativeFrames < 0) return projectAnchorUs

        return monotonic(projectAnchorUs + (cumulativeFrames * 1_000_000L) / outputSampleRate)
    }

    private fun monotonic(value: Long): Long {
        if (lastPositionUs == Long.MIN_VALUE || value >= lastPositionUs) lastPositionUs = value
        return lastPositionUs
    }

    @Synchronized fun invalidateTimestamp() { anchorNs = System.nanoTime() }
}
