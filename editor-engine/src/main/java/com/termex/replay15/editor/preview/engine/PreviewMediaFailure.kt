package com.termex.replay15.editor.preview.engine

import android.net.Uri

sealed interface PreviewMediaFailure {
    val clipId: String?
    val uri: Uri?
    val sourceGeneration: Long
    val cause: Throwable?

    data class MissingSource(
        override val clipId: String?, override val uri: Uri, override val sourceGeneration: Long,
        override val cause: Throwable? = null,
    ) : PreviewMediaFailure

    data class PermissionLost(
        override val clipId: String?, override val uri: Uri, override val sourceGeneration: Long,
        override val cause: Throwable? = null,
    ) : PreviewMediaFailure

    data class UnsupportedFormat(
        override val clipId: String?, override val uri: Uri?, val mime: String?, override val sourceGeneration: Long,
        override val cause: Throwable? = null,
    ) : PreviewMediaFailure

    data class DecoderFailure(
        override val clipId: String?, override val uri: Uri?, val codecName: String?, override val sourceGeneration: Long,
        override val cause: Throwable,
    ) : PreviewMediaFailure

    data class SurfaceFailure(
        override val clipId: String?, override val uri: Uri?, override val sourceGeneration: Long,
        override val cause: Throwable,
    ) : PreviewMediaFailure

    data class SeekFailure(
        override val clipId: String?, override val uri: Uri?, val targetUs: Long, override val sourceGeneration: Long,
        override val cause: Throwable,
    ) : PreviewMediaFailure

    data class SourceReadFailure(
        override val clipId: String?, override val uri: Uri, override val sourceGeneration: Long,
        override val cause: Throwable,
    ) : PreviewMediaFailure
}

fun PreviewMediaFailure.userMessage(): String = when (this) {
    is PreviewMediaFailure.MissingSource -> "Arquivo original não foi encontrado."
    is PreviewMediaFailure.PermissionLost -> "O Recly perdeu acesso a este arquivo. Selecione-o novamente."
    is PreviewMediaFailure.UnsupportedFormat -> "Este formato de vídeo não é suportado neste dispositivo."
    is PreviewMediaFailure.SurfaceFailure -> "A prévia perdeu a superfície de vídeo e está se recuperando."
    is PreviewMediaFailure.SeekFailure -> "Não foi possível buscar este ponto do vídeo. Tentando recuperar a prévia."
    is PreviewMediaFailure.DecoderFailure -> "O decodificador de vídeo falhou. Tentando recuperar a prévia."
    is PreviewMediaFailure.SourceReadFailure -> "Não foi possível ler este arquivo agora."
}
