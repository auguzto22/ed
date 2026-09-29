package com.termex.replay15.editor

import com.termex.replay15.editor.assets.EffectInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.history.ProjectHistory
import org.junit.Assert.*
import org.junit.Test

class ProjectHistoryTest {
    private fun clip(effects: List<EffectInstance> = emptyList()) = VideoClip(
        uri = "content://video/history",
        name = "History",
        sourceUs = 5 * SECOND,
        width = 1920,
        height = 1080,
        effects = effects,
    )

    @Test
    fun `one gesture with many drafts creates one undo action`() {
        val initial = Project(videos = listOf(clip()))
        val history = ProjectHistory(initial)

        history.beginEdit()
        history.updateEdit(initial.changeVideo(0) { it.copy(opacity = .8f) })
        history.updateEdit(initial.changeVideo(0) { it.copy(opacity = .4f) })
        assertTrue(history.commitEdit())

        assertEquals(.4f, history.current.videos.single().opacity)
        assertTrue(history.undo())
        assertEquals(initial, history.current)
        assertTrue(history.redo())
        assertEquals(.4f, history.current.videos.single().opacity)
    }

    @Test
    fun `gesture returning to its origin and cancelled gesture create no entry`() {
        val initial = Project(videos = listOf(clip()))
        val history = ProjectHistory(initial)
        history.beginEdit()
        history.updateEdit(initial.changeVideo(0) { it.copy(opacity = .5f) })
        history.updateEdit(initial)
        assertFalse(history.commitEdit())
        assertFalse(history.canUndo)

        history.beginEdit()
        history.updateEdit(initial.changeVideo(0) { it.copy(zoom = 2f) })
        assertTrue(history.cancelEdit())
        assertEquals(initial, history.current)
        assertFalse(history.canUndo)
    }

    @Test
    fun `new committed gesture invalidates redo`() {
        val initial = Project(videos = listOf(clip()))
        val history = ProjectHistory(initial)
        history.apply(initial.changeVideo(0) { it.copy(opacity = .8f) })
        history.undo()
        assertTrue(history.canRedo)
        history.beginEdit()
        history.updateEdit(initial.changeVideo(0) { it.copy(zoom = 1.5f) })
        history.commitEdit()
        assertFalse(history.canRedo)
    }

    @Test
    fun `snapshots detach caller owned nested collections`() {
        val values = linkedMapOf("radius" to 4f)
        val effects = mutableListOf(EffectInstance("fx", "recly_glow", values = values))
        val videos = mutableListOf(clip(effects))
        val history = ProjectHistory(Project(videos = videos))

        values["radius"] = 20f
        effects.clear()
        videos.clear()

        val saved = history.current.videos.single().effects.single()
        assertEquals(4f, saved.values.getValue("radius"))
    }

    @Test
    fun `history limit bounds retained actions`() {
        val initial = Project(videos = listOf(clip()))
        val history = ProjectHistory(initial, limit = 2)
        history.apply(initial.changeVideo(0) { it.copy(opacity = .8f) })
        history.apply(initial.changeVideo(0) { it.copy(opacity = .6f) })
        history.apply(initial.changeVideo(0) { it.copy(opacity = .4f) })
        assertTrue(history.undo())
        assertTrue(history.undo())
        assertFalse(history.undo())
        assertEquals(.8f, history.current.videos.single().opacity)
    }
}
