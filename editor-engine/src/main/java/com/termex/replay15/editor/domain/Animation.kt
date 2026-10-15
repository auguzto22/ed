package com.termex.replay15.editor.domain

/**
 * Movimentos prontos que o [AnimationClip] sabe aplicar. Cada preset descreve só a forma do
 * movimento; a força vem de [AnimationClip.intensity] e o ritmo de [AnimationClip.easing].
 *
 * O [id] é o que vai para o arquivo do projeto (nunca o ordinal), então novos presets podem
 * entrar em qualquer posição sem quebrar projetos salvos.
 */
enum class MotionPreset(val id: String, val label: String) {
    ZOOM_IN("zoom_in", "Zoom in"),
    ZOOM_OUT("zoom_out", "Zoom out"),
    PAN_LEFT("pan_left", "Pan para a esquerda"),
    PAN_RIGHT("pan_right", "Pan para a direita"),
    PUNCH_IN("punch_in", "Punch in"),
    SHAKE("shake", "Tremor"),
    KEN_BURNS("ken_burns", "Ken Burns"),
    KEN_BURNS_LEFT("ken_burns_left", "Ken Burns para a esquerda"),
    KEN_BURNS_OUT("ken_burns_out", "Ken Burns afastando"),
    KEN_BURNS_OUT_LEFT("ken_burns_out_left", "Ken Burns afastando para a esquerda");

    companion object {
        fun fromId(id: String): MotionPreset? = entries.firstOrNull { it.id == id }
    }
}

const val MIN_ANIMATION_US = 80_000L
const val DEFAULT_ANIMATION_US = 1_500_000L
const val MIN_ANIMATION_INTENSITY = .1f
const val MAX_ANIMATION_INTENSITY = 3f
const val MAX_ANIMATION_CLIPS = 400

/**
 * Um movimento com início e fim sobre um alvo (clipe de vídeo/foto, texto ou sticker), mostrado
 * na timeline como um balão.
 *
 * [startUs] e [endUs] são relativos ao INÍCIO DO ALVO, não ao projeto. É de propósito: mover o
 * clipe, apagar um clipe anterior (ripple) ou reordenar a sequência leva o balão junto sem que
 * ninguém precise reescrever tempos, e um corte só precisa repartir os balões entre as duas
 * metades. A posição na timeline é `início do alvo + startUs` (veja [AnimationEditing]).
 *
 * Fora de `[startUs, endUs)` a animação não contribui com nada: o alvo volta ao que os keyframes
 * manuais dizem.
 */
data class AnimationClip(
    val id: String = newId(),
    val targetId: String,
    val startUs: Long,
    val endUs: Long,
    val preset: MotionPreset = MotionPreset.ZOOM_IN,
    val intensity: Float = 1f,
    val easing: Easing = Easing.SMOOTH,
) {
    init {
        require(id.matches(ID_PATTERN) && targetId.matches(ID_PATTERN))
        require(startUs >= 0 && endUs - startUs >= MIN_ANIMATION_US && endUs <= MAX_PROJECT_US)
        require(intensity.isFinite() && intensity in MIN_ANIMATION_INTENSITY..MAX_ANIMATION_INTENSITY)
    }

    val durationUs: Long get() = endUs - startUs

    fun withWindow(startUs: Long, endUs: Long): AnimationClip = copy(startUs = startUs, endUs = endUs)

    private companion object { val ID_PATTERN = Regex("[a-zA-Z0-9-]{1,80}") }
}

/**
 * Edições puras sobre as animações de um [Project]. Nada aqui mexe em alvo nenhum: só nos
 * balões, sempre devolvendo um projeto válido (ou null quando o pedido não cabe).
 */
object AnimationEditing {
    /** Onde o alvo está na timeline do projeto e quanto dura. */
    data class Target(val startUs: Long, val durationUs: Long) {
        val endUs: Long get() = startUs + durationUs
    }

    fun target(project: Project, targetId: String): Target? {
        val index = project.videos.indexOfFirst { it.id == targetId }
        if (index >= 0) return Target(project.startOf(index), project.videos[index].durationUs)
        for (track in project.videoTracks) {
            val placed = track.clips.firstOrNull { it.clip.id == targetId } ?: continue
            return Target(placed.startUs, placed.clip.durationUs)
        }
        project.texts.firstOrNull { it.id == targetId }?.let { return Target(it.startUs, it.durationUs) }
        project.stickers.firstOrNull { it.id == targetId }?.let { return Target(it.startUs, it.durationUs) }
        return null
    }

    fun of(project: Project, targetId: String): List<AnimationClip> =
        project.animationClips.filter { it.targetId == targetId }

    /** Janela do balão na timeline do projeto, ou null se o alvo não existe mais. */
    fun absoluteWindow(project: Project, clip: AnimationClip): LongRange? {
        val target = target(project, clip.targetId) ?: return null
        return (target.startUs + clip.startUs) until (target.startUs + clip.endUs)
    }

