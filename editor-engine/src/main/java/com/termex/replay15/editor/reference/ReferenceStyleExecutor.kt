package com.termex.replay15.editor.reference

import com.termex.replay15.editor.autoedit.AutoEditExecutor
import com.termex.replay15.editor.domain.*

/** Applies only normal editor primitives; the returned Project is one ProjectHistory transaction. */
object ReferenceStyleExecutor {
    /**
     * @param placeholder returns the URI of a neutral "drop your image here" picture with the given
     * width/height ratio, or null when none can be made (image slots then become timeline markers only).
     */
    fun execute(project: Project, clipIndex: Int, plan: ReferenceStyleEditPlan, profile: ReferenceStyleProfile,
        placeholder: ((aspect: Float) -> String?)? = null): Project {
        val source = project.videos.getOrNull(clipIndex) ?: error("Clipe nao encontrado")
        val clipStart = project.startOf(clipIndex)
        // Captions already on the clip are restyled too: generated captions are kept (not
        // recreated) by the Auto Edit executor, so "new texts only" would skip them.
        val originalTextIds = project.texts.filterNot { com.termex.replay15.editor.autoedit.ClipAnalyzer.isCaptionOf(it, clipStart, clipStart + source.durationUs) }
            .mapTo(hashSetOf()) { it.id }
        var edited = AutoEditExecutor.execute(project, clipIndex, plan.base)
        if (profile.captionStyle.present) {
            val style = profile.captionStyle; val portrait = edited.dimensions().let { it.second > it.first }
            val safeY = style.y.coerceIn(if (portrait) .16f else .1f, if (portrait) .82f else .88f)
            edited = edited.copy(texts = edited.texts.map { text ->
                if (text.id in originalTextIds) text else text.copy(x = style.x.coerceIn(.12f, .88f), y = safeY,
                    size = style.relativeHeight.coerceIn(.025f, .14f), color = style.fillColor, bold = style.bold,
                    italic = style.italic, fontId = style.closestFont.id, outlineColor = style.outlineColor,
                    outlineWidth = if (style.confidence >= .45f) .008f else text.outlineWidth,
                    backgroundColor = if (style.hasBackground) 0x99000000.toInt() else 0, shadow = true, animation = style.animation,
                    wordStyle = if (style.uppercase) text.wordStyle.copy(uppercase = true) else text.wordStyle)
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
        return applyLayout(project, edited, clipIndex, plan.layout, placeholder)
    }

    /** Layout slots become ordinary text, sticker and marker items over the edited clip span. */
    internal fun applyLayout(original: Project, edited: Project, clipIndex: Int, layout: List<ReferenceLayoutDecision>,
        placeholder: ((Float) -> String?)?): Project {
        if (layout.isEmpty()) return edited
        val pieces = (edited.videos.size - original.videos.size + 1).coerceAtLeast(1)
        val spanStart = edited.startOf(clipIndex)
        val spanDuration = edited.videos.subList(clipIndex, (clipIndex + pieces).coerceAtMost(edited.videos.size)).sumOf { it.durationUs }
        if (spanDuration < MIN_TEXT_DURATION_US) return edited
        val (canvasW, canvasH) = edited.dimensions()
        fun at(fraction: Float) = spanStart + (spanDuration * fraction.toDouble()).toLong()
        val texts = mutableListOf<TextClip>(); val stickers = mutableListOf<StickerClip>(); val markers = mutableListOf<TimelineMarker>()
        layout.forEach { slot ->
            val start = at(slot.start)
            val end = maxOf(at(slot.end), start + MIN_STICKER_DURATION_US).coerceAtMost(spanStart + spanDuration)
            if (end - start < MIN_TEXT_DURATION_US) return@forEach
            val box = slot.box.clamped()
            when (slot.kind) {
                ReferenceLayoutKind.TITLE, ReferenceLayoutKind.CARD -> {
                    val card = slot.kind == ReferenceLayoutKind.CARD
                    val background = when { slot.style.backgroundColor != 0 -> slot.style.backgroundColor; card -> 0xF2FFFFFF.toInt(); else -> 0 }
                    val color = if (card && slot.style.backgroundColor == 0) 0xFF111111.toInt() else slot.style.textColor
                    val text = if (card) "Texto do card" else "Seu titulo aqui"
                    if (edited.texts.size + texts.size < MAX_TEXTS) texts += TextClip(
                        text = if (slot.style.uppercase) text.uppercase() else text, startUs = start, endUs = end,
                        x = box.x.coerceIn(0f, 1f), y = box.y.coerceIn(0f, 1f),
                        size = (if (card) box.height * .35f else box.height * .8f).coerceIn(.03f, .1f),
                        color = color, bold = slot.style.bold,
                        fontId = ReferenceGeminiParser.textFont(slot.style.font, slot.style.bold).id,
                        boxWidth = if (box.width >= .2f) box.width.coerceIn(.2f, .95f) else 0f,
                        backgroundColor = background, outlineWidth = if (slot.style.outline && background == 0) .008f else 0f,
                        shadow = background == 0, animation = TextAnimation.POP,
                    )
                }
                ReferenceLayoutKind.OVERLAY_IMAGE, ReferenceLayoutKind.STICKER, ReferenceLayoutKind.BROLL -> {
                    val broll = slot.kind == ReferenceLayoutKind.BROLL
                    val target = if (broll) LayoutBox.FULL else box
                    val aspect = (target.width * canvasW / (target.height * canvasH).coerceAtLeast(1f)).coerceIn(.1f, 10f)
                    val uri = placeholder?.invoke(aspect)
                    if (uri != null && edited.stickers.size + stickers.size < 24) stickers += StickerClip(
                        uri = uri, name = if (broll) "Espaco: B-roll" else "Espaco: imagem", startUs = start, endUs = end,
                        x = target.x.coerceIn(0f, 1f), y = target.y.coerceIn(0f, 1f), size = target.height.coerceIn(.03f, 1.5f),
                        opacity = if (broll) 1f else .9f,
                    )
                    markers += TimelineMarker(timeUs = start, name = if (broll) "B-roll aqui" else "Imagem aqui")
                }
                ReferenceLayoutKind.CAPTION -> Unit
            }
        }
        return edited.copy(
            texts = (edited.texts + texts).sortedBy { it.startUs }.take(MAX_TEXTS),
            stickers = edited.stickers + stickers,
            markers = (edited.markers + markers).sortedBy { it.timeUs }.take(500),
        )
    }
}
