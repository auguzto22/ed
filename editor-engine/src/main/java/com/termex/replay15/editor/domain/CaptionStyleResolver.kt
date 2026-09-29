package com.termex.replay15.editor.domain

/** Shared font resolution for preview and export. Ordinary text layers remain independent. */
object CaptionStyleResolver {
    fun resolve(project: Project, text: TextClip): TextClip =
        if (text.isCaption) text.copy(fontId = text.captionFontOverride ?: project.captionGlobalFontId)
        else text

    fun applyGlobalFont(project: Project, fontId: String): Project {
        require(fontId.matches(Regex("[a-z0-9_]{1,80}")))
        return project.copy(captionGlobalFontId = fontId)
    }

    fun useGlobalFontForAll(project: Project): Project = project.copy(
        texts = project.texts.map { if (it.isCaption) it.copy(captionFontOverride = null) else it }
    )
}
