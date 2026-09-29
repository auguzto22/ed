package com.termex.replay15.editor.assets

import android.content.Context
import com.recly.editor.engine.R
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class AssetRepository(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "editor-assets").apply { mkdirs() }
    private val preferences = app.getSharedPreferences("editor-asset-library", Context.MODE_PRIVATE)
    private val repositoryKey = app.filesDir.absolutePath
    private val builtIn by lazy { builtInCatalog.computeIfAbsent(repositoryKey) { BuiltInEffects.definitions(app) } }
    fun definitions(): List<EffectDefinition> = builtIn + directory.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.flatMap { versions ->
        versions.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { runCatching { EffectDefinition.parse(File(it, "manifest.json").readText()) }.getOrNull() }
    }
    fun resolve(id: String, version: Int): Pair<EffectDefinition, String> {
        require(id.matches(Regex("[a-z][a-z0-9_]{0,63}")) && version in 1..100000)
        builtIn.firstOrNull { it.id == id && it.version == version }?.let { definition ->
            val key = "$repositoryKey/builtin/$id/$version"
            return resolvedEffects.computeIfAbsent(key) { _ ->
                val shaderName = definition.shaderFile ?: if (definition.shader != "shader.frag") definition.shader.removeSuffix(".frag") else id
                definition to BuiltInEffects.shader(app, shaderName)
            }
        }
        val folder = File(directory, "$id/$version")
        val key = folder.absolutePath
        return resolvedEffects.computeIfAbsent(key) {
            val definition = EffectDefinition.parse(File(folder, "manifest.json").readText())
            require(definition.id == id && definition.version == version)
            definition to File(folder, "shader.frag").readText().also(::validateShader)
        }
    }
    fun install(packageFile: File, expected: OnlineAsset? = null): EffectDefinition {
        val staging = Files.createTempDirectory(directory.toPath(), ".install-").toFile()
        try {
            AssetValidator.extract(packageFile, staging)
            val definition = EffectDefinition.parse(File(staging, "manifest.json").readText())
            if (expected != null) require(definition.id == expected.id && definition.version == expected.version) { "Pacote diferente do catalogo" }
            require(!definition.id.startsWith("recly_")) { "Identificador reservado para efeitos internos" }
            validateShader(File(staging, "shader.frag").readText())
            require(!definition.premium) { "Este pacote exige uma licenca de acesso ainda nao configurada" }
            val parent = File(directory, definition.id).apply { mkdirs() }
            val target = File(parent, definition.version.toString())
            require(!target.exists()) { "Esta versao do pacote ja esta instalada" }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            resolvedEffects.remove(target.absolutePath)
            return definition
        } finally { staging.deleteRecursively() }
    }
    fun favorite(id: String): Boolean = preferences.getStringSet("favorites", emptySet()).orEmpty().contains(id)
    fun toggleFavorite(id: String) { val values = preferences.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        if (!values.add(id)) values.remove(id); preferences.edit().putStringSet("favorites", values).apply() }
    fun used(id: String) {
        val recent = (listOf(id) + recent()).distinct().take(20)
        preferences.edit().putString("recent", recent.joinToString(",")).apply()
    }
    fun recent() = preferences.getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() }
    fun downloadedBytes() = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    companion object {
        private val builtInCatalog = ConcurrentHashMap<String, List<EffectDefinition>>()
        private val resolvedEffects = ConcurrentHashMap<String, Pair<EffectDefinition, String>>()
        fun validateShader(source: String) {
            require(source.length in 1..65_536 && source.contains("vec4 reclyEffect(")) { "Shader do pacote incompativel" }
            require(!Regex("#|\\b(?:uniform|attribute|varying|while|for|do|discard|main)\\b").containsMatchIn(source)) { "Shader usa instrucoes fora do formato aceito" }
        }
    }
}
