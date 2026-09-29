package com.termex.replay15.editor

import com.termex.replay15.editor.assets.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.project.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class TransitionCatalogTest {

    private fun loadCatalog(): List<TransitionDefinition> {
        val relative = "src/main/assets/editor/transitions.json"
        val file = sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?: error("transitions.json not found")
        val array = JSONArray(file.readText())
        return List(array.length()) { TransitionDefinition.parse(array.getJSONObject(it).toString()) }
    }

    private fun loadShader(name: String): String {
        val clean = if (name.endsWith(".frag")) name else "$name.frag"
        val relative = "src/main/assets/editor/transitions/$clean"
        return sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Shader not found: $name")
    }

    private fun loadHeader(): String {
        val relative = "src/main/res/raw/transition_header.glsl"
        return sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("transition_header.glsl not found")
    }

    private fun loadFooter(): String {
        val relative = "src/main/res/raw/transition_footer.glsl"
        return sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("transition_footer.glsl not found")
    }

    @Test
    fun `catalog contains at least 73 unique valid transitions`() {
        val definitions = loadCatalog()
        assertTrue("Expected at least 73 transitions, found ${definitions.size}", definitions.size >= 73)
        val ids = definitions.map { it.id }
        assertEquals("Duplicate transition IDs found", ids.size, ids.distinct().size)
    }

    @Test
    fun `all 17 priority transitions are present in the catalog`() {
        val definitions = loadCatalog()
        val ids = definitions.map { it.id }.toSet()
        val priority = listOf(
            "cross_dissolve",
            "dip_to_black",
            "slide_left",
            "push_left",
            "wipe_left",
            "circle_wipe",
            "smooth_zoom",
            "zoom_blur",
            "whip_left",
            "blur_dissolve",
            "white_flash",
            "light_leak",
            "digital_glitch",
            "rgb_glitch",
            "luma_fade",
            "ripple",
            "spin_zoom",
        )
        for (p in priority) {
            assertTrue("Priority transition missing: $p", ids.contains(p))
        }
    }

    @Test
    fun `all categories are represented in catalog`() {
        val definitions = loadCatalog()
        val categories = definitions.map { TransitionCatalog.canonicalCategory(it.category) }.toSet()
        val expected = setOf(
            TransitionCatalog.CAT_BASIC,
            TransitionCatalog.CAT_SLIDE,
            TransitionCatalog.CAT_WIPE,
            TransitionCatalog.CAT_ZOOM,
            TransitionCatalog.CAT_MOTION,
            TransitionCatalog.CAT_BLUR,
            TransitionCatalog.CAT_LIGHT,
            TransitionCatalog.CAT_GLITCH,
            TransitionCatalog.CAT_LUMA,
            TransitionCatalog.CAT_DISTORTION,
            TransitionCatalog.CAT_3D,
            TransitionCatalog.CAT_CREATIVE,
        )
        assertTrue("Missing categories: ${expected - categories}", categories.containsAll(expected))
    }

    @Test
    fun `fade does not directly overlay both clips`() {
        val definitions = loadCatalog()
        val crossMode = definitions.first { it.id == "cross_dissolve" }
            .parameters.first { it.id == "mode" }.default
        val fadeMode = definitions.first { it.id == "fade" }
            .parameters.first { it.id == "mode" }.default

        assertNotEquals("Fade must not behave like Cross Dissolve", crossMode, fadeMode)
    }

    @Test
    fun `catalog entries do not accidentally reuse the same shader mode`() {
        val definitions = loadCatalog()
        val dispatchKeys = definitions.mapNotNull { definition ->
            val mode = definition.parameters.firstOrNull { it.id == "mode" } ?: return@mapNotNull null
            "${definition.shaderFile}:${mode.default}" to definition.id
        }
        val duplicates = dispatchKeys.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }

        assertTrue("Transitions sharing an identical shader mode: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `special transform transitions keep their intended shader modes`() {
        val definitions = loadCatalog().associateBy { it.id }
        fun mode(id: String) = definitions.getValue(id).parameters.first { it.id == "mode" }.default

        assertEquals(13f, mode("zoom_rotate"), 0f)
        assertEquals(14f, mode("spin"), 0f)
        assertEquals(15f, mode("spin_zoom"), 0f)
        assertEquals(16f, mode("rotate_push"), 0f)
    }

    @Test
    fun `every transition resolves to an existing shader containing reclyTransition entry point`() {
        val definitions = loadCatalog()
        for (def in definitions) {
            val source = loadShader(def.shaderFile)
            assertTrue("Shader ${def.shaderFile} must define reclyTransition", source.contains("reclyTransition"))
        }
    }

    @Test
    fun `transition parameters have valid boundaries`() {
        val definitions = loadCatalog()
        for (def in definitions) {
            assertTrue("Min duration must be positive", def.minDurationUs > 0)
            assertTrue("Max duration must be >= min duration", def.maxDurationUs >= def.minDurationUs)
            assertTrue("Default duration must be within bounds", def.defaultDurationUs in def.minDurationUs..def.maxDurationUs)
            for (p in def.parameters) {
                assertTrue("Param min must be < max for ${p.id} in ${def.id}", p.min < p.max)
                assertTrue("Param default must be in range for ${p.id} in ${def.id}", p.default in p.min..p.max)
            }
        }
    }

    @Test
    fun `progress progression is deterministic at 0, half and 1`() {
        val easing = Easing.SMOOTH
        val bezier = CubicBezier()
        val p0 = easing.apply(0f, bezier)
        val pHalf = easing.apply(0.5f, bezier)
        val p1 = easing.apply(1f, bezier)

        assertEquals("Progress at 0.0 must evaluate to 0.0", 0f, p0, 0.0001f)
        assertEquals("Progress at 0.5 must evaluate to 0.5 for symmetric smooth easing", 0.5f, pHalf, 0.0001f)
        assertEquals("Progress at 1.0 must evaluate to 1.0", 1f, p1, 0.0001f)

        // Linear progression
        assertEquals(0f, Easing.LINEAR.apply(0f, bezier), 0.0001f)
        assertEquals(0.5f, Easing.LINEAR.apply(0.5f, bezier), 0.0001f)
        assertEquals(1f, Easing.LINEAR.apply(1f, bezier), 0.0001f)
    }

    private fun testClip(id: String, durationUs: Long = 4 * SECOND) = VideoClip(
        id = id,
        uri = "content://$id",
        name = "Clip $id",
        sourceUs = durationUs,
        width = 1080,
        height = 1920,
        outUs = durationUs,
    )

    @Test
    fun `transition round trip serialization preserves all parameters and easing`() {
        val trans = TransitionInstance(
            id = "t-1",
            transitionId = "whip_left",
            leftClipId = "clip-a",
            rightClipId = "clip-b",
            durationUs = 750_000L,
            parameters = mapOf("direction" to 0f, "blur" to 0.8f),
            easing = Easing.EASE_OUT,
        )
        val project = Project(
            videos = listOf(
                testClip("clip-a", 5 * SECOND),
                testClip("clip-b", 5 * SECOND),
            ),
            transitions = listOf(trans),
        )

        val out = ByteArrayOutputStream()
        ProjectCodec.write(project, out)
        val restored = ProjectCodec.read(ByteArrayInputStream(out.toByteArray()))

        assertEquals(1, restored.transitions.size)
        val restoredTrans = restored.transitions.single()
        assertEquals(trans.id, restoredTrans.id)
        assertEquals(trans.transitionId, restoredTrans.transitionId)
        assertEquals(trans.leftClipId, restoredTrans.leftClipId)
        assertEquals(trans.rightClipId, restoredTrans.rightClipId)
        assertEquals(trans.durationUs, restoredTrans.durationUs)
        assertEquals(trans.easing, restoredTrans.easing)
        assertEquals(trans.parameters["direction"] ?: -1f, restoredTrans.parameters["direction"] ?: -2f, 0.001f)
        assertEquals(trans.parameters["blur"] ?: -1f, restoredTrans.parameters["blur"] ?: -2f, 0.001f)
    }

    @Test
    fun `transition windows preserve cuts and project duration`() {
        val clipA = testClip("a", 4 * SECOND)
        val clipB = testClip("b", 4 * SECOND)
        val clipC = testClip("c", 4 * SECOND)
        val trans1 = TransitionInstance(id = "t1", transitionId = "cross_dissolve", leftClipId = "a", rightClipId = "b", durationUs = 1 * SECOND)
        val trans2 = TransitionInstance(id = "t2", transitionId = "slide_left", leftClipId = "b", rightClipId = "c", durationUs = 500_000L)

        val project = Project(videos = listOf(clipA, clipB, clipC), transitions = listOf(trans1, trans2))

        assertEquals(0L, project.startOf(0))
        assertEquals(4 * SECOND, project.startOf(1))
        assertEquals(8 * SECOND, project.startOf(2))
        assertEquals(12 * SECOND, project.durationUs)
    }

    @Test
    fun `removing clip automatically purges associated transitions`() {
        val clipA = testClip("a", 4 * SECOND)
        val clipB = testClip("b", 4 * SECOND)
        val trans = TransitionInstance(id = "t1", transitionId = "cross_dissolve", leftClipId = "a", rightClipId = "b", durationUs = 1 * SECOND)
        val project = Project(videos = listOf(clipA, clipB), transitions = listOf(trans))

        val cleaned = project.copy(videos = listOf(clipA), transitions = project.cleanTransitions(listOf(clipA)))
        assertTrue("Transition must be purged when clip B is removed", cleaned.transitions.isEmpty())
    }

    @Test
    fun `withTransition clamps duration to available media bounds`() {
        val clipA = testClip("a", 800_000L)
        val clipB = testClip("b", 600_000L)
        val trans = TransitionInstance(id = "t1", transitionId = "cross_dissolve", leftClipId = "a", rightClipId = "b", durationUs = 1_500_000L)
        val project = Project(videos = listOf(clipA, clipB))

        val updated = project.withTransition(trans)
        val applied = updated.transitions.single()
        assertTrue("Applied transition duration must be <= min(clipA, clipB)", applied.durationUs <= minOf(clipA.durationUs, clipB.durationUs))
        assertEquals(600_000L, applied.durationUs)
    }

    @Test
    fun `filter search and categories in TransitionCatalog`() {
        val defs = loadCatalog()
        val blurList = TransitionCatalog.filter(defs, TransitionCatalog.CAT_BLUR)
        assertTrue(blurList.isNotEmpty())
        assertTrue(blurList.all { TransitionCatalog.canonicalCategory(it.category) == TransitionCatalog.CAT_BLUR })

        val searchResult = TransitionCatalog.filter(defs, TransitionCatalog.CATEGORY_ALL, query = "glitch")
        assertTrue(searchResult.isNotEmpty())
        assertTrue(searchResult.any { it.id == "digital_glitch" })
        assertTrue(searchResult.any { it.id == "rgb_glitch" })
    }

    @Test
    fun `every transition definition produces valid fragment shader with no duplicate uniforms`() {
        val definitions = loadCatalog()
        val header = loadHeader()
        val footer = loadFooter()
        for (def in definitions) {
            val body = loadShader(def.shaderFile)
            val fullShader = com.termex.replay15.editor.render.TransitionShaderBuilder
                .buildFragmentShader(header, body, footer, def)
            com.termex.replay15.editor.render.TransitionShaderBuilder
                .validateNoDuplicateUniforms(fullShader)
        }
    }

    @Test
    fun `all parameters defined in transitions json map to valid uniforms`() {
        val definitions = loadCatalog()
        val header = loadHeader()
        val footer = loadFooter()
        for (def in definitions) {
            val body = loadShader(def.shaderFile)
            val fullShader = com.termex.replay15.editor.render.TransitionShaderBuilder
                .buildFragmentShader(header, body, footer, def)
            for (param in def.parameters) {
                val uniformDecl = "uniform float p_${param.id};"
                assertTrue(
                    "Transition ${def.id} missing expected uniform declaration for parameter ${param.id}",
                    fullShader.contains(uniformDecl)
                )
            }
        }
    }

    @Test
    fun `no transition parameter collides with reserved engine uniforms`() {
        val reserved = setOf("uTexA", "uTexB", "uResolution", "uProgress", "vUv")
        val definitions = loadCatalog()
        for (def in definitions) {
            for (param in def.parameters) {
                assertFalse(
                    "Parameter ${param.id} in transition ${def.id} collides with reserved uniform",
                    reserved.contains(param.id)
                )
            }
        }
    }
}
