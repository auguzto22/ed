package com.termex.replay15.editor.reference

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileInputStream
import java.security.MessageDigest

object ReferenceMediaProbe {
    private const val HASH_WINDOW = 64 * 1024

    fun probe(context: Context, uri: Uri): ReferenceMediaInfo {
        require(uri.scheme in setOf("content", "file")) { "Origem de video nao suportada" }
        var name = "Video de referencia"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it)?.take(180) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
        val modified = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                } ?: 0L
            } else 0L
        }.getOrDefault(0L)
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(context, uri)
            extractor.setDataSource(context, uri, null)
            fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
            val durationUs = number(MediaMetadataRetriever.METADATA_KEY_DURATION) * 1_000L
            val rotation = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt().let { ((it % 360) + 360) % 360 }
            val rawWidth = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            val rawHeight = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            val width = if (rotation % 180 == 0) rawWidth else rawHeight
            val height = if (rotation % 180 == 0) rawHeight else rawWidth
            require(durationUs > 0 && width > 0 && height > 0) { "Arquivo sem faixa de video valida" }
            val videoTrack = (0 until extractor.trackCount).map(extractor::getTrackFormat).firstOrNull {
                it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalArgumentException("Codec de video nao reconhecido")
            val fps = when {
                videoTrack.containsKey(MediaFormat.KEY_FRAME_RATE) -> videoTrack.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                else -> retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
            }.coerceIn(1f, 240f)
            val hasAudio = (0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val fingerprint = fingerprint(context, uri, size, durationUs, width, height, modified)
            return ReferenceMediaInfo(uri.toString(), name, durationUs, width, height, rotation, fps, hasAudio, size, modified, fingerprint)
        } finally {
            extractor.release(); retriever.release()
        }
    }

    private fun fingerprint(context: Context, uri: Uri, size: Long, durationUs: Long, width: Int, height: Int, modified: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$uri|$size|$durationUs|$width|$height|$modified".toByteArray())
        val hashed = runCatching {
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return@runCatching false
            descriptor.use {
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    if (descriptor.startOffset > 0) input.channel.position(descriptor.startOffset)
                    val first = ByteArray(HASH_WINDOW)
                    val count = input.read(first)
                    if (count > 0) digest.update(first, 0, count)
                    val length = descriptor.length.takeIf { it > 0 } ?: size
                    if (length > HASH_WINDOW) {
                        runCatching {
                            input.channel.position(descriptor.startOffset + (length - HASH_WINDOW).coerceAtLeast(0))
                            var read: Int
                            do { read = input.read(first); if (read > 0) digest.update(first, 0, read) } while (read > 0)
                        }
                    }
                }
            }
            true
        }.getOrDefault(false)
        if (!hashed) runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val first = ByteArray(HASH_WINDOW); val count = input.read(first)
                if (count > 0) digest.update(first, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
