package com.termex.replay15.editor.ui

import android.content.Intent
import com.termex.replay15.editor.autoedit.AutoEditOptions
import com.termex.replay15.editor.autoedit.AutoEditStyle

/**
 * Escolhas feitas na tela Auto Edit do Início, levadas até o editor pelo Intent.
 * O editor importa o vídeo, aplica o formato e roda o Auto Edit direto com estas opções
 * (sem abrir de novo a folha de opções).
 */
data class AutoEditLaunch(
    val style: AutoEditStyle,
    val format: Format,
    val cleanPauses: Boolean,
    val captions: Boolean,
    val smartZoom: Boolean,
    val audioEnhancement: Boolean,
) {
    /** Formato do resultado. [ratio] 0 = mantém a proporção do vídeo. */
    enum class Format(val label: String, val ratio: Float) {
        VERTICAL("9:16", 9f / 16f), SQUARE("1:1", 1f), WIDE("16:9", 16f / 9f), ORIGINAL("Original", 0f)
    }

    /** 9:16 usa o Auto Reframe do motor (segue o rosto/movimento); os outros só trocam a tela. */
    fun options(): AutoEditOptions = AutoEditOptions(
        style = style,
        captions = captions,
        cleanPauses = cleanPauses,
        smartZoom = smartZoom,
        autoReframe = format == Format.VERTICAL,
        audioEnhancement = audioEnhancement,
    )

    fun writeTo(intent: Intent): Intent = intent
        .putExtra(EXTRA_STYLE, style.name)
        .putExtra(EXTRA_FORMAT, format.name)
        .putExtra(EXTRA_CLEAN, cleanPauses)
        .putExtra(EXTRA_CAPTIONS, captions)
        .putExtra(EXTRA_ZOOM, smartZoom)
        .putExtra(EXTRA_AUDIO, audioEnhancement)

    companion object {
        private const val EXTRA_STYLE = "auto_edit_style"
        private const val EXTRA_FORMAT = "auto_edit_format"
        private const val EXTRA_CLEAN = "auto_edit_clean"
        private const val EXTRA_CAPTIONS = "auto_edit_captions"
        private const val EXTRA_ZOOM = "auto_edit_zoom"
        private const val EXTRA_AUDIO = "auto_edit_audio"

        /** null quando o editor foi aberto sem passar pela tela Auto Edit. */
        fun from(intent: Intent?): AutoEditLaunch? {
            val styleName = intent?.getStringExtra(EXTRA_STYLE) ?: return null
            val style = AutoEditStyle.entries.firstOrNull { it.name == styleName } ?: return null
            val format = Format.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_FORMAT) } ?: Format.VERTICAL
            return AutoEditLaunch(
                style = style,
                format = format,
                cleanPauses = intent.getBooleanExtra(EXTRA_CLEAN, true),
                captions = intent.getBooleanExtra(EXTRA_CAPTIONS, true),
                smartZoom = intent.getBooleanExtra(EXTRA_ZOOM, true),
                audioEnhancement = intent.getBooleanExtra(EXTRA_AUDIO, true),
            )
        }
    }
}
