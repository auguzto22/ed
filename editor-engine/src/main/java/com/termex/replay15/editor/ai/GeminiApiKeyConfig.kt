package com.termex.replay15.editor.ai

import com.recly.editor.engine.BuildConfig

/**
 * Central configuration for Gemini API keys.
 * Uses BuildConfig key if available, otherwise falls back to configured default.
 */
object GeminiApiKeyConfig {
    const val DEFAULT_KEY: String = ""

    fun getApiKey(): String {
        return BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() } ?: DEFAULT_KEY
    }
}
