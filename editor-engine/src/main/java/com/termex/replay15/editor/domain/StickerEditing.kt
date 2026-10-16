package com.termex.replay15.editor.domain

import com.termex.replay15.editor.transform.TransformKeyframe

/** Limite de camadas de imagem/sticker por projeto (o mesmo dos codecs). */
const val MAX_STICKERS = 24

/**
 * Edições de sticker da barra estilo CapCut (Dividir, Duplicar, Espelhar, Camadas).
 * Tudo puro: devolve um projeto novo, ou null quando a edição não se aplica.
 */
object StickerEditing {

    /**
     * Divide o sticker [id] em [atUs] (tempo da timeline). A primeira parte fica com a entrada,
     * a segunda com a saída, e as duas com o loop. Keyframes e máscara são cortados no ponto.
     * Devolve o projeto e o id da segunda parte.
     */
    fun split(project: Project, id: String, atUs: Long): Pair<Project, String>? {
        val index = project.stickers.indexOfFirst { it.id == id }
        if (index < 0 || project.stickers.size >= MAX_STICKERS) return null
        val sticker = project.stickers[index]
        if (atUs - sticker.startUs < MIN_STICKER_DURATION_US || sticker.endUs - atUs < MIN_STICKER_DURATION_US) return null

        val firstKeys = cutKeyframes(sticker, sticker.startUs, atUs)
        val secondKeys = cutKeyframes(sticker, atUs, sticker.endUs)
        val localCut = atUs - sticker.startUs
        val first = sticker.copy(
            endUs = atUs,
            transformKeyframes = firstKeys,
            mask = sticker.mask?.let { maskPart(it, 0L, localCut) },
            animations = sticker.animations.copy(exit = StickerAnimationType.NONE),
        )
        val second = sticker.copy(
            id = newId(),
            startUs = atUs,
            transformKeyframes = secondKeys,
            mask = sticker.mask?.let { maskPart(it, localCut, sticker.durationUs) },
            animations = sticker.animations.copy(enter = StickerAnimationType.NONE),
        )
        val stickers = project.stickers.toMutableList().apply { set(index, first); add(index + 1, second) }
        return project.copy(stickers = stickers) to second.id
    }

    /**
     * Copia o sticker [id] para o mesmo intervalo, um pouco deslocado para dar para ver as duas,
     * logo acima do original na ordem das camadas. Devolve o projeto e o id da cópia.
     */
    fun duplicate(project: Project, id: String): Pair<Project, String>? {
        val index = project.stickers.indexOfFirst { it.id == id }
        if (index < 0 || project.stickers.size >= MAX_STICKERS) return null
        val sticker = project.stickers[index]
        val shift = .04f
        fun nudge(value: Float) = (value + shift).let { if (it > 1f) value - shift else it }.coerceIn(0f, 1f)
        val copy = sticker.copy(
            id = newId(),
            x = nudge(sticker.x),
            y = nudge(sticker.y),
            transformKeyframes = sticker.transformKeyframes.map {
                it.copy(transform = it.transform.copy(x = nudge(it.transform.x), y = nudge(it.transform.y)))
            },
        )
        val stickers = project.stickers.toMutableList().apply { add(index + 1, copy) }
        return project.copy(stickers = stickers) to copy.id
    }

    fun delete(project: Project, id: String): Project =
        project.copy(stickers = project.stickers.filterNot { it.id == id })

    fun mirror(project: Project, id: String): Project =
        project.copy(stickers = project.stickers.map { if (it.id == id) it.copy(flip = !it.flip) else it })

    /**
     * Ordem das camadas. Na lista do projeto o último é desenhado por cima, então "frente" é o fim.
     * [frontToBack] é a ordem mostrada no painel Organizar camadas (da frente para trás).
     */
    fun frontToBack(project: Project): List<StickerClip> = project.stickers.asReversed()

