package com.termex.replay15.editor.backgroundremoval

import android.content.Context
import com.termex.replay15.editor.ai.GeminiApiKeyConfig
import com.termex.replay15.editor.domain.BackgroundRemovalProvider

/**
 * Factory for creating segmentation engines and mask appliers according to the requested provider.
 */
object SegmentationEngineFactory {

    fun createEngine(
        context: Context,
        streamMode: Boolean,
        provider: BackgroundRemovalProvider = BackgroundRemovalProvider.AUTO,
    ): SegmentationEngine {
        return when (provider) {
            // Photos only need one mask, so they get the full-resolution subject model (with the
            // selfie model as a person guide and fallback) instead of the 256 px live model.
            BackgroundRemovalProvider.MLKIT -> if (streamMode) MlKitHumanSegmenter(context, true) else StillImageSegmenter(context)
            // Automatic mode on video uses the per-pixel on-device model. The Gemini path returns
            // coarse polygons several seconds later and at a few frames per second, which left
            // moving subjects with a blocky, lagging matte (or none at all) in the preview.
            // Gemini polygons were also the automatic choice for photos, which made photo cut-outs
            // look faceted; the per-pixel subject model is used instead.
            BackgroundRemovalProvider.AUTO -> if (streamMode) MlKitHumanSegmenter(context, true) else StillImageSegmenter(context)
            BackgroundRemovalProvider.GEMINI -> gemini(context, streamMode)
        }
    }

    private fun gemini(context: Context, streamMode: Boolean): SegmentationEngine {
        val apiKey = GeminiApiKeyConfig.getApiKey()
        return if (apiKey.isNotBlank()) {
            GeminiBackgroundSegmenter(
                context = context,
                apiKey = apiKey,
                fallbackEngine = MlKitHumanSegmenter(context, streamMode),
            )
        } else {
            MlKitHumanSegmenter(context, streamMode)
        }
    }

    fun createApplier(context: Context): GeminiBitmapMaskApplier {
        return GeminiBitmapMaskApplier(context)
    }
}
