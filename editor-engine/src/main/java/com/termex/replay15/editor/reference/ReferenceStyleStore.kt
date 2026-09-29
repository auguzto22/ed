package com.termex.replay15.editor.reference

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Atomic, version-aware profile/cache persistence. Original media is never copied. */
class ReferenceStyleStore(private val directory: File) {
    init { require(directory.exists() || directory.mkdirs()) { "Nao foi possivel criar armazenamento de estilos" } }

    fun list(): List<ReferenceStyleProfile> = directory.listFiles { file -> file.extension == "json" && !file.name.endsWith(".tmp.json") }
        .orEmpty().mapNotNull { runCatching { read(it) }.getOrNull() }.sortedByDescending { it.createdAtMs }

    fun findValid(fingerprint: String): ReferenceStyleProfile? = list().firstOrNull {
        it.sourceVideoFingerprint == fingerprint && it.analysisVersion == REFERENCE_ANALYSIS_VERSION
    }

    fun load(id: String): ReferenceStyleProfile {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        return read(File(directory, "$id.json"))
    }

    fun save(profile: ReferenceStyleProfile) {
        val target = File(directory, "${profile.id}.json")
        val temp = File(directory, "${profile.id}.tmp.json")
        try {
            FileOutputStream(temp).use { output ->
                output.write(profile.toJson().toString().toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun rename(id: String, name: String): ReferenceStyleProfile {
        require(name.isNotBlank() && name.length <= 100)
        return load(id).copy(name = name.trim()).also(::save)
    }

    private fun read(file: File): ReferenceStyleProfile {
        require(file.isFile && file.length() in 1..8_000_000) { "Perfil invalido" }
        return ReferenceStyleProfile.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
    }
}
