package com.termex.replay15.editor.captions

import java.util.Locale

/** Presentation-only cleanup. Never expands slang or supplies words missing from the audio. */
class PtBrTextNormalizer(customVocabulary: Set<String> = emptySet()) {
    private val casing = mapOf(
        "recly" to "Recly", "capcut" to "CapCut", "tiktok" to "TikTok",
        "youtube" to "YouTube", "instagram" to "Instagram", "whatsapp" to "WhatsApp",
        "insta" to "Insta", "podcast" to "podcast", "podcasts" to "podcasts",
        "reels" to "Reels", "shorts" to "Shorts", "discord" to "Discord",
        "twitter" to "Twitter", "twitch" to "Twitch", "telegram" to "Telegram",
        "facebook" to "Facebook", "reddit" to "Reddit", "google" to "Google",
        "chatgpt" to "ChatGPT", "streamer" to "streamer",
        "gameplay" to "gameplay", "download" to "download", "upload" to "upload",
        "fps" to "FPS", "android" to "Android", "iphone" to "iPhone",
        "opengl" to "OpenGL", "glsl" to "GLSL", "kotlin" to "Kotlin"
    ) + customVocabulary.filter { ' ' !in it }.associateBy { it.lowercase(Locale.forLanguageTag("pt-BR")) }

    fun normalize(text: String): String {
        return text.trim().split('\n').mapNotNull { line ->
            val words = line.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (words.isEmpty()) null else {
                val rendered = words.map { word -> casing[word.lowercase(Locale.forLanguageTag("pt-BR"))] ?: word }
                    .joinToString(" ")
                if (words.first().lowercase(Locale.forLanguageTag("pt-BR")) in casing) rendered
                else rendered.replaceFirstChar { it.titlecase(Locale.forLanguageTag("pt-BR")) }
            }
        }.joinToString("\n")
    }
}
