package com.termex.replay15.editor.reference

import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.StickerClip
import com.termex.replay15.editor.domain.TextClip

/**
 * Layout slots copied from a reference ("Seu titulo aqui", "Texto do card", "+ Sua imagem") are
 * guides for the user to fill. An unfilled slot is recognised by its untouched content: once the
 * user types a title or swaps the image, it is ordinary content and is exported like any other.
 *
 * Before this, unfilled slots were rendered into the exported video, and a card at the bottom of
 * the frame was even taken for a caption by the Auto Edit ("TEXTO DO CARD" as transcript).
 */
object ReferenceSlots {
    const val TITLE_TEXT = "Seu titulo aqui"
    const val CARD_TEXT = "Texto do card"
    private const val PLACEHOLDER_DIR = "/reference-placeholders/"

    fun isUnfilled(text: TextClip): Boolean = text.text.trim().let {
        it.equals(TITLE_TEXT, ignoreCase = true) || it.equals(CARD_TEXT, ignoreCase = true)
    }

    fun isUnfilled(sticker: StickerClip): Boolean = PLACEHOLDER_DIR in sticker.uri

    fun unfilledCount(project: Project): Int = project.texts.count(::isUnfilled) + project.stickers.count(::isUnfilled)

    /** The project as it should be exported: empty slots left out, everything else untouched. */
    fun withoutUnfilled(project: Project): Project {
        if (unfilledCount(project) == 0) return project
        return project.copy(texts = project.texts.filterNot(::isUnfilled), stickers = project.stickers.filterNot(::isUnfilled))
    }
}
