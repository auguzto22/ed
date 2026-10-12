package com.termex.replay15.editor.transitions

import com.termex.replay15.editor.assets.TransitionDefinition
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Android/JVM transport only. Package validation, defaults and GLSL assembly live in Rust. */
object TransitionPrograms {
    const val MODE = "mode"
    internal fun query(value: JSONObject): Any = JSONTokener(NativeTransitionBridge.nativeQuery(value.toString())).nextValue()
    fun catalog(): List<TransitionDefinition> {
        val array = query(JSONObject().put("op", "catalog")) as JSONArray
        return List(array.length()) { TransitionDefinition.parse(array.getJSONObject(it).toString()) }
    }
    fun shader(id: String): String = query(JSONObject().put("op", "shader").put("id", id)) as String
    fun uniformValues(id: String, parameters: Map<String, Float>): Map<String, Float> =
        floats(query(JSONObject().put("op", "uniforms").put("id", id).put("parameters", JSONObject(parameters))) as JSONObject)
    private fun definition(d: TransitionDefinition) = JSONObject().put("id", d.id).put("shaderFile", d.shaderFile)
        .put("parameters", JSONArray().apply { d.parameters.forEach { p -> put(JSONObject().put("id", p.id).put("min", p.min).put("max", p.max).put("default", p.default)) } })
    fun buildFragmentShader(header: String, body: String, footer: String, d: TransitionDefinition): String =
        query(JSONObject().put("op", "assemble").put("header", header).put("body", body).put("footer", footer).put("definition", definition(d))) as String
    fun validateNoDuplicateUniforms(shader: String) { query(JSONObject().put("op", "validate").put("shader", shader)) }
    fun uniformValues(d: TransitionDefinition, supplemental: Collection<String>, parameters: Map<String, Float>): Map<String, Float> =
        floats(query(JSONObject().put("op", "values").put("definition", definition(d)).put("supplemental", JSONArray(supplemental)).put("parameters", JSONObject(parameters))) as JSONObject)
    private fun floats(json: JSONObject) = json.keys().asSequence().associateWith { json.getDouble(it).toFloat() }
}
