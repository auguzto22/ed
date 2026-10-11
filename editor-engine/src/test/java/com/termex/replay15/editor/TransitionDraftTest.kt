package com.termex.replay15.editor

import com.termex.replay15.editor.assets.TransitionDefinition
import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.render.TransitionShaderBuilder
import com.termex.replay15.editor.ui.TransitionDraft
import com.termex.replay15.editor.ui.TransitionGroups
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TransitionDraftTest {
    private val definitions: List<TransitionDefinition> by lazy {
        val relative = "src/main/assets/editor/transitions.json"
        val file = sequenceOf(File(relative), File("editor-engine/$relative")).first(File::isFile)
        val array = JSONArray(file.readText())
        List(array.length()) { TransitionDefinition.parse(array.getJSONObject(it).toString()) }
    }

    private fun def(id: String) = definitions.single { it.id == id }

    private fun clip(id: String, seconds: Long = 4) = VideoClip(id = id, uri = "file:///$id.mp4", name = id,
        sourceUs = 12 * SECOND, inUs = SECOND, outUs = SECOND + seconds * SECOND, width = 1920, height = 1080)

    private fun project(vararg clips: VideoClip, transitions: List<TransitionInstance> = emptyList()) =
        Project(videos = clips.toList(), transitions = transitions)

    @Test fun aNewCutStartsWithoutTransitionAndPreviewsOnlyAfterAChoice() {
        val original = project(clip("a"), clip("b"))
        val draft = TransitionDraft(original, "a", "b", definitions)
        assertNull(draft.definition)
        assertFalse(draft.hadTransition)
        assertEquals(original, draft.draftProject())
        draft.select(def("whip_left"))
        assertEquals(def("whip_left").defaultDurationUs, draft.durationUs)
        val transition = draft.draftProject().transitionBetween("a", "b")!!
        assertEquals(TransitionAlignment.CENTER, transition.alignment)
        assertEquals(Easing.SMOOTH, transition.easing)
    }

    @Test fun durationIsSteppedInProjectFramesAndLabelledInPortuguese() {
        val draft = TransitionDraft(project(clip("a"), clip("b")), "a", "b", definitions)
        draft.select(def("cross_dissolve"))
        draft.setFrames(15)
        assertEquals(500_000L, draft.durationUs)
        assertEquals("0,50 s · 15 quadros", draft.durationLabel())
        assertEquals(3, draft.minFrames()) // 0.1 s at 30 fps
        draft.setFrames(1)
        assertEquals(100_000L, draft.durationUs)
        draft.setDurationUs(1_234_567L)
        assertEquals(37, draft.frames())
    }

    @Test fun limitsFollowAlignmentAndNeighboursAndClampWhenAlignmentChanges() {
        val a = clip("a"); val b = clip("b", seconds = 2); val c = clip("c")
        val neighbour = TransitionInstance("bc", "cross_dissolve", "b", "c", SECOND, alignment = TransitionAlignment.END_AT_CUT)
        val draft = TransitionDraft(project(a, b, c, transitions = listOf(neighbour)), "a", "b", definitions)
        draft.select(def("cross_dissolve"))
        // B lasts 2 s and its last second already belongs to the B→C transition.
        draft.setAlignment(TransitionAlignment.START_AT_CUT)
        assertEquals(SECOND, draft.maxDurationUs())
        draft.setAlignment(TransitionAlignment.END_AT_CUT)
        assertEquals(2 * SECOND, draft.maxDurationUs())
        draft.setDurationUs(2 * SECOND)
        draft.setAlignment(TransitionAlignment.START_AT_CUT)
        assertEquals(SECOND, draft.durationUs)
        assertEquals(30, draft.maxFrames())
    }

    @Test fun staleModeFromOldProjectsNeverReachesTheShader() {
        val old = TransitionInstance("ab", "dip_to_black", "a", "b", SECOND, parameters = mapOf("mode" to 7f))
        val draft = TransitionDraft(project(clip("a"), clip("b"), transitions = listOf(old)), "a", "b", definitions)
        assertTrue(draft.hadTransition)
        assertFalse(draft.instance()!!.parameters.containsKey("mode"))
        assertTrue(draft.editableParameters.none { it.id == "mode" })
        val values = TransitionShaderBuilder.uniformValues(def("dip_to_black"), emptySet(), old.parameters)
        assertEquals(1f, values.getValue("p_mode"), 0f)
    }

    @Test fun parametersResetWhenTheTransitionChangesAndStayInRange() {
        val draft = TransitionDraft(project(clip("a"), clip("b")), "a", "b", definitions)
        draft.select(def("blur_dissolve"))
        draft.setValue("radius", 999f)
        assertEquals(40f, draft.valueOf("radius"), 0f)
        draft.select(def("zoom_blur"))
        assertEquals(16f, draft.valueOf("radius"), 0f)
        draft.setValue("unknown", 3f)
        assertFalse(draft.instance()!!.parameters.containsKey("unknown"))
    }

    @Test fun applyToAllCutsKeepsIdsAndClampsEachCut() {
        val clips = arrayOf(clip("a"), clip("b", seconds = 1), clip("c"), clip("d"))
        val existing = TransitionInstance("cd-old", "cross_dissolve", "c", "d", 300_000L)
        val draft = TransitionDraft(project(*clips, transitions = listOf(existing)), "a", "b", definitions)
        draft.select(def("push_left"))
        draft.setDurationUs(SECOND)
        val all = draft.applyToAllCuts()
        assertEquals(3, all.transitions.size)
        assertTrue(all.transitions.all { it.transitionId == "push_left" })
        assertEquals("cd-old", all.transitionBetween("c", "d")!!.id)
        assertEquals(draft.instance()!!.id, all.transitionBetween("a", "b")!!.id)
        // Clip B is only 1 s long and hosts the tail of A→B and the head of B→C.
        val windows = TransitionTiming.windows(all)
        windows.zipWithNext().forEach { (x, y) -> assertTrue(x.endUs <= y.startUs) }
        assertEquals(all.durationUs, project(*clips).durationUs)
    }

    @Test fun choosingNoneRemovesOnlyThisCut() {
        val ab = TransitionInstance("ab", "cross_dissolve", "a", "b", SECOND)
        val bc = TransitionInstance("bc", "cross_dissolve", "b", "c", SECOND)
        val draft = TransitionDraft(project(clip("a"), clip("b"), clip("c"), transitions = listOf(ab, bc)), "a", "b", definitions)
        draft.select(null)
        assertEquals(listOf(bc), draft.draftProject().transitions)
        assertNull(draft.window())
    }

    @Test fun everyTransitionBelongsToAGroupAndEssentialsExist() {
        val grouped = TransitionGroups.GROUPS.drop(1).flatMap { TransitionGroups.items(it, definitions) }.map { it.id }
        assertEquals(definitions.map { it.id }.toSet(), grouped.toSet())
        assertEquals(TransitionGroups.ESSENTIALS.size, TransitionGroups.items(TransitionGroups.GROUPS.first(), definitions).size)
    }
}
