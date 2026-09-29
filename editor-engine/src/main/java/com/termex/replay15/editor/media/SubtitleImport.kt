package com.termex.replay15.editor.media

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.termex.replay15.editor.domain.SECOND
import com.termex.replay15.editor.domain.TextClip
import java.nio.charset.Charset

object SubtitleImport {
    fun read(context: Context, uri: Uri, projectDurationUs: Long, limit: Int): List<TextClip> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= 2_000_000) { "Arquivo de legenda maior que 2 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("Nao foi possivel ler a legenda")
        return SubtitleDocument.parse(decode(bytes), projectDurationUs, limit)
    }

    private fun decode(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
        else -> String(bytes, Charsets.UTF_8)
    }
}