    /** Cria um balão de [preset] começando em [atProjectUs] (tempo do projeto). */
    fun create(
        project: Project, targetId: String, atProjectUs: Long, preset: MotionPreset,
        durationUs: Long = DEFAULT_ANIMATION_US, intensity: Float = 1f, easing: Easing = Easing.SMOOTH,
    ): Pair<Project, AnimationClip>? {
        if (project.animationClips.size >= MAX_ANIMATION_CLIPS) return null
        val target = target(project, targetId) ?: return null
        if (target.durationUs < MIN_ANIMATION_US) return null
        // Perto do fim do alvo não sobra a duração pedida: recua o início em vez de recusar.
        val length = durationUs.coerceIn(MIN_ANIMATION_US, target.durationUs)
        val start = (atProjectUs - target.startUs).coerceIn(0L, target.durationUs - length)
        val end = start + length
        val clip = AnimationClip(targetId = targetId, startUs = start, endUs = end, preset = preset,
            intensity = intensity.coerceIn(MIN_ANIMATION_INTENSITY, MAX_ANIMATION_INTENSITY), easing = easing)
        return project.copy(animationClips = project.animationClips + clip) to clip
    }

    fun update(project: Project, id: String, change: (AnimationClip) -> AnimationClip): Project {
        if (project.animationClips.none { it.id == id }) return project
        return project.copy(animationClips = project.animationClips.map { if (it.id == id) change(it) else it })
    }

    fun remove(project: Project, id: String): Project =
        if (project.animationClips.none { it.id == id }) project
        else project.copy(animationClips = project.animationClips.filterNot { it.id == id })

    /**
     * Move ou estica o balão para a janela [absStartUs, absEndUs) da timeline do projeto. A janela
     * é encolhida para caber no alvo (nunca estoura o clipe, o texto ou o sticker).
     */
    fun setAbsoluteWindow(project: Project, id: String, absStartUs: Long, absEndUs: Long): Project {
        val clip = project.animationClips.firstOrNull { it.id == id } ?: return project
        val target = target(project, clip.targetId) ?: return project
        val maxStart = target.durationUs - MIN_ANIMATION_US
        if (maxStart < 0L) return project
        val start = (absStartUs - target.startUs).coerceIn(0L, maxStart)
        val end = (absEndUs - target.startUs).coerceIn(start + MIN_ANIMATION_US, target.durationUs)
        if (start == clip.startUs && end == clip.endUs) return project
        return update(project, id) { it.withWindow(start, end) }
    }

    /** Copia o balão ao lado dele (depois, ou antes se não couber), dentro do mesmo alvo. */
    fun duplicate(project: Project, id: String): Pair<Project, AnimationClip>? {
        if (project.animationClips.size >= MAX_ANIMATION_CLIPS) return null
        val original = project.animationClips.firstOrNull { it.id == id } ?: return null
        val target = target(project, original.targetId) ?: return null
        val length = original.durationUs
        val copy = when {
            original.endUs + length <= target.durationUs -> original.copy(id = newId(), startUs = original.endUs, endUs = original.endUs + length)
            original.startUs - length >= 0L -> original.copy(id = newId(), startUs = original.startUs - length, endUs = original.startUs)
            else -> return null
        }
        return project.copy(animationClips = project.animationClips + copy) to copy
    }

    /**
     * Um corte do alvo em [cutLocalUs] (tempo local do alvo). Balões inteiros de um dos lados ficam
     * ou passam para [rightTargetId]; um balão que atravessa o corte é repartido nos dois.
     */
    fun splitTarget(project: Project, targetId: String, rightTargetId: String, cutLocalUs: Long): Project {
        if (project.animationClips.none { it.targetId == targetId }) return project
        val result = ArrayList<AnimationClip>(project.animationClips.size + 2)
        project.animationClips.forEach { clip ->
            if (clip.targetId != targetId) { result += clip; return@forEach }
            val leftEnd = minOf(clip.endUs, cutLocalUs)
            val rightStart = maxOf(clip.startUs, cutLocalUs)
            if (leftEnd - clip.startUs >= MIN_ANIMATION_US) result += clip.copy(endUs = leftEnd)
            if (clip.endUs - rightStart >= MIN_ANIMATION_US) {
                val keepsId = leftEnd - clip.startUs < MIN_ANIMATION_US
                result += clip.copy(id = if (keepsId) clip.id else newId(), targetId = rightTargetId,
                    startUs = rightStart - cutLocalUs, endUs = clip.endUs - cutLocalUs)
            }
        }
        return project.copy(animationClips = result.take(MAX_ANIMATION_CLIPS))
    }

    /**
     * Regra única que toda edição respeita: um balão pertence a um alvo que existe e cabe dentro
     * dele. Apagar, aparar ou encurtar o alvo, ou abrir um arquivo antigo, passam por aqui (o
     * [com.termex.replay15.editor.core.EditorSessionController] e o [com.termex.replay15.editor.project.ProjectCodec]).
     */
    fun sanitized(project: Project): Project {
        if (project.animationClips.isEmpty()) return project
        val seen = HashSet<String>()
        val kept = project.animationClips.mapNotNull { clip ->
            if (!seen.add(clip.id)) return@mapNotNull null
            val target = target(project, clip.targetId) ?: return@mapNotNull null
            val end = minOf(clip.endUs, target.durationUs)
            if (end - clip.startUs < MIN_ANIMATION_US) null
            else if (end == clip.endUs) clip else clip.copy(endUs = end)
        }.take(MAX_ANIMATION_CLIPS)
        return if (kept == project.animationClips) project else project.copy(animationClips = kept)
    }
}

fun Project.withSanitizedAnimations(): Project = AnimationEditing.sanitized(this)