    /** Aplica uma ordem escolhida no painel (lista de ids da frente para trás). */
    fun reorder(project: Project, idsFrontToBack: List<String>): Project {
        val byId = project.stickers.associateBy { it.id }
        if (idsFrontToBack.size != byId.size || idsFrontToBack.toSet() != byId.keys) return project
        return project.copy(stickers = idsFrontToBack.asReversed().map { byId.getValue(it) })
    }

    /** Move uma posição para a frente (+1) ou para trás (-1). */
    fun step(project: Project, id: String, direction: Int): Project {
        val index = project.stickers.indexOfFirst { it.id == id }
        val target = index + direction.coerceIn(-1, 1)
        if (index < 0 || target !in project.stickers.indices || target == index) return project
        val list = project.stickers.toMutableList()
        list.add(target, list.removeAt(index))
        return project.copy(stickers = list)
    }

    fun bringToFront(project: Project, id: String): Project {
        val sticker = project.stickers.firstOrNull { it.id == id } ?: return project
        return project.copy(stickers = project.stickers.filterNot { it.id == id } + sticker)
    }

    fun sendToBack(project: Project, id: String): Project {
        val sticker = project.stickers.firstOrNull { it.id == id } ?: return project
        return project.copy(stickers = listOf(sticker) + project.stickers.filterNot { it.id == id })
    }

    /**
     * Grava [transform] no sticker como faz o arraste na prévia: sem keyframes muda a base; com
     * keyframes cria/atualiza o keyframe em [timeUs] (tempo da timeline).
     */
    fun applyTransform(sticker: StickerClip, transform: com.termex.replay15.editor.transform.TransformState, timeUs: Long): StickerClip {
        val clean = transform.bounded().let { it.copy(opacity = it.opacity.coerceIn(.05f, 1f)) }
        if (sticker.transformKeyframes.isEmpty()) {
            return sticker.copy(x = clean.x, y = clean.y, scale = clean.scaleX, rotation = clean.rotation, opacity = clean.opacity)
        }
        val at = timeUs.coerceIn(sticker.startUs, sticker.endUs)
        val tolerance = 35_000L
        val existing = sticker.transformKeyframes.firstOrNull { kotlin.math.abs(it.timeUs - at) <= tolerance }
        val key = existing?.copy(transform = clean) ?: TransformKeyframe(at, clean)
        val others = sticker.transformKeyframes.filterNot { if (existing != null) it.timeUs == existing.timeUs else kotlin.math.abs(it.timeUs - at) <= tolerance }
        return sticker.copy(transformKeyframes = (others + key).sortedBy { it.timeUs }.take(200))
    }

    /** Keyframes (tempo absoluto) dentro de [from, to], com chaves de borda para não pular no corte. */
    private fun cutKeyframes(sticker: StickerClip, fromUs: Long, toUs: Long): List<TransformKeyframe> {
        val keys = sticker.transformKeyframes
        if (keys.isEmpty()) return keys
        val inside = keys.filter { it.timeUs in fromUs..toUs }
        val edges = listOf(fromUs, toUs).filter { edge -> inside.none { it.timeUs == edge } }
            .map { edge -> TransformKeyframe(edge, sticker.transformAt(edge), keys.first().easing, keys.first().bezier) }
        return (inside + edges).sortedBy { it.timeUs }.take(200)
    }

    /** Máscara de um pedaço [from, to] do tempo local; as chaves passam a contar do novo início. */
    private fun maskPart(mask: MaskState, fromUs: Long, toUs: Long): MaskState {
        if (mask.keyframes.isEmpty()) return mask
        val inside = mask.keyframes.filter { it.timeUs in fromUs..toUs }
        val edges = listOf(fromUs, toUs).filter { edge -> inside.none { it.timeUs == edge } }
            .map { edge -> MaskKeyframe.from(edge, mask.at(edge), mask.keyframes.first().easing) }
        val shifted = (inside + edges).sortedBy { it.timeUs }.map { it.copy(timeUs = it.timeUs - fromUs) }
            .distinctBy { it.timeUs }
            .take(MaskState.MAX_MASK_KEYFRAMES)
        return mask.copy(keyframes = shifted)
    }
}
