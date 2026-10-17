package com.termex.replay15.editor.scene3d

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.Scene3DClip
import com.termex.replay15.editor.domain.newId
import java.io.File

/**
 * Copies picked 3D models and environment images into app storage (like LUTs), checks them,
 * and loads them once with Filament so a file that cannot be rendered is refused at import
 * instead of failing later in the preview or the export. Call off the main thread.
 */
object Scene3DImport {
    private const val MAX_ENVIRONMENT_BYTES = 64L * 1024 * 1024
    const val DEFAULT_DURATION_US = 5_000_000L

    class ImportedModel(val file: File, val name: String)

    fun model(context: Context, uri: Uri): ImportedModel {
        val name = displayName(context, uri) ?: "modelo.glb"
        val file = copy(context, uri, "editor-models", name, ModelFileCheck.MAX_BYTES)
        try {
            val head = file.inputStream().use { input -> ByteArray(12).also { var read = 0; while (read < 12) { val n = input.read(it, read, 12 - read); if (n < 0) break; read += n } } }
            when (val result = ModelFileCheck.check(name, file.length(), head) { file.readText(Charsets.UTF_8) }) {
                is ModelFileCheck.Result.Rejected -> throw IllegalArgumentException(result.reason)
                else -> Unit
            }
            Scene3DRenderer().use { it.inspect(file.absolutePath) }
            return ImportedModel(file, name.substringBeforeLast('.').ifBlank { "Modelo 3D" }.take(120))
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    fun environment(context: Context, uri: Uri): File {
        val name = displayName(context, uri) ?: "ambiente.hdr"
        val lower = name.lowercase()
        require(listOf(".hdr", ".jpg", ".jpeg", ".png").any(lower::endsWith)) { "Escolha uma imagem .hdr, .jpg ou .png (panorama 2:1)" }
        val file = copy(context, uri, "editor-environments", name, MAX_ENVIRONMENT_BYTES)
        try {
            Scene3DRenderer().use { it.inspectEnvironment(file.absolutePath) }
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    /** A new layer at [positionUs], 5 s long or up to the end of the project. */
    fun newScene(project: Project, model: ImportedModel, positionUs: Long): Scene3DClip {
        val duration = project.durationUs
        require(duration >= Scene3DClip.MIN_SCENE_DURATION_US) { "Adicione um vídeo ou foto antes da cena 3D" }
        var start = positionUs.coerceIn(0L, duration)
        if (duration - start < Scene3DClip.MIN_SCENE_DURATION_US) start = (duration - DEFAULT_DURATION_US).coerceAtLeast(0L)
        val end = minOf(duration, start + DEFAULT_DURATION_US)
        return Scene3DClip(id = newId(), name = model.name, modelPath = model.file.absolutePath, startUs = start, endUs = end)
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun copy(context: Context, uri: Uri, folder: String, name: String, limit: Long): File {
        val directory = File(context.filesDir, folder).apply { check(exists() || mkdirs()) }
        val safe = name.trim().replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(90).ifBlank { "arquivo" }
        val file = File(directory, "${newId()}_$safe")
        try {
            context.contentResolver.openInputStream(uri)?.use { source ->
                file.outputStream().use { dest ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= limit) { "Arquivo maior que ${limit / (1024 * 1024)} MB" }
                        dest.write(buffer, 0, n)
                    }
                    require(total > 0L) { "Arquivo vazio" }
                }
            } ?: throw IllegalArgumentException("Arquivo indisponível")
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }
}
