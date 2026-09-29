package com.termex.replay15.editor.media

import android.content.Context
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.termex.replay15.editor.domain.*
import java.io.File

object MediaImport {
    private fun name(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.take(180) ?: "Midia"

    fun video(context: Context, uri: Uri): VideoClip {
        val type = context.contentResolver.getType(uri).orEmpty()
        if (type.startsWith("image/")) {
            require(type != "image/gif") { "GIF animado ainda nao suportado. Escolha foto ou video." }
            var width = 0; var height = 0
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                width = info.size.width; height = info.size.height
                decoder.setTargetSize(1, 1)
            }.recycle()
            require(width > 0 && height > 0) { "Imagem nao suportada" }
            return VideoClip(uri = uri.toString(), name = name(context, uri), sourceUs = 5 * SECOND,
                width = width, height = height, image = true)
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0
            val duration = number(MediaMetadataRetriever.METADATA_KEY_DURATION) * 1000
            var w = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            var h = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            if (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0L) { val temp = w; w = h; h = temp }
            require(duration > 0 && w > 0 && h > 0) { "Arquivo sem video valido" }
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            return VideoClip(uri = uri.toString(), name = name(context, uri), sourceUs = duration,
                width = w, height = h, fps = fps.coerceIn(1f, 240f), mimeType = mime)
        } finally { retriever.release() }
    }

    fun audio(context: Context, uri: Uri, startUs: Long): AudioClip {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "Arquivo sem audio" }
            val duration = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000
            require(duration > 0) { "Duracao de audio invalida" }
            return AudioClip(uri = uri.toString(), name = name(context, uri), sourceUs = duration, startUs = startUs)
        } finally { retriever.release() }
    }

    fun sticker(context: Context, uri: Uri, startUs: Long, endUs: Long): StickerClip {
        val type = context.contentResolver.getType(uri).orEmpty()
        require(type.startsWith("image/") && type != "image/gif") { "Escolha uma imagem PNG, JPG ou WebP" }
        var valid = false
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            valid = info.size.width > 0 && info.size.height > 0
            decoder.setTargetSize(1, 1)
        }.recycle()
        require(valid) { "Imagem sobreposta invalida" }
        return StickerClip(uri = uri.toString(), name = name(context, uri), startUs = startUs, endUs = endUs)
    }

    fun sourceStatuses(context: Context, project: Project): Map<String, MediaSourceStatus> =
        (project.allVideos.map { it.uri } + project.audio.map { it.uri } + project.stickers.map { it.uri } +
            project.allVideos.mapNotNull { it.grade.lutPath.takeIf(String::isNotBlank)?.let { path -> Uri.fromFile(File(path)).toString() } })
            .distinct().associateWith { MediaSourceAccess.verify(context.contentResolver, Uri.parse(it)) }

    /** Compatibility helper: a temporary read/decoder failure is deliberately not "missing". */
    fun missing(context: Context, project: Project): List<String> = sourceStatuses(context, project)
        .filterValues { it is MediaSourceStatus.Missing }.keys.toList()
}
