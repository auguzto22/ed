package com.termex.replay15.editor.captions

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** Loaded once per generator; terms are evidence for comparing measured ASR hypotheses only. */
class PtBrLexiconRepository private constructor(private val phrases: Set<String>) {
    constructor(context: Context) : this(context.assets.open("language/pt_br/lexicon.json")
        .bufferedReader().use { source ->
            val json = JSONObject(source.readText())
            listOf("slang", "internet", "gaming", "technology", "brands", "regionalisms")
                .flatMap { category ->
                    val array = json.getJSONArray(category)
                    (0 until array.length()).map { normalize(array.getString(it)) }
                }.toSet()
        })

    fun contains(phrase: String): Boolean = normalize(phrase) in phrases

    companion object {
        internal fun fromTerms(terms: Set<String>): PtBrLexiconRepository =
            PtBrLexiconRepository(terms.map(::normalize).toSet())
        fun normalize(value: String): String = value.trim().lowercase(Locale.forLanguageTag("pt-BR"))
            .replace(Regex("\\s+"), " ")
    }
}

/** Requires agreement by both retries and a real confidence gain before changing a word. */
class PtBrContextRescorer(
    private val lexicon: PtBrLexiconRepository,
    customVocabulary: Set<String> = emptySet(),
    private val corrections: CaptionCorrectionMemory? = null,
    private val mixedVocabulary: BrazilianMixedVocabulary = BrazilianMixedVocabulary.empty(),
    private val entities: DynamicEntityContext = DynamicEntityContext.empty(),
) {
    private val custom = customVocabulary.map(PtBrLexiconRepository::normalize).toSet()

    fun choose(primary: CaptionWord, before: String?, after: String?,
        processed: List<CaptionWord>, raw: List<CaptionWord>): CaptionWord? {
        val candidates = processed.filter { other ->
            other.text.isNotBlank() && !other.text.equals(primary.text, true) &&
                temporalOverlap(other, primary) >= .45f && (other.confidence ?: 0f) >= .75f
        }
        return candidates.mapNotNull { candidate ->
            val corroboration = raw.firstOrNull { other ->
                other.text.equals(candidate.text, true) && temporalOverlap(other, candidate) >= .45f &&
                    temporalOverlap(other, primary) >= .45f && (other.confidence ?: 0f) >= .75f
            } ?: return@mapNotNull null
            val acoustic = minOf(candidate.confidence!!, corroboration.confidence!!)
            val previous = primary.confidence ?: 0f
            val contextual = contextBonus(candidate.text, before, after) +
                (corrections?.bonus(primary.text, candidate.text, before, after) ?: 0f) +
                mixedVocabulary.vocabularyBonus(candidate.text, acoustic) + entities.bonus(candidate.text, acoustic)
            if (acoustic + contextual < previous + .08f) return@mapNotNull null
            candidate.copy(id = primary.id, startMs = primary.startMs, endMs = primary.endMs,
                confidence = acoustic, verificationState = VerificationState.RECOVERED) to (acoustic + contextual)
        }.maxByOrNull { it.second }?.first
    }

    private fun contextBonus(word: String, before: String?, after: String?): Float {
        val token = PtBrLexiconRepository.normalize(word)
        val inCustom = token in custom
        val phrase = listOfNotNull(before, word, after)
        val joined = phrase.joinToString(" ")
        val knownPhrase = lexicon.contains(joined) ||
            before?.let { lexicon.contains("$it $word") } == true ||
            after?.let { lexicon.contains("$word $it") } == true
        return when {
            inCustom -> .04f
            knownPhrase -> .03f
            lexicon.contains(token) -> .02f
            else -> 0f
        }
    }
}
