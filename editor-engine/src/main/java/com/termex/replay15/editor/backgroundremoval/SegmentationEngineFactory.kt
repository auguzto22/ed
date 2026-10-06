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
            BackgroundRemovalProvider.GEMINI,
            BackgroundRemovalProvider.AUTO -> {
                val apiKey = GeminiApiKeyConfig.getApiKey()
                if (apiKey.isNotBlank()) {
                    GeminiBackgroundSegmenter(
                        context = context,
                        apiKey = apiKey,
                        fallbackEngine = MlKitHumanSegmenter(context, streamMode),
                    )
                } else {
                    MlKitHumanSegmenter(context, streamMode)
                }
            }
        }
    }

    fun createApplier(context: Context): GeminiBitmapMaskApplier {
        return GeminiBitmapMaskApplier(context)
    }
}
