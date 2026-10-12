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
            BackgroundRemovalProvider.MLKIT -> MlKitHumanSegmenter(context, streamMode)
            // Automatic mode on video uses the per-pixel on-device model. The Gemini path returns
            // coarse polygons several seconds later and at a few frames per second, which left
            // moving subjects with a blocky, lagging matte (or none at all) in the preview.
            BackgroundRemovalProvider.AUTO -> if (streamMode) MlKitHumanSegmenter(context, true) else gemini(context, streamMode)
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
