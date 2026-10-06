package com.termex.replay15.editor.packs

/**
 * Status do ciclo de vida de um Resource Pack.
 */
enum class PackStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLED,
    UPDATE_AVAILABLE,
}

/**
 * Metadados completos de um Resource Pack para download e modularização de assets.
 */
data class ResourcePack(
    val id: String,
    val name: String,
    val description: String,
    val version: Int,
    val sizeBytes: Long,
    val categories: List<String>,
    val license: String = "MIT / SIL OFL 1.1",
    val licenseUrl: String = "https://opensource.org/licenses",
    val author: String = "Recly Community",
    val resourceIds: List<String> = emptyList(),
    val isBaseCore: Boolean = false,
    var status: PackStatus = if (isBaseCore) PackStatus.INSTALLED else PackStatus.NOT_INSTALLED,
    var downloadProgress: Float = if (isBaseCore) 1f else 0f,
) {
    val formattedSize: String
        get() {
            val mb = sizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1.0) String.format("%.1f MB", mb) else "${sizeBytes / 1024} KB"
        }
}

