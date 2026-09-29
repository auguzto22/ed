package com.termex.replay15.editor.preview.engine

import com.termex.replay15.editor.assets.EffectInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.transform.LayerStateEvaluator

/** Immutable, GPU-facing state. The renderer never traverses Project. */
data class PreviewRenderState(
    val generation: Long,
    val projectRevision: Long,
    val projectTimeUs: Long,
    val width: Int,
    val height: Int,
    val backgroundColor: Int,
    val canvasFill: CanvasFill,
    val layers: List<Layer>,
    val transition: Transition?,
    val textLayers: List<TextRenderState>,
    val textIds: Set<String>,
    val stickers: List<StickerClip>,
    val camera: Camera3D = Camera3D(),
) {
    val timeUs: Long get() = projectTimeUs

    data class TextRenderState(
        val id: String,
        val text: String,
        val startUs: Long,
        val endUs: Long,
        val x: Float,
        val y: Float,
        val scale: Float,
        val rotation: Float,
        val opacity: Float,
        val color: Int,
        val fontId: String,
        val fontSize: Float,
        val visible: Boolean,
        val contentRevision: Long,
        val mask: MaskState?,
        val source: TextClip,
    )
    data class Layer(
        val key: ActiveClipResolver.ClipKey,
        val sourceTimeUs: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val image: Boolean,
        val uri: String,
        val zoom: Float,
        val x: Float,
        val y: Float,
        val rotation: Float,
        val fixedRotation: Int,
        val flip: Boolean,
        val opacity: Float,
        val crop: CropRect,
        val filter: VideoFilter,
        val filterStrength: Float,
        val brightness: Float,
        val contrast: Float,
        val saturation: Float,
        val hue: Float,
        val lightness: Float,
        val temperature: Float,
        val grade: StudioGrade,
        val effects: List<Effect>,
        val backgroundRemoval: BackgroundRemovalEffect,
        val mask: MaskState?,
    )
    data class Effect(
        val id: String,
        val assetId: String,
        val version: Int,
        val intensity: Float,
        val parameters: Map<String, Float>,
        val blendMode: Int,
        val maskShape: Int,
        val maskX: Float,
        val maskY: Float,
        val maskSize: Float,
        val maskAspect: Float,
        val maskRotation: Float,
        val maskFeather: Float,
        val maskInvert: Boolean,
        val maskOpacity: Float,
    )
    data class Transition(
        val id: String,
        val assetId: String,
        val left: ActiveClipResolver.ClipKey,
        val right: ActiveClipResolver.ClipKey,
        val progress: Float,
        val parameters: Map<String, Float>,
        val startUs: Long = 0,
        val endUs: Long = 0,
    )
}

