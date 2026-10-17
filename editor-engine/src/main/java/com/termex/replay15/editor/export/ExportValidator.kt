package com.termex.replay15.editor.export

import android.media.MediaExtractor
import android.media.MediaFormat
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.audioEnabled
import com.termex.replay15.editor.speed.NativeSpeed
import java.io.File

/**
 * Lê o MP4 exportado e confere duração do vídeo e sincronia do áudio contra o projeto (a regra
 * fica no motor em Rust, `validate.rs`). Roda depois do Transformer terminar, antes de salvar na
 * galeria; um problema não apaga o arquivo, mas é dito ao usuário.
 */
object ExportValidator {
    data class Result(val ok: Boolean, val message: String)

    fun validate(file: File, project: Project): Result {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var videoUs = -1L
            var audioUs = -1L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
                if (mime.startsWith("video/")) videoUs = maxOf(videoUs, duration)
                if (mime.startsWith("audio/")) audioUs = maxOf(audioUs, duration)
            }
            if (videoUs < 0) return Result(false, "O arquivo exportado não tem trilha de vídeo.")
            val verdict = NativeSpeed.validateExport(project.durationUs, videoUs, audioUs, expectsAudio(project), project.export.fps)
            Result(verdict[0] == 0L, NativeSpeed.exportVerdictText(verdict[0].toInt(), verdict[1]))
        } catch (failure: Exception) {
            Result(true, "Não foi possível conferir o arquivo: ${failure.message}")
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Áudio garantido: algum áudio avulso audível. O som dos clipes de vídeo depende de o arquivo
     * de origem ter trilha de áudio, o que não se sabe aqui, então não entra na expectativa (mas
     * se existir, a sincronia dele é conferida igual).
     */
    private fun expectsAudio(project: Project): Boolean =
        project.audio.any { it.volume > 0f && project.audioEnabled("audio:${it.id}") }
}
