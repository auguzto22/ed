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

/** The segmentation AI provider used to isolate subjects and remove background. */
enum class BackgroundRemovalProvider {
    AUTO,
    GEMINI,
    MLKIT,
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
    val provider: BackgroundRemovalProvider = BackgroundRemovalProvider.AUTO,
) {
    init {
        require(threshold in 0f..1f)
        require(feather in 0f..0.5f)
        require(edgeSmoothing in 0f..1f)
        require(backgroundUri == null || backgroundUri.length <= 2048)
    }
}


/**
 * Linear mapping between the "Precisão" slider (0..100 %) and the matte threshold.
 *
 * The slider used to be the raw threshold, so 100 % meant threshold 1.0: only pixels with
 * absolute certainty survived and the subject practically disappeared. The usable range of the
 * threshold is roughly 0.25 (loose: keeps shadows/halos) to 0.75 (tight: trims every uncertain
 * edge), so the slider now covers exactly that, linearly. 50 % is still threshold 0.5, so
 * existing projects look the same.
 */
object BackgroundPrecision {
    const val MIN_THRESHOLD = 0.25f
    const val MAX_THRESHOLD = 0.75f

    fun thresholdFor(percent: Int): Float =
        MIN_THRESHOLD + (MAX_THRESHOLD - MIN_THRESHOLD) * percent.coerceIn(0, 100) / 100f

    fun percentFor(threshold: Float): Int =
        ((threshold - MIN_THRESHOLD) / (MAX_THRESHOLD - MIN_THRESHOLD) * 100f).let { kotlin.math.round(it).toInt() }.coerceIn(0, 100)
}
