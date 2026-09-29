package com.termex.replay15.editor.assets

import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object AssetValidator {
    fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32_768)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun verify(file: File, bytes: Long, sha: String) {
        require(bytes in 1..MAX_PACKAGE && file.length() == bytes) { "Tamanho do pacote incorreto" }
        require(sha.matches(Regex("[a-fA-F0-9]{64}")) && checksum(file).equals(sha, true)) { "Checksum do pacote incorreto" }
    }
    fun extract(file: File, destination: File) {
        require(file.length() in 1..MAX_PACKAGE)
        require(destination.isDirectory && destination.listFiles()?.isEmpty() == true)
        val accepted = setOf("manifest.json", "shader.frag", "preview.webp")
        val seen = mutableSetOf<String>(); var total = 0L
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in accepted && seen.add(entry.name)) { "Arquivo inesperado no pacote" }
                val target = File(destination, entry.name)
                require(target.canonicalFile.parentFile == destination.canonicalFile)
                var fileBytes = 0
                target.outputStream().use { output -> val buffer = ByteArray(16_384)
                    while (true) { val n = zip.read(buffer); if (n < 0) break
                        total += n; fileBytes += n
                        require(total <= MAX_EXPANDED && fileBytes <= if (entry.name == "preview.webp") 2_000_000 else 65_536) { "Pacote excede o limite de expansao" }
                        output.write(buffer, 0, n)
                    }
                }
            }
        }
        require(seen.containsAll(listOf("manifest.json", "shader.frag"))) { "Pacote incompleto" }
    }
    const val MAX_PACKAGE = 4_000_000L
    const val MAX_EXPANDED = 2_200_000L
}
