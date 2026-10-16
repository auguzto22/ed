package com.termex.replay15.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.VideoClip
import java.io.File
import java.io.FileOutputStream

/**
 * Miniatura de verdade dos projetos na tela Início: um quadro do primeiro clipe da timeline (no
 * ponto de entrada dele, já com o corte aplicado), e não mais um bloco de cor.
 *
 * Fica em cache na memória e em disco (filesDir/editor-thumbs/<id>_<modifiedAt>.jpg). Quando o
 * projeto é salvo de novo o modifiedAt muda, então a miniatura é refeita e a antiga é apagada.
 * Tudo aqui roda fora da thread principal.
 */
class ProjectThumbnails(context: Context) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "editor-thumbs").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Largura alvo da miniatura: suficiente para o cartão de meia tela sem pesar. */
    private val targetWidth = 480

    fun cached(project: Project): Bitmap? = memory.get(key(project))

    /** Bloqueante: chame numa thread de fundo. Null quando o vídeo não está mais acessível. */
    fun load(project: Project): Bitmap? {
        val key = key(project)
        memory.get(key)?.let { return it }
        val file = File(dir, "$key.jpg")
        if (file.isFile) {
            BitmapFactory.decodeFile(file.path)?.let { memory.put(key, it); return it }
        }
        val clip = project.videos.firstOrNull() ?: return null
        val bitmap = runCatching { frameOf(clip) }.getOrNull() ?: return null
        memory.put(key, bitmap)
        runCatching {
            // Apaga miniaturas antigas deste projeto antes de gravar a nova.
            dir.listFiles { f -> f.name.startsWith(project.id + "_") }?.forEach { it.delete() }
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }
        return bitmap
    }

    fun forget(projectId: String) {
        dir.listFiles { f -> f.name.startsWith(projectId + "_") }?.forEach { it.delete() }
        memory.snapshot().keys.filter { it.startsWith(projectId + "_") }.forEach { memory.remove(it) }
    }

    /** Espaço ocupado pelas miniaturas em disco (para a tela de Config.). */
    fun diskBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun key(project: Project) = "${project.id}_${project.modifiedAt}"

    private fun frameOf(clip: VideoClip): Bitmap? {
        // Tenta o arquivo original e cai para o proxy/derivado local se a permissão tiver caído.
        val candidates = listOfNotNull(clip.uri, clip.derivedUri, clip.proxyUri).distinct()
        for (raw in candidates) {
            val uri = Uri.parse(raw)
            val frame = runCatching { if (clip.image) imageOf(uri) else videoFrameOf(uri, clip) }.getOrNull()
            if (frame != null) return orient(frame, clip)
        }
        return null
    }

    private fun videoFrameOf(uri: Uri, clip: VideoClip): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == null || uri.scheme == "file") retriever.setDataSource(uri.path ?: return null)
            else retriever.setDataSource(app, uri)
            // Um pouco depois do ponto de entrada: o primeiro quadro costuma ser preto ou um fade.
            val span = (clip.outUs - clip.inUs).coerceAtLeast(0L)
            val atUs = clip.inUs + minOf(span / 4, 600_000L)
            val srcW = clip.width.coerceAtLeast(1)
            val srcH = clip.height.coerceAtLeast(1)
            val w = minOf(targetWidth, srcW)
            val h = (w.toLong() * srcH / srcW).toInt().coerceAtLeast(1)
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
            } else null
            return frame ?: retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::scaleDown)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun imageOf(uri: Uri): Bitmap? {
        val source = if (uri.scheme == null || uri.scheme == "file") ImageDecoder.createSource(File(uri.path ?: return null))
        else ImageDecoder.createSource(app.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width.coerceAtLeast(1)
            if (w > targetWidth) decoder.setTargetSize(targetWidth, (info.size.height.toLong() * targetWidth / w).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= targetWidth) return bitmap
        val h = (bitmap.height.toLong() * targetWidth / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, h, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Aplica a rotação e o espelhamento que o clipe tem na timeline, como na prévia. */
    private fun orient(bitmap: Bitmap, clip: VideoClip): Bitmap {
        val rotation = ((clip.rotation % 360) + 360) % 360
        if (rotation == 0 && !clip.flip) return bitmap
        val matrix = Matrix().apply {
            if (clip.flip) postScale(-1f, 1f)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }
}
