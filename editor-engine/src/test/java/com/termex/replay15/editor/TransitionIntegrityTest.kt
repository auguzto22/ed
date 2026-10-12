package com.termex.replay15.editor

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.core.EditorSessionController
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.history.ProjectHistory
import com.termex.replay15.editor.preview.engine.ActiveClipResolver
import org.junit.Assert.*
import org.junit.Test

class TransitionIntegrityTest {
    private fun clip(id: String, seconds: Long) =
        VideoClip(id = id, uri = "file:///$id.mp4", name = id, sourceUs = seconds * 1_000_000L, width = 1080, height = 1920)

    private fun base() = Project(videos = listOf(clip("a", 4), clip("b", 4), clip("c", 4)))
        .withTransition(TransitionInstance(transitionId = "cross_dissolve", leftClipId = "a", rightClipId = "b", durationUs = 2_000_000L))

    private fun session(project: Project) = EditorSessionController(ProjectHistory(project))

    @Test fun trimmingTheRightClipShortensTheTransition() {
        val session = session(base())
        session.apply(session.project.changeVideo(1) { it.copy(outUs = 500_000L) })
        assertEquals(500_000L, session.project.transitions.single().durationUs)
    }

    @Test fun outgoingClipIsNotHeldPastTheTransitionWindow() {
        val trimmed = base().changeVideo(1) { it.copy(outUs = 500_000L) }.withSanitizedTransitions()
        val resolver = ActiveClipResolver(trimmed)
        assertEquals(listOf("a", "b"), resolver.resolve(4_200_000L).videos.map { it.clip.id })
        assertEquals(listOf("c"), resolver.resolve(4_600_000L).videos.map { it.clip.id })
    }

    @Test fun nonAdjacentTransitionsAreDropped() {
        val project = base()
        val list = project.videos.toMutableList().apply { add(1, clip("x", 2)) }
        val session = session(project)
        session.apply(project.copy(videos = list))
        assertTrue(session.project.transitions.isEmpty())
    }

    @Test fun splittingKeepsTheOutgoingTransitionOnTheRightHalf() {
        val split = base().split(0, 2_000_000L)
        val transition = split.transitions.single()
        assertEquals(split.videos[1].id, transition.leftClipId)
        assertEquals("b", transition.rightClipId)
    }

    @Test fun loadingRepairsOrphanedTransitions() {
        val project = base().copy(videos = listOf(clip("a", 4), clip("c", 4), clip("b", 4)))
        val bytes = java.io.ByteArrayOutputStream().also { com.termex.replay15.editor.project.ProjectCodec.write(project, it) }.toByteArray()
        val restored = com.termex.replay15.editor.project.ProjectCodec.read(bytes.inputStream())
        assertTrue(restored.transitions.isEmpty())
    }
}
