package com.termex.replay15.editor.captions

import android.graphics.Color
import com.termex.replay15.editor.domain.SubtitleStylePatch
import com.termex.replay15.editor.domain.SubtitleWordCue
import com.termex.replay15.editor.domain.SubtitleWordStyle
import com.termex.replay15.editor.domain.TextAnimation
import com.termex.replay15.editor.domain.TextClip

/**
 * Legendas animadas no estilo dos apps de vídeo curto: conforme a pessoa fala, a palavra
 * muda de cor, ganha uma caixa, pula ou aparece. Cada estilo é só um [SubtitleStylePatch],
 * então vale tanto ao gerar legendas quanto no painel de legenda (com "Esta / Todas" etc.),
 * e preview e exportação usam o mesmo desenho ([com.termex.replay15.editor.render.TextLayout]).
 */
data class AnimatedCaptionStyle(
    val id: String,
    val name: String,
    val fontId: String,
    val color: Int,
    val outlineColor: Int,
    val outlineWidth: Float,
    val backgroundColor: Int,
    val shadow: Boolean,
    val wordStyle: SubtitleWordStyle,
    val bold: Boolean = true,
) {
    fun patch(): SubtitleStylePatch = SubtitleStylePatch(
        fontId = fontId,
        color = color,
        bold = bold,
        outlineColor = outlineColor,
        outlineWidth = outlineWidth,
        backgroundColor = backgroundColor,
        shadow = shadow,
        wordStyle = wordStyle,
        // As animações "por palavra" só marcam o modo; quem desenha é o wordStyle.
        duringAnimation = if (wordStyle.animated) TextAnimation.KARAOKE else TextAnimation.NONE,
        animation = TextAnimation.NONE,
    )
}

object AnimatedCaptionStyles {
    private const val WHITE = Color.WHITE
    private const val BLACK = Color.BLACK
    private const val YELLOW = 0xFFFFD43B.toInt()
    private const val GREEN = 0xFF2BE07A.toInt()
    private const val RED = 0xFFC50022.toInt()
    private const val CYAN = 0xFF38E1FF.toInt()
    private const val PINK = 0xFFFF4FA3.toInt()
    private const val ORANGE = 0xFFFF8A1F.toInt()
    private const val DIM = 0x8CFFFFFF.toInt()

    val all: List<AnimatedCaptionStyle> = listOf(
        AnimatedCaptionStyle("none", "Simples", "system_sans_black", WHITE, BLACK, .012f, 0, true,
            SubtitleWordStyle()),
        AnimatedCaptionStyle("karaoke", "Karaokê", "system_sans_black", WHITE, BLACK, .014f, 0, true,
            SubtitleWordStyle(spokenColor = YELLOW, activeColor = YELLOW, boldCurrentWord = true)),
        AnimatedCaptionStyle("destaque", "Destaque", "anton_regular", WHITE, BLACK, .016f, 0, true,
            SubtitleWordStyle(activeColor = GREEN, activeScale = 1.12f, pop = true, uppercase = true)),
        AnimatedCaptionStyle("caixa", "Caixa", "system_sans_black", WHITE, BLACK, 0f, 0, false,
            SubtitleWordStyle(highlightColor = RED, activeColor = WHITE, pop = true, activeScale = 1.06f, uppercase = true)),
        AnimatedCaptionStyle("amarelo", "Amarelo pop", "anton_regular", WHITE, BLACK, .016f, 0, true,
            SubtitleWordStyle(activeColor = YELLOW, activeScale = 1.2f, pop = true, uppercase = true)),
        AnimatedCaptionStyle("arco_iris", "Arco-íris", "system_sans_black", WHITE, BLACK, .014f, 0, true,
            SubtitleWordStyle(activePalette = listOf(PINK, CYAN, YELLOW, GREEN), activeScale = 1.1f, pop = true)),
        AnimatedCaptionStyle("revelar", "Palavra a palavra", "montserrat_semibold", WHITE, BLACK, .014f, 0, true,
            SubtitleWordStyle(reveal = true, activeColor = YELLOW, pop = true, activeScale = 1.08f)),
        AnimatedCaptionStyle("neon", "Neon", "outfit_regular", WHITE, 0xFF0A2A3A.toInt(), .012f, 0, true,
            SubtitleWordStyle(activeColor = CYAN, futureColor = DIM, activeScale = 1.08f, pop = true)),
        AnimatedCaptionStyle("fogo", "Fogo", "bebas_neue_regular", WHITE, BLACK, .016f, 0, true,
            SubtitleWordStyle(activeColor = ORANGE, spokenColor = 0xFFFFE3C2.toInt(), activeScale = 1.15f, pop = true, uppercase = true)),
        AnimatedCaptionStyle("minimal", "Minimal", "poppins_semibold", WHITE, BLACK, 0f, 0, true,
            SubtitleWordStyle(futureColor = DIM, spokenColor = DIM, activeColor = WHITE)),
        AnimatedCaptionStyle("gaming", "Gaming", "space_mono_regular", WHITE, BLACK, .014f, 0, true,
            SubtitleWordStyle(activeColor = GREEN, activeScale = 1.12f, pop = true, uppercase = true)),
        AnimatedCaptionStyle("bolha", "Bolha", "poppins_semibold", WHITE, 0, 0f, 0xB3000000.toInt(), false,
            SubtitleWordStyle(highlightColor = YELLOW, activeColor = BLACK, pop = true, activeScale = 1.05f)),
    )

    fun byId(id: String): AnimatedCaptionStyle? = all.firstOrNull { it.id == id }

    /**
     * Garante tempo por palavra: legendas sem timestamps (SRT importado, texto digitado) ganham
     * uma divisão estimada pelo tamanho de cada palavra, para os efeitos funcionarem também nelas.
     */
    fun withWordTiming(clip: TextClip): TextClip {
        val words = Regex("\\S+").findAll(clip.text).map { it.value }.toList()
        // Tempo medido na transcrição vale mais que qualquer estimativa, mesmo se faltar alguma palavra.
        if (words.isEmpty() || clip.wordCues.isNotEmpty()) return clip
        val weights = words.map { it.length.coerceAtLeast(2) + 1 }
        val total = weights.sum().toFloat()
        var cursor = clip.startUs
        val cues = words.mapIndexed { index, word ->
            val end = if (index == words.lastIndex) clip.endUs
            else (cursor + clip.durationUs * (weights[index] / total)).toLong().coerceIn(cursor + 1, clip.endUs - (words.size - index - 1))
            SubtitleWordCue(word.take(120), cursor, end).also { cursor = end }
        }
        return if (cues.size <= 300) clip.copy(wordCues = cues) else clip
    }
}
