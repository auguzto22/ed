package com.termex.replay15.editor.autoedit

import java.text.Normalizer
import java.util.Locale

/** A moment where an image would illustrate the speech, before any image is searched. */
data class BrollSuggestion(
    val startUs: Long, val endUs: Long, val keyword: String, val query: String, val confidence: Float = .7f,
) {
    init { require(startUs >= 0 && endUs > startUs && keyword.isNotBlank() && query.isNotBlank() && confidence in 0f..1f) }
}

/**
 * Chooses where full-screen B-roll images go. Suggestions come from the AI pass
 * ([BrollAiSuggester]) or, without it, from a pt-BR dictionary of concrete, picturable words.
 * Either way the planner alone decides timing: it keeps the opening on the speaker, spaces the
 * images, caps how many a clip gets and never lets an image cross a removed pause.
 */
object BrollPlanner {
    /** The hook (first sentence) stays on the speaker's face. */
    const val MIN_START_US = 3_000_000L
    const val DEFAULT_DURATION_US = 1_500_000L
    const val MIN_DURATION_US = 800_000L
    const val MAX_DURATION_US = 2_500_000L
    /** Speaker time between two images. */
    const val MIN_SPACING_US = 2_500_000L
    /** The same thing is not illustrated twice within this window. */
    const val SAME_KEYWORD_US = 12_000_000L
    /** The clip never ends on an image. */
    const val END_GUARD_US = 300_000L
    const val MAX_IMAGES = 12
    private const val LEAD_US = 80_000L

    /** One image every ~6.5 s at most (the reference cuts away about that often). */
    fun budget(durationUs: Long): Int = (durationUs / 6_500_000L).toInt().coerceIn(1, MAX_IMAGES)

    fun plan(
        words: List<TimedWord>,
        durationUs: Long,
        cuts: List<CutDecision> = emptyList(),
        suggestions: List<BrollSuggestion>? = null,
    ): List<BrollDecision> {
        if (durationUs < MIN_START_US + MIN_DURATION_US + END_GUARD_US) return emptyList()
        val fromAi = !suggestions.isNullOrEmpty()
        val candidates = if (fromAi) suggestions!! else lexiconSuggestions(words)
        val placed = candidates.mapNotNull { fit(it, durationUs, cuts) }
        val chosen = mutableListOf<BrollSuggestion>()
        placed.sortedWith(compareByDescending<BrollSuggestion> { it.confidence }.thenBy { it.startUs }).forEach { candidate ->
            if (chosen.size >= budget(durationUs)) return@forEach
            val spaced = chosen.none { other ->
                candidate.startUs < other.endUs + MIN_SPACING_US && other.startUs < candidate.endUs + MIN_SPACING_US
            }
            // Same word or same search ("minimalista" / "simplicidade") would show the same kind of picture.
            val repeated = chosen.any { other ->
                (normalize(other.keyword) == normalize(candidate.keyword) || normalize(other.query) == normalize(candidate.query)) &&
                    kotlin.math.abs(other.startUs - candidate.startUs) < SAME_KEYWORD_US
            }
            if (spaced && !repeated) chosen += candidate
        }
        return chosen.sortedBy { it.startUs }.map { suggestion ->
            BrollDecision(suggestion.startUs, suggestion.endUs, suggestion.keyword.take(60), suggestion.query.take(80),
                suggestion.confidence, if (fromAi) "IA: fala cita \"${suggestion.keyword.take(40)}\"" else "fala cita \"${suggestion.keyword.take(40)}\"")
        }
    }

    /** Clamps a suggestion into the clip, away from the opening, the ending and removed pauses. */
    private fun fit(suggestion: BrollSuggestion, durationUs: Long, cuts: List<CutDecision>): BrollSuggestion? {
        val limit = durationUs - END_GUARD_US
        // A mention inside the hook is skipped, not delayed: the picture must match what is said.
        if (suggestion.startUs < MIN_START_US) return null
        var start = suggestion.startUs
        var end = minOf(suggestion.endUs, start + MAX_DURATION_US, limit)
        if (end - start < MIN_DURATION_US) end = minOf(start + DEFAULT_DURATION_US, limit)
        // An image must not span a removed pause: after the cut it would cover two unrelated sentences.
        cuts.sortedBy { it.startUs }.forEach { cut ->
            if (cut.startUs < end && cut.endUs > start) {
                if (cut.startUs - start >= MIN_DURATION_US) end = cut.startUs else start = cut.endUs
            }
        }
        if (end > limit) end = limit
        if (end - start < MIN_DURATION_US) return null
        if (cuts.any { it.startUs < end && it.endUs > start }) return null
        return suggestion.copy(startUs = start, endUs = end)
    }

