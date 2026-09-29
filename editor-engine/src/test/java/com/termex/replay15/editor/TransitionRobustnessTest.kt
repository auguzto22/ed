package com.termex.replay15.editor

import com.termex.replay15.editor.assets.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.render.TransitionShaderBuilder
import com.termex.replay15.editor.ui.TransitionPreviewHelper
import org.junit.Assert.*
import org.junit.Test

class TransitionRobustnessTest {

    private fun testClip(id: String, durationUs: Long): VideoClip = VideoClip(
        id = id,
        uri = "file:///$id.mp4",
        name = id,
        sourceUs = durationUs,
        width = 1080,
        height = 1920,
    )

    @Test
    fun `shader must not contain duplicate uniforms`() {
        val definition = TransitionDefinition(
            id = "test_trans",
            name = "Test Transition",
            category = "test",
            engine = TransitionEngine.BLEND,
            shaderFile = "test.frag",
            parameters = listOf(
                EffectParameter(
                    id = "mode",
                    name = "Mode",
                    default = 0f,
                    min = 0f,
                    max = 1f,
                ),
                EffectParameter(
                    id = "softness",
                    name = "Softness",
                    default = 0.5f,
                    min = 0f,
                    max = 1f,
                ),
            ),
        )

        // Fragment shader that already explicitly defines uniform float p_mode;
        val header = "precision highp float;\nuniform sampler2D uTexA;\nuniform sampler2D uTexB;\nuniform float uProgress;"
        val shaderBody = """
            uniform float p_mode;
            vec4 reclyTransition(vec2 uv, float progress) {
                return vec4(p_mode, p_softness, progress, 1.0);
            }
        """.trimIndent()
        val footer = "void main() { gl_FragColor = reclyTransition(vec2(0.5), uProgress); }"

        val shader = TransitionShaderBuilder.buildFragmentShader(header, shaderBody, footer, definition)

        // Verify that validateNoDuplicateUniforms passes
        TransitionShaderBuilder.validateNoDuplicateUniforms(shader)

        // Verify that p_mode appears exactly once despite being in parameters and shaderBody
        val pModeMatches = Regex("""uniform\s+\w+\s+p_mode\s*;""").findAll(shader).count()
        assertEquals(1, pModeMatches)

        // Verify that missing uniform p_softness was generated exactly once
        val pSoftnessMatches = Regex("""uniform\s+\w+\s+p_softness\s*;""").findAll(shader).count()
        assertEquals(1, pSoftnessMatches)

        // Verify that validateNoDuplicateUniforms throws on deliberate duplicate uniforms
        val faultyShader = "uniform float uProgress;\nuniform float uProgress;\nvoid main(){}"
        assertThrows(IllegalArgumentException::class.java) {
            TransitionShaderBuilder.validateNoDuplicateUniforms(faultyShader)
        }
    }

    @Test
    fun `removing transition must keep it removed after dialog closes`() {
        val leftClipId = "clip-1"
        val rightClipId = "clip-2"
        val clipA = testClip(leftClipId, 5_000_000L)
        val clipB = testClip(rightClipId, 5_000_000L)

        val trans = TransitionInstance(
            id = "t1",
            transitionId = "cross_dissolve",
            leftClipId = leftClipId,
            rightClipId = rightClipId,
            durationUs = 1_000_000L,
        )

        var project = Project(
            videos = listOf(clipA, clipB),
            transitions = listOf(trans),
        )

        assertNotNull(project.transitionBetween(leftClipId, rightClipId))

        // Simulate user clicking "Remover" action:
        // apply(project.withoutTransition(leftClipId, rightClipId))
        val updatedProject = project.withoutTransition(leftClipId, rightClipId)
        project = updatedProject

        // Simulate dialog dismiss listener:
        // previewApply(project())
        // activeDialog = null
        // The project state must remain with transition removed (assertNull)
        assertNull(project.transitionBetween(leftClipId, rightClipId))
        assertEquals(0, project.transitions.size)
    }

    @Test
    fun `transition preview must begin before cut`() {
        val cutUs = 10_000_000L
        val region = TransitionPreviewHelper.calculatePreviewRegion(
            cutUs = cutUs,
            projectDurationUs = 20_000_000L,
        )

        assertTrue("Preview start must begin before cut point", region.startUs < 10_000_000L)
        assertTrue("Preview end must extend after cut point", region.endUs > 10_000_000L)
        assertEquals(10_000_000L - 750_000L, region.startUs)
        assertEquals(10_000_000L + 1_250_000L, region.endUs)
    }

    @Test
    fun `transition preview clamps properly at start and end of project`() {
        // Cut very close to project start (cut at 200ms, pre-roll 750ms would be negative)
        val startRegion = TransitionPreviewHelper.calculatePreviewRegion(
            cutUs = 200_000L,
            projectDurationUs = 10_000_000L,
        )
        assertEquals(0L, startRegion.startUs)
        assertEquals(200_000L + 1_250_000L, startRegion.endUs)

        // Cut very close to project end (cut at 9.5s, post-roll 1.25s exceeds project duration 10s)
        val endRegion = TransitionPreviewHelper.calculatePreviewRegion(
            cutUs = 9_500_000L,
            projectDurationUs = 10_000_000L,
        )
        assertEquals(9_500_000L - 750_000L, endRegion.startUs)
        assertEquals(10_000_000L, endRegion.endUs)
    }
}
