package com.termex.replay15.editor.animation

import android.content.Context
import com.termex.replay15.editor.domain.*
import org.json.JSONObject
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AnimationRepository(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "editor-animations").apply { mkdirs() }
    private val preferences = app.getSharedPreferences("editor-animation-library", Context.MODE_PRIVATE)
    fun list(): List<AnimationPreset> {
        val builtIn = app.assets.list("editor/animations").orEmpty().map { name ->
            app.assets.open("editor/animations/$name").use { parse(readBounded(it)) }
        }
        val imported = directory.listFiles().orEmpty().filter { it.extension == "json" }.take(256).mapNotNull { file ->
            runCatching { file.inputStream().use { parse(readBounded(it)) } }.getOrNull()
        }
        return (builtIn + imported).sortedWith(compareByDescending<AnimationPreset> { favorite(it.id) }.thenBy { it.name })
    }
    fun install(input: InputStream): AnimationPreset {
        val text = readBounded(input); val definition = parse(text)
        require(!definition.id.startsWith("recly_")) { "Identificador reservado" }
        require(directory.listFiles().orEmpty().count { it.extension == "json" } < 256) { "Limite de 256 animacoes importadas" }
        val target = File(directory, "${definition.id}-${definition.version}.json")
        require(!target.exists()) { "Esta versao da animacao ja esta instalada" }
        val temporary = File.createTempFile("animation-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
        return definition
    }
    fun favorite(id: String) = id in preferences.getStringSet("favorites", emptySet()).orEmpty()
    fun toggleFavorite(id: String) {
        val items = preferences.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        if (!items.add(id)) items.remove(id)
        preferences.edit().putStringSet("favorites", items).apply()
    }
    fun used(id: String) { preferences.edit().putString("recent", (listOf(id) + recent()).distinct().take(20).joinToString(",")).apply() }
    fun recent() = preferences.getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() }
    companion object {
        private fun readBounded(input: InputStream): String {
            val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 65_536) { "Pacote de animacao muito grande" }; output.write(buffer, 0, count) }
            return output.toString("UTF-8")
        }
        fun parse(text: String): AnimationPreset {
            require(text.length <= 65_536)
            val json = JSONObject(text)
            require(json.getString("type") == "ANIMATION" && json.getInt("engineVersion") == 1)
            require(!json.optBoolean("premium", false)) { "Acesso premium nao configurado" }
            val keys = json.getJSONArray("points"); require(keys.length() in 2..32)
            val points = List(keys.length()) { index ->
                val key = keys.getJSONObject(index)
                val b = key.optJSONArray("bezier")
                if (b != null) require(b.length() == 4)
                AnimationPoint(key.getDouble("time").toFloat(), key.optDouble("zoom", 1.0).toFloat(),
                    key.optDouble("x", 0.0).toFloat(), key.optDouble("y", 0.0).toFloat(), key.optDouble("rotation", 0.0).toFloat(),
                    key.optDouble("opacity", 1.0).toFloat(), Easing.valueOf(key.optString("easing", "SMOOTH")),
                    if (b == null) CubicBezier() else CubicBezier(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()))
            }
            return AnimationPreset(json.getString("id"), json.getInt("version"), json.getString("name"),
                AnimationCategory.valueOf(json.getString("category")), points, json.getString("license"))
        }
    }
}
