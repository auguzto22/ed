package com.termex.replay15.editor.captions

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Terms that commonly occur inside Brazilian Portuguese speech.
 *
 * Pronunciation variants are recognition hints only. They are deliberately not
 * exposed as replacement rules and can never become caption text by themselves.
 */
enum class MixedVocabularyCategory {
    SOCIAL_MEDIA, INTERNET, CONTENT, TECHNOLOGY, GAMING, PT_BR_SLANG,
    BRAND, PERSON_NAME, COMPANY, PRODUCT, LOCATION
}

data class MixedVocabularyEntry(
    val canonical: String,
    val category: MixedVocabularyCategory,
    val pronunciationVariants: Set<String> = emptySet(),
    val bias: Float = .03f,
) {
    init {
        require(canonical.isNotBlank())
        require(bias in 0f..1f)
    }
}

class BrazilianMixedVocabulary private constructor(
    entries: Collection<MixedVocabularyEntry>,
) {
    private val byCanonical = entries
        .filter { it.canonical.isNotBlank() }
        .associateBy { normalize(it.canonical) }
    private val byVariant = entries.flatMap { entry ->
        entry.pronunciationVariants.map { normalize(it) to entry }
    }.toMap()

    val entries: List<MixedVocabularyEntry> = byCanonical.values.toList()
    val canonicalTerms: Set<String> = entries.map { it.canonical }.toSet()
    val pronunciationHints: Map<String, String> = byVariant.mapValues { it.value.canonical }

    fun contains(term: String): Boolean = normalize(term) in byCanonical

    fun entryFor(term: String): MixedVocabularyEntry? =
        byCanonical[normalize(term)] ?: byVariant[normalize(term)]

    /** Returns a bounded hint; it never creates a missing token. */
    fun vocabularyBonus(text: String, acousticConfidence: Float?): Float {
        val confidence = acousticConfidence ?: return 0f
        if (confidence < .55f) return 0f
        val tokens = normalize(text).split(' ').filter(String::isNotBlank)
        if (tokens.isEmpty()) return 0f
        val matched = entries.filter { entry ->
            val canonical = normalize(entry.canonical)
            canonical == tokens.joinToString(" ") || tokens.any { it == canonical }
        }
        return matched.maxOfOrNull { it.bias }?.coerceAtMost(.06f) ?: 0f
    }

    /**
     * This is intentionally a flat vocabulary for request metadata. A Vosk
     * grammar is not installed from it because a grammar would restrict free
     * Brazilian Portuguese instead of gently biasing it.
     */
    fun recognitionTerms(extra: Set<String> = emptySet()): Set<String> =
        (canonicalTerms + extra).filter(String::isNotBlank).toSet()

    companion object {
        private val locale = Locale.forLanguageTag("pt-BR")

        fun load(context: Context): BrazilianMixedVocabulary = runCatching {
            context.applicationContext.assets.open("language/pt_br/mixed_vocabulary.json")
                .bufferedReader().use { fromJson(JSONObject(it.readText())) }
        }.getOrElse { default() }

        fun fromEntries(entries: Collection<MixedVocabularyEntry>): BrazilianMixedVocabulary =
            BrazilianMixedVocabulary(entries)

        fun empty(): BrazilianMixedVocabulary = BrazilianMixedVocabulary(emptyList())

        fun default(): BrazilianMixedVocabulary = fromEntries(DEFAULT_ENTRIES)

        internal fun normalize(value: String): String = value.trim().lowercase(locale)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")

        private fun fromJson(json: JSONObject): BrazilianMixedVocabulary {
            val values = mutableListOf<MixedVocabularyEntry>()
            val categories = json.optJSONObject("categories") ?: JSONObject()
            categories.keys().forEach { categoryName ->
                val category = runCatching {
                    MixedVocabularyCategory.valueOf(categoryName.uppercase(Locale.US))
                }.getOrNull() ?: return@forEach
                val items = categories.optJSONArray(categoryName) ?: JSONArray()
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val canonical = item.optString("canonical").trim()
                    if (canonical.isBlank()) continue
                    val variants = item.optJSONArray("pronunciationVariants")?.let { array ->
                        (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }.toSet()
                    }.orEmpty()
                    values += MixedVocabularyEntry(canonical, category, variants,
                        item.optDouble("bias", .03).toFloat().coerceIn(0f, .06f))
                }
            }
            return fromEntries(values)
        }

        private val DEFAULT_ENTRIES = listOf(
            entry("Instagram", MixedVocabularyCategory.SOCIAL_MEDIA, "Instagrã"),
            entry("Insta", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("TikTok", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("YouTube", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("WhatsApp", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("Discord", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("Twitter", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("X", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("Facebook", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("Reddit", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("Twitch", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("Telegram", MixedVocabularyCategory.SOCIAL_MEDIA),
            entry("Google", MixedVocabularyCategory.BRAND),
            entry("ChatGPT", MixedVocabularyCategory.BRAND),
            entry("CapCut", MixedVocabularyCategory.BRAND),
            entry("link", MixedVocabularyCategory.INTERNET),
            entry("site", MixedVocabularyCategory.INTERNET),
            entry("app", MixedVocabularyCategory.INTERNET),
            entry("feed", MixedVocabularyCategory.INTERNET),
            entry("story", MixedVocabularyCategory.INTERNET),
            entry("stories", MixedVocabularyCategory.INTERNET),
            entry("reels", MixedVocabularyCategory.INTERNET),
            entry("shorts", MixedVocabularyCategory.INTERNET),
            entry("live", MixedVocabularyCategory.INTERNET),
            entry("like", MixedVocabularyCategory.INTERNET),
            entry("follow", MixedVocabularyCategory.INTERNET),
            entry("unfollow", MixedVocabularyCategory.INTERNET),
            entry("hashtag", MixedVocabularyCategory.INTERNET),
            entry("trend", MixedVocabularyCategory.INTERNET),
            entry("trending", MixedVocabularyCategory.INTERNET),
            entry("meme", MixedVocabularyCategory.INTERNET),
            entry("spoiler", MixedVocabularyCategory.INTERNET),
            entry("online", MixedVocabularyCategory.INTERNET),
            entry("offline", MixedVocabularyCategory.INTERNET),
            entry("podcast", MixedVocabularyCategory.CONTENT, "pódcast", "pódcást", "pódkésti"),
            entry("podcasts", MixedVocabularyCategory.CONTENT, "pódcasts"),
            entry("stream", MixedVocabularyCategory.CONTENT),
            entry("streamer", MixedVocabularyCategory.CONTENT),
            entry("streaming", MixedVocabularyCategory.CONTENT),
            entry("gameplay", MixedVocabularyCategory.CONTENT, "gueimplei"),
            entry("gaming", MixedVocabularyCategory.CONTENT),
            entry("video", MixedVocabularyCategory.CONTENT),
            entry("short", MixedVocabularyCategory.CONTENT),
            entry("react", MixedVocabularyCategory.CONTENT),
            entry("reaction", MixedVocabularyCategory.CONTENT),
            entry("download", MixedVocabularyCategory.TECHNOLOGY, "daunloud"),
            entry("upload", MixedVocabularyCategory.TECHNOLOGY),
            entry("setup", MixedVocabularyCategory.TECHNOLOGY),
            entry("PC", MixedVocabularyCategory.TECHNOLOGY),
            entry("notebook", MixedVocabularyCategory.TECHNOLOGY),
            entry("smartphone", MixedVocabularyCategory.TECHNOLOGY),
            entry("software", MixedVocabularyCategory.TECHNOLOGY),
            entry("hardware", MixedVocabularyCategory.TECHNOLOGY),
            entry("plugin", MixedVocabularyCategory.TECHNOLOGY),
            entry("API", MixedVocabularyCategory.TECHNOLOGY),
            entry("server", MixedVocabularyCategory.TECHNOLOGY),
            entry("cloud", MixedVocabularyCategory.TECHNOLOGY),
            entry("AI", MixedVocabularyCategory.TECHNOLOGY),
            entry("mano", MixedVocabularyCategory.PT_BR_SLANG),
            entry("mané", MixedVocabularyCategory.PT_BR_SLANG),
            entry("véi", MixedVocabularyCategory.PT_BR_SLANG, "vei"),
            entry("velho", MixedVocabularyCategory.PT_BR_SLANG),
            entry("pô", MixedVocabularyCategory.PT_BR_SLANG),
            entry("caraca", MixedVocabularyCategory.PT_BR_SLANG),
            entry("bora", MixedVocabularyCategory.PT_BR_SLANG),
            entry("oxe", MixedVocabularyCategory.PT_BR_SLANG),
            entry("oxente", MixedVocabularyCategory.PT_BR_SLANG),
            entry("eita", MixedVocabularyCategory.PT_BR_SLANG),
            entry("tá", MixedVocabularyCategory.PT_BR_SLANG),
            entry("tô", MixedVocabularyCategory.PT_BR_SLANG),
            entry("cê", MixedVocabularyCategory.PT_BR_SLANG),
            entry("pra", MixedVocabularyCategory.PT_BR_SLANG),
            entry("pro", MixedVocabularyCategory.PT_BR_SLANG),
            entry("né", MixedVocabularyCategory.PT_BR_SLANG),
            entry("vamo", MixedVocabularyCategory.PT_BR_SLANG),
            entry("tá ligado", MixedVocabularyCategory.PT_BR_SLANG),
        )

        private fun entry(canonical: String, category: MixedVocabularyCategory, vararg variants: String) =
            MixedVocabularyEntry(canonical, category, variants.toSet())
    }
}
