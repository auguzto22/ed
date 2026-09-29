package com.termex.replay15.editor.captions

import com.termex.replay15.editor.domain.Project
import java.util.Locale

enum class DynamicEntityType {
    PERSON, CREATOR, BRAND, APP, COMPANY, GAME, LOCATION, PRODUCT
}

data class DynamicEntity(
    val value: String,
    val type: DynamicEntityType,
    val aliases: Set<String> = emptySet(),
)

/**
 * Project-local names are weak evidence. They are useful only when the ASR has
 * already produced an acoustically compatible candidate.
 */
class DynamicEntityContext(
    val entities: List<DynamicEntity>,
) {
    val terms: Set<String> = entities.flatMap { entity ->
        listOf(entity.value) + entity.aliases + entity.value.split(Regex("\\s+"))
    }.filter { it.isNotBlank() }.toSet()

    fun contains(value: String): Boolean = entities.any { entity ->
        val normalized = normalize(value)
        normalize(entity.value) == normalized || normalized in normalize(entity.value).split(' ') ||
            entity.aliases.any { normalize(it) == normalized }
    }

    /** Bounded and confidence-gated so a famous name cannot win unclear audio. */
    fun bonus(value: String, acousticConfidence: Float?): Float {
        val confidence = acousticConfidence ?: return 0f
        if (confidence < .68f || !contains(value)) return 0f
        return if (confidence >= .82f) .04f else .02f
    }

    fun entityType(value: String): DynamicEntityType? = entities.firstOrNull {
        val normalized = normalize(value)
        normalize(it.value) == normalized || normalized in normalize(it.value).split(' ') ||
            it.aliases.any { alias -> normalize(alias) == normalized }
    }?.type

    companion object {
        private val locale = Locale.forLanguageTag("pt-BR")
        private val ignored = setOf(
            "a", "o", "as", "os", "um", "uma", "com", "do", "da", "dos", "das",
            "de", "em", "no", "na", "eu", "você", "voce", "ele", "ela", "nós", "nos",
            "meu", "minha", "isso", "esse", "essa", "aqui", "muito", "bom", "melhor", "melhores",
            "ontem", "abre", "manda", "faz", "podcast", "podcasts", "video", "vídeo", "projeto",
            "novo", "nova", "legenda", "legendas", "instagram", "insta", "youtube",
            "tiktok", "whatsapp", "discord", "google", "capcut"
        )

        fun empty(): DynamicEntityContext = DynamicEntityContext(emptyList())

        fun fromProject(
            project: Project,
            vocabulary: BrazilianMixedVocabulary = BrazilianMixedVocabulary.default(),
        ): DynamicEntityContext = fromMetadata(
            title = project.name,
            description = (project.videos.map { it.name } + project.texts.map { it.text } +
                project.markers.map { it.name }).joinToString(" "),
            knownTerms = project.captionVocabulary,
            vocabulary = vocabulary,
        )

        fun fromMetadata(
            title: String?,
            description: String? = null,
            knownTerms: Set<String> = emptySet(),
            vocabulary: BrazilianMixedVocabulary = BrazilianMixedVocabulary.empty(),
        ): DynamicEntityContext {
            val result = linkedMapOf<String, DynamicEntity>()
            fun add(value: String, type: DynamicEntityType, aliases: Set<String> = emptySet()) {
                val clean = value.trim().replace(Regex("\\s+"), " ")
                if (clean.isBlank() || clean.length > 80 || normalize(clean) in ignored) return
                result.putIfAbsent(normalize(clean), DynamicEntity(clean, type, aliases))
            }

            listOfNotNull(title, description).forEach { text ->
                // Capitalisation is useful for names in titles such as
                // "Podcast com Douglas Viegas", but common product words are filtered.
                val regex = Regex("(?<![\\p{L}])(?:[A-ZÁÉÍÓÚÂÊÔÃÕÇ][\\p{L}]+(?:\\s+[A-ZÁÉÍÓÚÂÊÔÃÕÇ][\\p{L}]+){0,3})")
                regex.findAll(text).forEach { match ->
                    val phrase = match.value.trim()
                    if (phrase.split(Regex("\\s+")).size > 1) add(phrase, DynamicEntityType.PERSON)
                    else if (phrase.length >= 3 && normalize(phrase) !in ignored) add(phrase, DynamicEntityType.PRODUCT)
                }
            }
            knownTerms.forEach { term ->
                if (!vocabulary.contains(term)) add(term, DynamicEntityType.PERSON)
            }
            return DynamicEntityContext(result.values.toList())
        }

        internal fun normalize(value: String): String = value.trim().lowercase(locale)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
    }
}
