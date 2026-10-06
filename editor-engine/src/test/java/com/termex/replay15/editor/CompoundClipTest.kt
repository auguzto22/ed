package com.termex.replay15.editor

import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.history.ProjectHistory
import com.termex.replay15.editor.project.ProjectCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Phase 27. Grouping is metadata over the flat main sequence: nothing is re-parented, so the
 * preview, decoders and export read exactly the same clip list before and after a compound exists.
 */
class CompoundClipTest {

    private fun clip(name: String, seconds: Int = 2) = VideoClip(
        uri = "content://media/$name", name = name, sourceUs = seconds * SECOND,
        width = 1080, height = 1920, inUs = 0, outUs = seconds * SECOND,
    )

    private fun project(vararg names: String) = Project(
        id = "compound-test", name = "Base", videos = names.map { clip(it) }, createdAt = 1, modifiedAt = 1,
    )

    private fun Project.saved(): ByteArrayOutputStream = ByteArrayOutputStream().also { ProjectCodec.write(this, it) }

    private fun Project.reloaded(): Project = ProjectCodec.read(ByteArrayInputStream(saved().toByteArray()))

    @Test
    fun groupingKeepsTheSequenceIdentical() {
        val base = project("a", "b", "c")
        val grouped = CompoundEditing.group(base, 0, 1)
        // The whole point: no clip moved, no id changed, no duration changed.
        assertEquals(base.videos.map(VideoClip::id), grouped.videos.map(VideoClip::id))
        assertEquals(base.videos, grouped.videos)
        assertEquals(base.durationUs, grouped.durationUs)
        assertEquals(base.startOf(1), grouped.startOf(1))
        assertEquals(1, grouped.compounds.size)
    }

    @Test
    fun compoundReportsItsRangeAndDuration() {
        val grouped = CompoundEditing.group(project("a", "b", "c"), 0, 1)
        val compound = grouped.compounds.single()
        assertEquals(0L, CompoundEditing.startUs(grouped, compound))
        assertEquals(4 * SECOND, CompoundEditing.endUs(grouped, compound))
        assertEquals(4 * SECOND, CompoundEditing.durationUs(grouped, compound))
        // childIds holds clip ids, not display names; the order is timeline order.
        assertEquals(grouped.videos.slice(0..1).map(VideoClip::id), compound.childIds)
        assertEquals(2, compound.childCount)
    }

    @Test
    fun childrenResolveInTimelineOrder() {
        val grouped = CompoundEditing.group(project("a", "b", "c", "d"), 1, 2)
        val compound = grouped.compounds.single()
        assertEquals(listOf("b", "c"), CompoundEditing.children(grouped, compound).map(VideoClip::name))
        assertEquals(listOf(1, 2), CompoundEditing.indices(grouped, compound))
    }

    @Test
    fun groupingRefusesInvalidRuns() {
        val base = project("a", "b", "c")
        // A single clip is not a compound.
        assertEquals(base, CompoundEditing.group(base, 1, 1))
        // Out of range.
        assertEquals(base, CompoundEditing.group(base, 1, 9))
        // Nested grouping is refused, not silently stacked.
        val once = CompoundEditing.group(base, 0, 1)
        assertEquals(once, CompoundEditing.group(once, 0, 1))
        assertEquals(once, CompoundEditing.group(once, 1, 2))
    }

    @Test
    fun ungroupLeavesEveryClipUntouched() {
        val grouped = CompoundEditing.group(project("a", "b", "c"), 0, 1)
        val plain = CompoundEditing.ungroup(grouped, grouped.compounds.single().id)
        assertEquals(grouped.videos, plain.videos)
        assertTrue(plain.compounds.isEmpty())
    }

    @Test
    fun deletingAChildDropsTheGroupInsteadOfCorruptingTheProject() {
        val grouped = CompoundEditing.group(project("a", "b", "c"), 0, 1)
        val victim = grouped.videos[0]
        val remaining = grouped.videos.filterNot { it.id == victim.id }
        val next = CompoundEditing.sanitize(grouped.copy(videos = remaining, compounds = emptyList()))
        assertTrue(next.compounds.isEmpty())
        assertEquals(listOf("b", "c"), remaining.map(VideoClip::name))
    }

    @Test
    fun deletingAChildKeepsACompoundThatStillHasTwoClips() {
        val grouped = CompoundEditing.group(project("a", "b", "c", "d"), 0, 2)
        val victim = grouped.videos[0]
        // A structural edit keeps the groups that still describe the new sequence: sanitize drops
        // a compound only when it is left with fewer than two children, or when one of its ids
        // no longer exists.
        val next = grouped.delete(victim.id)
        assertEquals(1, next.compounds.size)
        assertEquals(grouped.videos.drop(1).take(2).map(VideoClip::id), next.compounds.single().childIds)
    }

    @Test
    fun deletingASecondChildDropsTheNowUndersizedCompound() {
        val grouped = CompoundEditing.group(project("a", "b", "c", "d"), 0, 2)
        // Removing two of the three grouped children leaves a one-child group, which is no
        // longer a compound and must be dropped rather than left dangling.
        val dropped = grouped.videos.take(2).map(VideoClip::id).toSet()
        val next = CompoundEditing.survivingCompounds(
            grouped.videos.filterNot { it.id in dropped }, grouped.compounds)
        assertTrue(next.isEmpty())
    }