    /** Dictionary fallback: the first picturable word of each mention, with its stock search. */
    fun lexiconSuggestions(words: List<TimedWord>): List<BrollSuggestion> = words.mapNotNull { word ->
        val entry = BrollLexicon.lookup(word.text) ?: return@mapNotNull null
        val start = (word.startUs - LEAD_US).coerceAtLeast(0L)
        BrollSuggestion(start, start + DEFAULT_DURATION_US, PodcastCaptions.displayWord(word.text).ifBlank { entry.first }, entry.second, .7f)
    }

    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

/**
 * Concrete Portuguese words that read well as a stock photo, with an English search query
 * (stock libraries index in English). Ambiguous words ("real", "nota", "bolsa", "conta",
 * "tempo") are deliberately absent: a wrong picture is worse than none.
 */
object BrollLexicon {
    private val vocabulary: List<Pair<List<String>, String>> = listOf(
        listOf("dinheiro", "grana", "reais", "dolar", "dolares", "salario", "salarios", "renda", "lucro", "lucros") to "money cash",
        listOf("rico", "ricos", "rica", "ricas", "riqueza", "milionario", "milionarios", "milionaria", "luxo") to "luxury wealth",
        listOf("pobre", "pobres", "pobreza") to "poverty",
        listOf("trabalho", "trabalhos", "trabalhar", "trabalhando", "trabalhei", "trampo", "trampar", "emprego", "empregos", "bico", "bicos", "servico", "obra") to "worker working",
        listOf("empresa", "empresas", "negocio", "negocios", "empreender", "empreendedor", "empreendedora", "empreendedores") to "business meeting",
        listOf("escritorio", "chefe", "patrao") to "office work",
        listOf("casa", "casas", "lar", "apartamento", "apartamentos", "imovel", "imoveis") to "house home",
        listOf("carro", "carros", "automovel") to "car",
        listOf("moto", "motos", "motocicleta") to "motorcycle",
        listOf("viagem", "viagens", "viajar", "viajando", "ferias") to "travel",
        listOf("praia", "praias", "mar") to "beach",
        listOf("aviao", "avioes", "aeroporto") to "airplane",
        listOf("familia", "familias") to "family",
        listOf("filho", "filhos", "filha", "filhas", "crianca", "criancas") to "children playing",
        listOf("esposa", "marido", "casamento", "casal") to "couple",
        listOf("amigo", "amigos", "amiga", "amigas", "amizade") to "friends together",
        listOf("comida", "comidas", "almoco", "jantar", "restaurante") to "food meal",
        listOf("cafe") to "coffee",
        listOf("academia", "treino", "treinar", "malhar", "musculacao") to "gym workout",
        listOf("medico", "medicos", "hospital", "hospitais") to "doctor hospital",
        listOf("celular", "celulares", "smartphone", "telefone") to "smartphone",
        listOf("computador", "computadores", "notebook") to "laptop computer",
        listOf("escola", "escolas", "faculdade", "universidade", "estudar", "estudando", "estudo", "livro", "livros") to "studying books",
        listOf("cartao", "cartoes", "credito", "divida", "dividas", "boleto", "boletos", "contas") to "credit card bills",
        listOf("banco", "bancos") to "bank",
        listOf("investir", "investimento", "investimentos", "investidor", "investidores") to "stock market investing",
        listOf("comprar", "compra", "compras", "comprando", "consumo", "consumir", "shopping") to "shopping bags",
        listOf("loja", "lojas", "mercado", "supermercado") to "store shopping",
        listOf("relogio", "relogios") to "wrist watch",
        listOf("torneira", "torneiras") to "faucet water",
        listOf("ralo") to "sink drain water",
        listOf("agua") to "water",
        listOf("minimalista", "minimalismo", "simplicidade") to "minimalist home",
        listOf("natureza", "campo", "fazenda", "floresta") to "nature landscape",
        listOf("neve") to "snow",
        listOf("cidade", "cidades") to "city skyline",
        listOf("futebol") to "soccer",
        listOf("musica", "musicas") to "music",
        listOf("festa", "festas", "balada") to "party",
        listOf("dormir", "sono", "cama") to "sleeping bed",
        listOf("estresse", "ansiedade") to "stress",
        listOf("cachorro", "cachorros") to "dog",
        listOf("gato", "gatos") to "cat",
    )

    private val index: Map<String, Pair<String, String>> = buildMap {
        vocabulary.forEach { (forms, query) -> forms.forEach { form -> putIfAbsent(form, forms.first() to query) } }
    }

    /** Keyword (canonical form) and stock query for a spoken word, or null if it is not picturable. */
    fun lookup(word: String): Pair<String, String>? = index[BrollPlanner.normalize(word)]

    val size: Int get() = index.size
}
