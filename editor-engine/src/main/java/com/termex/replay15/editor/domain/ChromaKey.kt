package com.termex.replay15.editor.domain

/**
 * GPU-facing chroma-key settings. StudioGrade remains the persisted owner for
 * backwards compatibility; these helpers give preview/export one typed model.
 */
data class ChromaKeyState(
    val enabled: Boolean = false,
    val keyColor: Int = 0xFF00FF00.toInt(),
    val similarity: Float = .25f,
    val smoothness: Float = .08f,
    val spill: Float = 0f,
    val edgeFeather: Float = 0f,
) {
    init {
        require(similarity in .01f.. .8f)
        require(smoothness in .001f.. .5f)
        require(spill in 0f..1f)
        require(edgeFeather in -.2f.. .2f)
    }
}

fun StudioGrade.chromaKeyState(): ChromaKeyState = ChromaKeyState(
    enabled = chromaEnabled,
    keyColor = chromaColor,
    similarity = chromaTolerance,
    smoothness = chromaSmoothness,
    spill = chromaSpill,
    edgeFeather = chromaEdge,
)

fun StudioGrade.withChromaKey(state: ChromaKeyState): StudioGrade = copy(
    chromaEnabled = state.enabled,
    chromaColor = state.keyColor,
    chromaTolerance = state.similarity,
    chromaSmoothness = state.smoothness,
    chromaSpill = state.spill,
    chromaEdge = state.edgeFeather,
)
