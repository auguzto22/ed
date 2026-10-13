package com.termex.replay15.editor.highlights

import com.termex.replay15.editor.autoedit.MotionSample

/**
 * Trocas de cena/câmera a partir do [MotionSample] que o `ClipAnalyzer` já mede (diferença média de
 * luminância entre quadros a cada 500 ms). Não decodifica nada além do que a análise já fez.
 *
 * Uma troca de cena é um pico isolado: a diferença é alta em termos absolutos **e** bem maior que a
 * mediana dos vizinhos. Movimento contínuo (câmera andando, gameplay) eleva a mediana junto e não
 * dispara; um corte seco aparece como um único salto.
 */
object SceneCutDetector {
    /** Diferença mínima absoluta (0..1) para considerar troca de cena. */
    const val MIN_AMOUNT = .30f
    /** Quantas vezes acima da mediana dos vizinhos o pico precisa estar. */
    const val MIN_RATIO = 3f
    /** Vizinhos considerados de cada lado. */
    const val WINDOW = 3
    /** Duas trocas mais próximas que isso contam como uma. */
    const val MIN_SPACING_US = 1_000_000L

    /**
     * Devolve os instantes das trocas (µs, na mesma base de tempo das amostras). O corte aconteceu
     * entre a amostra anterior e esta, então o instante é o ponto médio entre as duas.
     */
    fun detect(motion: List<MotionSample>): List<Long> {
        if (motion.size < 3) return emptyList()
        val sorted = motion.sortedBy { it.timeUs }
        val cuts = mutableListOf<Long>()
        for (i in sorted.indices) {
            val amount = sorted[i].amount
            if (amount < MIN_AMOUNT) continue
            val neighbours = (maxOf(0, i - WINDOW)..minOf(sorted.lastIndex, i + WINDOW))
                .filter { it != i }
                .map { sorted[it].amount }
                .sorted()
            val median = neighbours[neighbours.size / 2]
            if (amount < MIN_RATIO * median) continue
            val previousUs = if (i > 0) sorted[i - 1].timeUs else 0L
            val cutUs = (previousUs + sorted[i].timeUs) / 2
            if (cuts.isNotEmpty() && cutUs - cuts.last() < MIN_SPACING_US) continue
            cuts += cutUs
        }
        return cuts
    }
}