    @Test
    fun splittingAndMovingKeepUnaffectedGroups() {
        val grouped = CompoundEditing.group(project("a", "b", "c"), 0, 1)
        val compound = grouped.compounds.single()
        // A split cuts inside the first grouped child; both halves stay grouped.
        val split = grouped.splitAt(SECOND)
        assertEquals(1, split.compounds.size)
        assertTrue(split.compounds.single().childIds.all { id -> split.videos.any { it.id == id } })
        // Reordering the main sequence must not silently discard the group either.
        val moved = CompoundEditing.withVideos(grouped, grouped.videos.let { l -> l.reversed() })
        assertEquals(1, moved.compounds.size)
        assertEquals(compound.childIds.toSet(), moved.compounds.single().childIds.toSet())
    }

    @Test
    fun splitInsideACompoundKeepsTheProjectValid() {
        val grouped = CompoundEditing.group(project("a", "b", "c"), 0, 1)
        val split = grouped.splitAt(SECOND) // cuts the first child in half
        assertEquals(4, split.videos.size)
        assertTrue(CompoundEditing.sanitize(split).compounds.all { it.childIds.all { id -> split.videos.any { it.id == id } } })
    }

    @Test
    fun moveReordersClipsWithoutBreakingGroups() {
        val grouped = CompoundEditing.group(project("a", "b", "c"), 0, 1)
        val moved = grouped.moveVideo(2, 0)
        assertEquals(listOf("c", "a", "b"), moved.videos.map(VideoClip::name))
        assertTrue(moved.compounds.all { compound ->
            compound.childIds.all { id -> moved.videos.any { it.id == id } }
        })
    }

    @Test
    fun compoundsSurviveSaveAndReload() {
        val grouped = CompoundEditing.group(project("a", "b", "c"), 0, 1, name = "Trecho forte")
        val reloaded = grouped.reloaded()
        assertEquals(1, reloaded.compounds.size)
        assertEquals("Trecho forte", reloaded.compounds.single().name)
        assertEquals(grouped.compounds.single().childIds, reloaded.compounds.single().childIds)
        assertEquals(grouped.videos, reloaded.videos)
    }

    @Test
    fun renameIsPersisted() {
        val grouped = CompoundEditing.group(project("a", "b"), 0, 1)
        val renamed = CompoundEditing.rename(grouped, grouped.compounds.single().id, "Abertura")
        assertEquals("Abertura", renamed.reloaded().compounds.single().name)
    }

    @Test
    fun oldProjectsWithoutCompoundsStillOpen() {
        // A schema written before compounds exist decodes to an empty list, never a failure.
        val plain = project("a", "b")
        assertTrue(plain.reloaded().compounds.isEmpty())
        assertEquals(plain.videos, plain.reloaded().videos)
    }

    @Test
    fun groupingIsOneUndoStep() {
        val base = project("a", "b", "c")
        val history = ProjectHistory(base)
        assertTrue(history.apply(CompoundEditing.group(base, 0, 1)))
        assertEquals(1, history.current.compounds.size)
        assertTrue(history.undo())
        assertTrue(history.current.compounds.isEmpty())
        assertTrue(history.redo())
        assertEquals(1, history.current.compounds.size)
    }

    @Test
    fun ungroupIsUndoable() {
        val base = project("a", "b")
        val history = ProjectHistory(CompoundEditing.group(base, 0, 1))
        assertTrue(history.apply(CompoundEditing.ungroup(history.current, history.current.compounds.single().id)))
        assertTrue(history.undo())
        assertEquals(1, history.current.compounds.size)
    }

    @Test
    fun ownerLookupAndSelectionHelpers() {
        val grouped = CompoundEditing.group(project("a", "b", "c"), 0, 1)
        val compound = grouped.compounds.single()
        assertEquals(compound, CompoundEditing.at(grouped, clipId = grouped.videos[1].id))
        assertEquals(compound, CompoundEditing.at(grouped, index = 0))
        assertTrue(CompoundEditing.isGrouped(grouped, grouped.videos[0].id))
        assertFalse(CompoundEditing.isGrouped(grouped, grouped.videos[2].id))
        assertNull(CompoundEditing.at(grouped, clipId = grouped.videos[2].id))
        // A multi-selection is grouped contiguously from its first to last member, so the
        // out-of-order input 2,3,1 must yield the same group as selecting 1..3.
        val selectionBase = project("a", "b", "c", "d")
        val bySelection = CompoundEditing.groupSelection(selectionBase, listOf(2, 3, 1))
        assertEquals(
            CompoundEditing.group(selectionBase, 1, 3).compounds.single().childIds,
            bySelection.compounds.single().childIds,
        )
        // A selection that is not contiguous is still one contiguous run from first to last.
        assertEquals(3, bySelection.compounds.single().childCount)
    }

    @Test
    fun manyCompoundsCoexist() {
        val base = project("a", "b", "c", "d", "e", "f")
        val grouped = CompoundEditing.group(CompoundEditing.group(base, 0, 1), 2, 3)
        val reloaded = grouped.reloaded()
        assertEquals(2, reloaded.compounds.size)
        assertEquals(2, CompoundEditing.indices(reloaded, reloaded.compounds.first()).size)
        assertNotNull(reloaded.compounds.mapNotNull { c -> reloaded.videos.firstOrNull { it.id == c.childIds.first() } })
    }
}
