package com.termex.replay15.editor.reference

import com.termex.replay15.editor.autoedit.AutoEditExecutor
import com.termex.replay15.editor.domain.*

/** Applies only normal editor primitives; the returned Project is one ProjectHistory transaction. */
object ReferenceStyleExecutor {
    fun execute(project: Project, clipIndex: Int, plan: ReferenceStyleEditPlan, profile: ReferenceStyleProfile): Project {
        val source = project.videos.getOrNull(clipIndex) ?: error("Clipe nao encontrado")
        val originalTextIds = project.texts.mapTo(hashSetOf()) { it.id }
        var edited = AutoEditExecutor.execute(project, clipIndex, plan.base)
        if (profile.captionStyle.present) {
            val style = profile.captionStyle; val portrait = edited.dimensions().let { it.second > it.first }
            val safeY = style.y.coerceIn(if (portrait) .16f else .1f, if (portrait) .82f else .88f)
            edited = edited.copy(texts = edited.texts.map { text ->
                if (text.id in originalTextIds) text else text.copy(x = style.x.coerceIn(.12f, .88f), y = safeY,
                    size = style.relativeHeight.coerceIn(.025f, .14f), color = style.fillColor, bold = style.bold,
                    italic = style.italic, fontId = style.closestFont.id, outlineColor = style.outlineColor,
                    outlineWidth = if (style.confidence >= .45f) .008f else text.outlineWidth,
                    backgroundColor = if (style.hasBackground) 0x99000000.toInt() else 0, shadow = true, animation = style.animation)
            })
        }
        if (plan.base.cuts.isNotEmpty() && profile.dominantCutType == ReferenceEventType.FADE) {
            val duration = profile.events.filter { it.type == ReferenceEventType.FADE }.map { it.endUs - it.startUs }
                .medianLong().coerceIn(100_000L, 2_000_000L)
            val affected = edited.videos.indices.filter { edited.videos[it].uri == source.uri && edited.videos[it].inUs >= source.inUs && edited.videos[it].outUs <= source.outUs }
            edited = edited.copy(videos = edited.videos.mapIndexed { index, clip ->
                val position = affected.indexOf(index)
                if (position < 0) clip else clip.copy(
                    transitionIn = if (position > 0) ClipTransition.FADE_BLACK else clip.transitionIn,
                    transitionOut = if (position < affected.lastIndex) ClipTransition.FADE_BLACK else clip.transitionOut,
                    transitionDurationUs = duration,
                )
            })
        }
        // A speed curve changes global timing. Apply automatically only when no other timed content can be displaced.
        val speedSafe = plan.speedChanges.isNotEmpty() && plan.base.cuts.isEmpty() && project.videos.size == 1 &&
            project.audio.isEmpty() && project.texts.isEmpty() && project.stickers.isEmpty() && project.videoTracks.isEmpty()
        if (speedSafe) {
            val clip = edited.videos.singleOrNull { it.id == source.id }
            if (clip != null) {
                val points = mutableListOf(SpeedPoint(clip.inUs, 1f), SpeedPoint(clip.outUs, 1f))
                plan.speedChanges.forEach { ramp ->
                    val center = clip.timeMap.sourceAt(ramp.timeUs.coerceIn(0, clip.durationUs))
                    val half = (ramp.durationUs / 2).coerceAtLeast(50_000L)
                    points += SpeedPoint((center - half).coerceIn(clip.inUs, clip.outUs), 1f)
                    points += SpeedPoint(center.coerceIn(clip.inUs, clip.outUs), ramp.peakSpeed.coerceIn(.1f, 16f))
                    points += SpeedPoint((center + half).coerceIn(clip.inUs, clip.outUs), 1f)
                }
                val bounded = points.groupBy { it.sourceUs }.map { (_, sameTime) -> sameTime.maxBy { it.speed } }.sortedBy { it.sourceUs }.take(32)
                edited = edited.copy(videos = edited.videos.map { if (it.id == clip.id) it.copy(speedCurve = bounded, preservePitch = true) else it })
            }
        }
        return edited
    }
}
