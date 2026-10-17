package com.termex.replay15.editor.scene3d

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.Scene3DClip
import com.termex.replay15.editor.domain.VideoClip
import com.termex.replay15.editor.domain.newId
import java.io.File
import java.util.zip.ZipFile

/**
 * Imports 3D models and environment images into app storage (like LUTs).
 *
 * A model can be picked as one file (.glb, .gltf, .fbx, .obj), as several files at once (the
 * model plus its .bin/.mtl/textures) or as a .zip containing them. Everything is converted to
 * one self-contained .glb and loaded once with Filament, so a file that cannot be rendered is
 * refused here instead of failing later in the preview or the export. Call off the main thread.
 */
object Scene3DImport {
    private const val MAX_ENVIRONMENT_BYTES = 64L * 1024 * 1024
    private const val MAX_BUNDLE_BYTES = 600L * 1024 * 1024
    const val DEFAULT_DURATION_US = 5_000_000L
    /** Length of the automatic background when a project has no video yet. */
    const val BACKGROUND_DURATION_US = 10_000_000L

    class ImportedModel(val file: File, val name: String, val warnings: List<String>)

    fun model(context: Context, uris: List<Uri>): ImportedModel {
        require(uris.isNotEmpty()) { "Nenhum arquivo escolhido" }
        val names = uris.map { displayName(context, it) ?: it.lastPathSegment?.substringAfterLast('/') ?: "arquivo" }
        val work = File(context.cacheDir, "model-import-${newId()}").apply { check(mkdirs()) }
        try {
            val files = LinkedHashMap<String, () -> ByteArray>()
            var zip: ZipFile? = null
            try {
                if (uris.size == 1 && names[0].lowercase().endsWith(".zip")) {
                    val archive = File(work, "model.zip")
                    copy(context, uris[0], archive, MAX_BUNDLE_BYTES)
                    val opened = ZipFile(archive).also { zip = it }
                    var total = 0L
                    opened.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                        total += entry.size.coerceAtLeast(0L)
                        require(total <= MAX_BUNDLE_BYTES) { "O .zip descompactado passa de 600 MB" }
                        files[entry.name] = { opened.getInputStream(entry).use { it.readBytes() } }
                    }
                } else {
                    uris.forEachIndexed { index, uri ->
                        val target = File(work, "$index")
                        copy(context, uri, target, MAX_BUNDLE_BYTES)
                        files[names[index]] = { target.readBytes() }
                    }
                }
                val mainName = ModelConversion.pickMain(files.keys)
                    ?: throw IllegalArgumentException("Nenhum modelo .glb, .gltf, .fbx ou .obj entre os arquivos")
                val main = files.getValue(mainName)()
                require(main.size.toLong() <= ModelFileCheck.MAX_BYTES) { "Modelo maior que 250 MB" }
                val converted = ModelConversion.convert(mainName, main, ModelBundle(files))
                val baseName = mainName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Modelo 3D" }
                val directory = File(context.filesDir, "editor-models").apply { check(exists() || mkdirs()) }
                val output = File(directory, "${newId()}_${safe(baseName)}.glb")
                try {
                    output.writeBytes(converted.glb)
                    Scene3DRenderer().use { it.inspect(output.absolutePath) }
                } catch (failure: Throwable) {
                    output.delete(); throw failure
                }
                return ImportedModel(output, baseName.take(120), converted.warnings)
            } finally {
                zip?.close()
            }
        } finally {
            work.deleteRecursively()
        }
    }

    fun environment(context: Context, uri: Uri): File {
        val name = displayName(context, uri) ?: "ambiente.hdr"
        val lower = name.lowercase()
        require(listOf(".hdr", ".jpg", ".jpeg", ".png").any(lower::endsWith)) { "Escolha uma imagem .hdr, .jpg ou .png (panorama 2:1)" }
        val directory = File(context.filesDir, "editor-environments").apply { check(exists() || mkdirs()) }
        val file = File(directory, "${newId()}_${safe(name)}")
        try {
            copy(context, uri, file, MAX_ENVIRONMENT_BYTES)
            Scene3DRenderer().use { it.inspectEnvironment(file.absolutePath) }
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    /**
     * A plain background so a project can be only 3D: a solid image clip (9:16, 1080x1920) in
     * the project's background colour, stored with the project's other imported files.
     */
    fun backgroundClip(context: Context, color: Int): VideoClip {
        val directory = File(context.filesDir, "editor-backgrounds").apply { check(exists() || mkdirs()) }
        val file = File(directory, "fundo_${Integer.toHexString(color)}_1080x1920.png")
        if (!file.isFile || file.length() == 0L) {
            val temporary = File(directory, "${newId()}.tmp")
            val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                check(temporary.renameTo(file) || file.isFile)
            } finally { bitmap.recycle(); temporary.delete() }
        }
        return VideoClip(uri = Uri.fromFile(file).toString(), name = "Fundo 3D", sourceUs = BACKGROUND_DURATION_US,
            width = 1080, height = 1920, image = true)
    }

    /** A new layer at [positionUs], 5 s long or up to the end of the project. */
    fun newScene(project: Project, model: ImportedModel, positionUs: Long): Scene3DClip {
        val duration = project.durationUs
        require(duration >= Scene3DClip.MIN_SCENE_DURATION_US) { "Adicione um vídeo ou foto antes da cena 3D" }
        var start = positionUs.coerceIn(0L, duration)
        if (duration - start < Scene3DClip.MIN_SCENE_DURATION_US) start = (duration - DEFAULT_DURATION_US).coerceAtLeast(0L)
        // A project made only for 3D gets the scene over its whole background.
        val length = if (project.videos.size == 1 && isBackground(project.videos[0]) && start == 0L) duration else DEFAULT_DURATION_US
        val end = minOf(duration, start + length)
        return Scene3DClip(id = newId(), name = model.name, modelPath = model.file.absolutePath, startUs = start, endUs = end)
    }

    private fun safe(name: String) = name.trim().replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(90).ifBlank { "arquivo" }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun copy(context: Context, uri: Uri, file: File, limit: Long) {
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
    }

    /** True for the automatic background made by [backgroundClip]. */
    fun isBackground(clip: VideoClip): Boolean = clip.image && clip.uri.contains("/editor-backgrounds/fundo_")
}
