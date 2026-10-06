package com.termex.replay15.editor.assets

import android.content.Context
import org.json.JSONArray

object BuiltInTransitions {
    @Volatile private var cached: List<TransitionDefinition>? = null

    fun definitions(context: Context): List<TransitionDefinition> {
        return cached ?: synchronized(this) {
            cached ?: run {
                val text = context.assets.open("editor/transitions.json").bufferedReader().use { it.readText() }
                val catalog = JSONArray(text)
                val list = List(catalog.length()) { TransitionDefinition.parse(catalog.getJSONObject(it).toString()) }
                list.forEach { item ->
                    com.termex.replay15.editor.licenses.ResourceCreditsRegistry.register(
                        com.termex.replay15.editor.licenses.ResourceLicenseMetadata(
                            id = item.id,
                            name = item.name,
                            author = "GL Transitions / Recly Community",
                            source = "GL Transitions (MIT)",
                            license = "MIT",
                            licenseUrl = "https://opensource.org/licenses/MIT",
                            sourceUrl = "https://gl-transitions.com",
                            version = "${item.version}.0",
                            category = "Transition",
                            tags = listOf(item.category),
                        )
                    )
                }
                list.also { cached = it }
            }
        }
    }

    fun findById(context: Context, id: String): TransitionDefinition? =
        definitions(context).firstOrNull { it.id == id }

    fun findById(id: String): TransitionDefinition? =
        cached?.firstOrNull { it.id == id }

    fun shader(context: Context, shaderFile: String): String =
        context.assets.open("editor/transitions/$shaderFile").bufferedReader().use { it.readText() }
}
