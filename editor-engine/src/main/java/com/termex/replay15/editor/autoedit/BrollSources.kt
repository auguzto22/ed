package com.termex.replay15.editor.autoedit

import com.recly.editor.engine.BuildConfig

/** Image libraries in the order they are tried: Pexels when a key is configured, then Openverse. */
object BrollSources {
    fun default(): List<StockImageSearch> = listOfNotNull(
        BuildConfig.PEXELS_API_KEY.takeIf(String::isNotBlank)?.let(::PexelsImageSearch),
        OpenverseImageSearch(),
    )
}
