package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import com.termex.replay15.editor.domain.BackgroundRemovalProvider

/**
 * Factory for creating segmentation engines and mask appliers according to the requested provider.
 */
object SegmentationEngineFactory {

    fun createEngine(
        context: Context,
        streamMode: Boolean,
        @Suppress("UNUSED_PARAMETER") provider: BackgroundRemovalProvider = BackgroundRemovalProvider.AUTO,
    ): SegmentationEngine {
        // Every provider gets the on-device engine the export uses: the per-pixel live model for
        // video and the full-resolution subject model for photos. GEMINI used to switch only the
        // preview to Gemini polygons (coarse, a few per second) while BackgroundRemovalExportEffect
        // always cut with ML Kit, so the exported video never matched what the user approved.
        // GeminiBackgroundSegmenter stays available for when the export can use it too.
        return if (streamMode) MlKitHumanSegmenter(context, true) else StillImageSegmenter(context)
    }

    fun createApplier(context: Context): GeminiBitmapMaskApplier {
        return GeminiBitmapMaskApplier(context)
    }
}
