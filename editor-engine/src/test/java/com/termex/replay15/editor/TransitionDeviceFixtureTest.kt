package com.termex.replay15.editor

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.ProjectCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Round-trip fixtures can optionally be installed for real decoder/GLES testing. */
class TransitionDeviceFixtureTest {
    @Test fun deviceFixturesPreserveTransitionsAndClipTransforms() {
        val directory = System.getenv("RECLY_TRANSITION_FIXTURES")?.let { File(it).apply { mkdirs() } }
        val root = "file:///data/user/0/com.recly.editor/files/transition-diagnostics"
        val a = VideoClip(id = "diagnostic-a", uri = "$root/a.mp4", name = "A", sourceUs = 5 * SECOND, width = 640, height = 360)
        val b = VideoClip(id = "diagnostic-b", uri = "$root/b.mp4", name = "B", sourceUs = 5 * SECOND, width = 360, height = 640)
        for ((suffix, duration) in listOf("cut" to 0L, "short" to 100_000L, "crossfade" to 500_000L, "long" to 2 * SECOND, "cube" to 2 * SECOND)) {
            val transitions = if (duration == 0L) emptyList() else listOf(TransitionInstance(
                "diagnostic-ab", if (suffix == "cube") "cube_right" else "cross_dissolve",
                a.id, b.id, duration, easing = Easing.LINEAR))
            val project = Project(id = "transition-diagnostic-$suffix", name = "Diagnóstico transição $suffix",
                videos = listOf(a, b), aspect = 16f / 9, transitions = transitions)
            val bytes = ByteArrayOutputStream().also { ProjectCodec.write(project, it) }.toByteArray()
            val read = ProjectCodec.read(ByteArrayInputStream(bytes))
            assertEquals(project.videos, read.videos)
            assertEquals(project.transitions, read.transitions)
            assertEquals(project.durationUs, read.durationUs)
            directory?.resolve("${project.id}.r15")?.writeBytes(bytes)
        }
    }
}
