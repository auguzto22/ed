package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaskSpaceMigrationTest {
    private val second = 1_000_000L

    private fun clip(mask: MaskState?, tracks: List<TrackingTrack> = emptyList()) =
        VideoClip(uri = "content://video/1", name = "Clip", sourceUs = 4 * second, width = 1080, height = 1920,
            mask = mask, trackingTracks = tracks)

    /** Same bytes, header rewritten to [schema]: the sections are identical between 29 and 30. */
    private fun reloadAs(project: Project, schema: Int): Project {
        val bytes = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }.toByteArray()
        java.nio.ByteBuffer.wrap(bytes).putInt(4, schema)
        return ProjectCodec.read(bytes.inputStream())
    }

    @Test fun currentProjectsLoadUnchanged() {
        val mask = MaskState.default().copy(centerY = .2f, rotation = 30f)
        val project = Project(videos = listOf(clip(mask)))
        assertEquals(project, reloadAs(project, PROJECT_SCHEMA))
    }

    @Test fun legacyVideoMasksAreMirroredIntoTheYDownSpace() {
        val legacy = MaskState.default(MaskType.CUSTOM_PATH).copy(
            centerY = .2f, rotation = 30f,
            customPath = listOf(MaskPathPoint(.1f, .1f), MaskPathPoint(.9f, .1f), MaskPathPoint(.5f, .9f)),
            keyframes = listOf(MaskKeyframe(0L, .5f, .25f, .5f, .5f, 10f, .04f, 0f)),
        )
        val migrated = reloadAs(Project(videos = listOf(clip(legacy))), 29).videos.single().mask!!
        assertEquals(.8f, migrated.centerY, 1e-5f)
        assertEquals(-30f, migrated.rotation, 1e-5f)
        listOf(.9f, .9f, .1f).zip(migrated.customPath.map { it.y }).forEach { (expected, actual) -> assertEquals(expected, actual, 1e-5f) }
        assertEquals(.75f, migrated.keyframes.single().centerY, 1e-5f)
        assertEquals(-10f, migrated.keyframes.single().rotation, 1e-5f)
    }

    @Test fun legacyTrackedMasksKeepFollowingTheSameBox() {
        // Old files: track stored in texture space (y up) and it replaced the mask geometry.
        val track = TrackingTrack("track-1", listOf(
            TrackingPoint(0L, .4f, .7f, .2f, .1f, 1f),
            TrackingPoint(second, .6f, .6f, .2f, .1f, 1f),
        ))
        val legacy = MaskState.default().copy(trackingTrackId = track.id)
        val migratedClip = reloadAs(Project(videos = listOf(clip(legacy, listOf(track)))), 29).videos.single()
        val atEnd = migratedClip.maskAt(second)!!
        // Same place the old build showed it, now expressed y-down: (0.6, 1 - 0.6), box 0.2 x 0.1.
        assertEquals(.6f, atEnd.centerX, 1e-5f)
        assertEquals(.4f, atEnd.centerY, 1e-5f)
        assertEquals(.2f, atEnd.width, 1e-5f)
        assertEquals(.1f, atEnd.height, 1e-5f)
    }

    @Test fun clipsWithoutMasksAreUntouched() {
        val migrated = reloadAs(Project(videos = listOf(clip(null))), 29).videos.single()
        assertNull(migrated.mask)
    }
}
