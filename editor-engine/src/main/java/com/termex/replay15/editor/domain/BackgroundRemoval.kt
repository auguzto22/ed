package com.termex.replay15.editor.domain

/** The visual operation used by the background-removal compositor. */
enum class BackgroundMode {
    REMOVE,
    COLOR,
    IMAGE,
    VIDEO,
    BLUR,
}

/** Preview/export quality controls the size of the image sent to the segmenter. */
enum class SegmentationQuality {
    FAST,
    BALANCED,
    HIGH,
}

/**
 * Persisted, non-destructive background-removal settings.
 *
 * The mask is intentionally not part of the project. It is a bounded runtime cache and can be
 * rebuilt from the source media at any time.
 */
data class BackgroundRemovalEffect(
    val enabled: Boolean = false,
    val mode: BackgroundMode = BackgroundMode.REMOVE,
    val threshold: Float = 0.5f,
    val feather: Float = 0.08f,
    val edgeSmoothing: Float = 0.5f,
    val quality: SegmentationQuality = SegmentationQuality.BALANCED,
    val backgroundUri: String? = null,
    val backgroundColor: Int = 0x00000000,
) {
    init {
        require(threshold in 0f..1f)
        require(feather in 0f..0.5f)
        require(edgeSmoothing in 0f..1f)
        require(backgroundUri == null || backgroundUri.length <= 2048)
    }
}
