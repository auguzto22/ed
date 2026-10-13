package com.termex.replay15.editor.highlights

import com.termex.replay15.editor.domain.CanvasFill
import com.termex.replay15.editor.domain.MIN_TEXT_DURATION_US
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.domain.newId
import com.termex.replay15.editor.reframe.ReframeAspect

/**
 * Fase 3: transforma um melhor momento num **projeto novo e editável** (nada de MP4 intermediário).
 *
 * Usa as operações que o editor já tem: [Project.splitAt] corta a sequência principal nas bordas do
 * momento (respeitando `ClipTimeMap`, velocidade e keyframes), e só os clipes dentro do trecho ficam.
 * As legendas e textos do trecho vão junto, deslocados para o novo início. O resultado sai em 9:16 com
 * preenchimento (corte central); o reenquadramento por rosto é aplicado depois, pelo Auto Reframe.
 *
 * Fica de fora, de propósito: trilhas de vídeo sobrepostas, áudio extra, stickers, ajustes, marcadores
 * e grupos (compounds). Eles dependem do tempo do projeto original e ficariam fora de sincronia.
 */
object HighlightProjectBuilder {

    data class Result(val project: Project, val sourceStartUs: Long, val sourceEndUs: Long)

    fun build(
        source: Project,
        startUs: Long,
        endUs: Long,
        name: String,
        nowMs: Long,
        aspect: ReframeAspect = ReframeAspect.VERTICAL_9_16,
    ): Result {
        require(endUs > startUs && startUs >= 0L) { "Trecho inválido" }
        require(source.videos.isNotEmpty()) { "O projeto não tem vídeo na sequência principal" }
        require(aspect != ReframeAspect.ORIGINAL)

        // Corta primeiro no fim, depois no início: o corte do fim não muda a posição do início.
        val split = source.splitAt(endUs).splitAt(startUs)

        // Um split recusado (muito perto da borda do clipe) deixa um pedaço curto; o ponto médio de
        // cada clipe decide se ele pertence ao momento.
        val kept = split.videos.indices.filter { i ->
            val clipStart = split.startOf(i)
            val middle = clipStart + split.videos[i].durationUs / 2
            middle in startUs until endUs
        }.ifEmpty { listOf(split.indexAt(startUs)) }

        val keptStart = split.startOf(kept.first())
        val keptEnd = split.startOf(kept.last()) + split.videos[kept.last()].durationUs
        val videos = kept.map { split.videos[it] }
        val keptIds = videos.map { it.id }.toSet()

        val texts = split.texts.mapNotNull { shiftText(it, keptStart, keptEnd) }

        val project = Project(
            id = newId(),
            name = name.trim().ifBlank { "Melhor momento" }.take(200),
            videos = videos,
            texts = texts,
            aspect = aspect.ratio,
            export = source.export,
            backgroundColor = source.backgroundColor,
            canvasFill = CanvasFill.FILL,
            transitions = split.transitions.filter { it.leftClipId in keptIds && it.rightClipId in keptIds },
            createdAt = nowMs,
            modifiedAt = nowMs,
            camera = source.camera,
            captionGlobalFontId = source.captionGlobalFontId,
            captionLanguage = source.captionLanguage,
            captionVocabulary = source.captionVocabulary,
        )
        return Result(project, keptStart, keptEnd)
    }

    /** Recorta o texto ao trecho [rangeStart, rangeEnd) e o desloca para começar em zero. */
    internal fun shiftText(text: TextClip, rangeStart: Long, rangeEnd: Long): TextClip? {
        val start = maxOf(text.startUs, rangeStart)
        val end = minOf(text.endUs, rangeEnd)
        if (end - start < MIN_TEXT_DURATION_US) return null
        val newStart = start - rangeStart
        val newEnd = end - rangeStart
        val cues = text.wordCues
            .filter { it.startUs >= start && it.endUs <= end }
            .map { it.copy(startUs = it.startUs - rangeStart, endUs = it.endUs - rangeStart) }
        return text.copy(
            id = newId(),
            startUs = newStart,
            endUs = newEnd,
            wordCues = cues,
            // Uma máscara animada depende da duração original; só é mantida se o texto não foi cortado.
            mask = text.mask.takeIf { start == text.startUs && end == text.endUs },
        )
    }
}
