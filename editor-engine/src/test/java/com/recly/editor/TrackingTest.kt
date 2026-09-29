package com.termex.replay15.editor

import com.termex.replay15.editor.domain.TrackingPoint
import com.termex.replay15.editor.domain.TrackingTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingTest {
    @Test
    fun `internal track interpolates normalized geometry`() {
        val track = TrackingTrack("track-1", listOf(
            TrackingPoint(0L, .2f, .3f, .1f, .2f, 1f),
            TrackingPoint(1_000L, .8f, .7f, .3f, .4f, .5f),
        ))
        val middle = track.at(500L)
        assertEquals(.5f, middle.centerX, .0001f)
        assertEquals(.5f, middle.centerY, .0001f)
        assertEquals(.2f, middle.width, .0001f)
        assertEquals(.75f, middle.confidence, .0001f)
    }
}
