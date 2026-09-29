package com.termex.replay15.editor.preview.engine

import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.TEXT_TRACK
import com.termex.replay15.editor.domain.TextClip
import com.termex.replay15.editor.domain.visualEnabled
import com.termex.replay15.editor.transform.LayerStateEvaluator

object ActiveTextResolver {
    fun activeTexts(project: Project, projectTimeUs: Long): List<TextClip> {
        if (!project.visualEnabled(TEXT_TRACK)) return emptyList()
        return project.texts.filter { LayerStateEvaluator.isActive(it, projectTimeUs) }
    }
}