object RenderStateEvaluator {
    fun evaluate(snapshot: ActiveClipResolver.Snapshot, project: Project, generation: Long, projectRevision: Long, width: Int, height: Int): PreviewRenderState {
        val layers = snapshot.videos.map { placed ->
            val clip = placed.clip; val sourceUs = placed.sourceTimeUs(snapshot.projectTimeUs); val transform = clip.transformAt(sourceUs)
            PreviewRenderState.Layer(placed.key, sourceUs, clip.width, clip.height, clip.image, clip.effectivePreviewUri,
                transform.zoom, transform.x, transform.y, transform.rotation, clip.rotation, clip.flip, transform.opacity,
                clip.crop, VideoFilter.entries[clip.filter], clip.filterStrength, clip.brightness, clip.contrast,
                clip.saturation, clip.hue, clip.lightness, clip.temperature, clip.grade,
                (clip.effects.filter { it.activeAt(sourceUs) }.map { it.toRenderEffect(sourceUs) } +
                    snapshot.adjustments.flatMap { adjustment -> adjustment.effects.filter { it.enabled && it.activeAt(snapshot.projectTimeUs) }
                        .map { it.toRenderEffect(snapshot.projectTimeUs) } }),
                clip.backgroundRemoval, clip.maskAt(sourceUs))
        }
        val transition = snapshot.transitions.firstOrNull()?.let { active ->
            val left = layers.firstOrNull { it.key.clipId == active.instance.leftClipId }
            val right = layers.firstOrNull { it.key.clipId == active.instance.rightClipId }
            if (left == null || right == null) null else PreviewRenderState.Transition(active.instance.id,
                active.instance.transitionId, left.key, right.key, active.progress, active.instance.parameters, active.startUs, active.endUs)
        }
        val texts = project.texts.filter { project.visualEnabled(TEXT_TRACK) }
            .map { CaptionStyleResolver.resolve(project, it) }.mapNotNull { text ->
            val timing = com.termex.replay15.editor.transform.RealtimeProjectState.timing(text.id)
            val startUs = timing?.startUs ?: text.startUs
            val endUs = timing?.endUs ?: text.endUs
            val evaluated = LayerStateEvaluator.evaluate(text, snapshot.projectTimeUs)
            if (!evaluated.visible) return@mapNotNull null
            val transform = evaluated.transform
            val wordState = if (text.wordCues.isEmpty()) 0L else {
                var activeIdx = -1
                var spokenCount = 0
                for (i in text.wordCues.indices) {
                    val cue = text.wordCues[i]
                    if (snapshot.projectTimeUs in cue.startUs until cue.endUs) activeIdx = i
                    if (snapshot.projectTimeUs >= cue.endUs) spokenCount++
                }
                ((activeIdx + 1).toLong() shl 16) or spokenCount.toLong()
            }
            PreviewRenderState.TextRenderState(
                text.id, text.text, startUs, endUs,
                transform.x, transform.y, transform.scaleX, transform.rotation,
                transform.opacity, text.color, text.fontId, text.size, evaluated.visible,
                (31L * text.copy(startUs = 0L, endUs = 1L, x = .5f, y = .5f, rotation = 0f, opacity = 1f, scale = 1f,
                    animation = TextAnimation.NONE, transformKeyframes = emptyList(), wordCues = emptyList(), mask = null).hashCode() + wordState).toLong(),
                text.maskAt(snapshot.projectTimeUs), text)
        }
        val stickers = project.stickers.filter { project.visualEnabled(STICKER_TRACK) }.mapNotNull { sticker ->
            val timing = com.termex.replay15.editor.transform.RealtimeProjectState.timing(sticker.id)
            val startUs = timing?.startUs ?: sticker.startUs
            val endUs = timing?.endUs ?: sticker.endUs
            val evaluated = LayerStateEvaluator.evaluate(sticker, snapshot.projectTimeUs)
            if (!evaluated.visible) return@mapNotNull null
            val transform = evaluated.transform
            sticker.copy(startUs = startUs, endUs = endUs, x = transform.x, y = transform.y,
                scale = transform.scaleX, rotation = transform.rotation, opacity = transform.opacity,
                transformKeyframes = emptyList())
        }
        return PreviewRenderState(generation, projectRevision, snapshot.projectTimeUs, width.coerceAtLeast(1), height.coerceAtLeast(1),
            project.backgroundColor, project.canvasFill, layers, transition, texts, project.texts.mapTo(linkedSetOf()) { it.id }, stickers, project.camera.cameraAt(snapshot.projectTimeUs))
    }

    private fun EffectInstance.toRenderEffect(sourceUs: Long) = PreviewRenderState.Effect(
        id, assetId, version,
        (valueAt(EffectInstance.INTENSITY, sourceUs, intensity) * groupIntensity).coerceIn(0f, 1f),
        values.mapValues { (name, value) -> valueAt(name, sourceUs, value) }, blendMode.ordinal,
        mask?.shape?.ordinal ?: 0,
        mask?.let { valueAt(EffectInstance.MASK_X, sourceUs, it.x) } ?: 0f,
        mask?.let { valueAt(EffectInstance.MASK_Y, sourceUs, it.y) } ?: 0f,
        mask?.let { valueAt(EffectInstance.MASK_SIZE, sourceUs, it.size) } ?: .75f,
        mask?.let { valueAt(EffectInstance.MASK_ASPECT, sourceUs, it.aspect) } ?: 1f,
        mask?.let { valueAt(EffectInstance.MASK_ROTATION, sourceUs, it.rotation) } ?: 0f,
        mask?.let { valueAt(EffectInstance.MASK_FEATHER, sourceUs, it.feather) } ?: .08f,
        mask?.invert ?: false,
        mask?.let { valueAt(EffectInstance.MASK_OPACITY, sourceUs, it.opacity) } ?: 1f,
    )
}
