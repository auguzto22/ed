package com.termex.replay15.editor.render

import com.termex.replay15.editor.assets.TransitionDefinition

/**
 * Assembles transition fragment shaders (header + generated parameter uniforms + body + footer).
 * Pure Kotlin so preview, export and unit tests share exactly the same source.
 */
object TransitionShaderBuilder {
    /** Parameter that picks the variant inside a shared shader; never user-editable. */
    const val MODE = "mode"

    private val UNIFORM_REGEX = Regex("""uniform\s+\w+\s+([A-Za-z0-9_]+)\s*;""")
    private val BODY_PARAM_REGEX = Regex("""\bp_([a-zA-Z0-9_]+)\b""")

    fun validateShaderBody(body: String, definition: TransitionDefinition) {
        val declaredUniforms = UNIFORM_REGEX.findAll(body).map { it.groupValues[1] }.toList()
        require(declaredUniforms.size == declaredUniforms.distinct().size) {
            "TRANSITION SHADER VALIDATION FAILED: Shader '${definition.shaderFile}' declares duplicate uniforms."
        }
    }

    fun findBodyParamUniforms(body: String): Set<String> {
        return BODY_PARAM_REGEX.findAll(body).map { it.groupValues[1] }.toSet()
    }

    fun buildParameterUniforms(
        body: String,
        definition: TransitionDefinition
    ): String {
        val bodyParams = findBodyParamUniforms(body)
        val declaredIds = UNIFORM_REGEX.findAll(body).map { it.groupValues[1] }
            .filter { it.startsWith("p_") }.map { it.removePrefix("p_") }.toSet()
        val allParamIds = (definition.parameters.map { it.id }.toSet() + bodyParams - declaredIds).sorted()
        return allParamIds.joinToString("\n") { "uniform float p_$it;" }
    }

    fun validateNoDuplicateUniforms(shader: String) {
        val names = UNIFORM_REGEX
            .findAll(shader)
            .map { it.groupValues[1] }
            .toList()

        val duplicates = names
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }

        require(duplicates.isEmpty()) {
            "Duplicate GLSL uniforms: ${duplicates.keys}"
        }
    }

    fun buildFragmentShader(
        header: String,
        body: String,
        footer: String,
        definition: TransitionDefinition
    ): String {
        validateShaderBody(body, definition)
        val bodyUniforms = UNIFORM_REGEX.findAll(body).map { it.groupValues[1] }.toSet()
        val sanitizedHeader = header.lines().filterNot { line ->
            val match = UNIFORM_REGEX.find(line)
            match != null && match.groupValues[1] in bodyUniforms
        }.joinToString("\n")

        val generatedUniforms = buildParameterUniforms(body, definition)
        val finalShader = if (generatedUniforms.isNotBlank()) {
            "$sanitizedHeader\n$generatedUniforms\n$body\n$footer"
        } else {
            "$sanitizedHeader\n$body\n$footer"
        }
        validateNoDuplicateUniforms(finalShader)
        return finalShader
    }

    /**
     * Final `p_*` uniform values (uniform name to value) for a transition instance: declared
     * parameters clamped to their range, then supplemental values for anything else the body
     * reads. Preview and export both call this, so a transition looks the same after exporting.
     */
    fun uniformValues(body: String, definition: TransitionDefinition, parameters: Map<String, Float>): Map<String, Float> =
        uniformValues(definition, supplementalParameters(body, definition), parameters)

    fun uniformValues(definition: TransitionDefinition, supplemental: Collection<String>, parameters: Map<String, Float>): Map<String, Float> {
        val values = LinkedHashMap<String, Float>()
        definition.parameters.forEach { parameter ->
            // The mode selects the transition inside a shared shader, so it always comes from the
            // definition: older projects may carry a stale value from the former "Modo" slider.
            val value = if (parameter.id == MODE) parameter.default else parameters[parameter.id] ?: parameter.default
            values["p_${parameter.id}"] = value.coerceIn(parameter.min, parameter.max)
        }
        supplemental.sorted().forEach { id -> values["p_$id"] = supplementalDefault(id) }
        return values
    }

    /** `p_*` parameters the body reads that its definition does not declare. */
    fun supplementalParameters(body: String, definition: TransitionDefinition): Set<String> =
        findBodyParamUniforms(body) - definition.parameters.map { it.id }.toSet()

    /**
     * Value for a `p_*` uniform the body reads but the definition does not declare. Preview and
     * export must agree, otherwise the same transition looks different after exporting.
     */
    fun supplementalDefault(id: String): Float = when (id) {
        "perspective" -> 1.0f
        "radius" -> 16.0f
        "softness" -> 0.1f
        "intensity", "strength", "blur" -> 1.0f
        else -> 0.0f
    }
}
