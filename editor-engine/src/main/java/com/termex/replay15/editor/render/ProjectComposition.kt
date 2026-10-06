package com.termex.replay15.editor.render

import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.*
import androidx.media3.effect.*
import androidx.media3.effect.HslAdjustment
import androidx.media3.transformer.*
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.core.isEditorDebuggable
import java.util.concurrent.atomic.AtomicLong

/** Builds the offline export composition. Interactive preview has its own decoder/GL engine. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
object ProjectComposition {
    private val nextTransitionSessionId = AtomicLong()

    private class TransitionMatrix(
        private val input: ClipTransition,
        private val output: ClipTransition,
        private val fadeUs: Long,
        private val durationUs: Long,
        private val startUs: Long,
    ) : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            val fade = fadeUs.coerceAtMost(durationUs / 2).coerceAtLeast(1)
            val projectTimeUs = if (presentationTimeUs in startUs..(startUs + durationUs)) {
                presentationTimeUs
            } else {
                startUs + presentationTimeUs
            }
            val localUs = (projectTimeUs - startUs).coerceIn(0L, durationUs)
            val entering = if (input == ClipTransition.NONE) 1f else (localUs / fade.toFloat()).coerceIn(0f, 1f)
            val leaving = if (output == ClipTransition.NONE) 1f else ((durationUs - localUs) / fade.toFloat()).coerceIn(0f, 1f)
            val amount = minOf(entering, leaving)
            val white = (input == ClipTransition.FADE_WHITE && entering <= leaving) ||
                (output == ClipTransition.FADE_WHITE && leaving < entering)
            val offset = if (white) 1f - amount else 0f
            return floatArrayOf(
                amount, 0f, 0f, 0f,
                0f, amount, 0f, 0f,
                0f, 0f, amount, 0f,
                offset, offset, offset, 1f,
            )
        }
        override fun isNoOp(inputWidth: Int, inputHeight: Int) = input == ClipTransition.NONE && output == ClipTransition.NONE
    }

    private class AdjustableInvert(private val strength: Float) : RgbMatrix {
        private val matrix = floatArrayOf(
            1f - 2f * strength, 0f, 0f, 0f,
            0f, 1f - 2f * strength, 0f, 0f,
            0f, 0f, 1f - 2f * strength, 0f,
            strength, strength, strength, 1f,
        )
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean) = matrix
        override fun isNoOp(inputWidth: Int, inputHeight: Int) = strength == 0f
    }

    private fun gain(volume: Float, fadeInUs: Long = 0, fadeOutUs: Long = 0, durationUs: Long = 0): List<AudioProcessor> {
        if ((fadeInUs > 0 || fadeOutUs > 0) && durationUs > 0) {
            val maximum = durationUs / 2
            val builder = DefaultGainProvider.Builder(volume)
            if (fadeInUs > 0) builder.addFadeAt(0, fadeInUs.coerceAtMost(maximum), DefaultGainProvider.FADE_IN_EQUAL_POWER)
            if (fadeOutUs > 0) {
                val length = fadeOutUs.coerceAtMost(maximum)
                builder.addFadeAt(durationUs - length, length, DefaultGainProvider.FADE_OUT_EQUAL_POWER)
            }
            return listOf(GainProcessor(builder.build()))
        }
        if (volume == 1f) return emptyList()
        return listOf(ChannelMixingAudioProcessor().apply {
            for (channels in 1..8) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, channels).scaleBy(volume))
        })
    }

    fun build(context: android.content.Context, project: Project): Composition {
        require(project.allVideos.isNotEmpty()) { "Adicione um video ou foto" }
        val (width, height) = project.dimensions(project.export.shortSide)
        val layers = RenderPlan.layers(project)
        val transitionSessionId = nextTransitionSessionId.incrementAndGet()
        TransitionBridge.clearStaleSessions(transitionSessionId)
        val transitionBridgeKeys = project.transitions.associate { transition ->
            val key = "$transitionSessionId:${transition.id}"
            TransitionBridge.retain(key)
            transition.id to key
        }
        val sequences = layers.map { layer ->
          val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
          var endUs = 0L
          layer.clips.forEach { placed ->
            val gapUs = RenderPlan.gapBefore(endUs, placed)
            if (gapUs > 0L) sequence.addGap(gapUs)
            val clip = placed.clip
            if (clip.isNullObject) {
                sequence.addGap(clip.durationUs)
                endUs = placed.endUs
                return@forEach
            }
            val media = MediaItem.Builder().setUri(clip.uri)
            if (clip.image) media.setImageDurationMs((clip.outUs - clip.inUs) / 1000)
            else media.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionUs(clip.inUs).setEndPositionUs(clip.outUs).build())
            val effects = mutableListOf<Effect>()
            if (clip.crop != CropRect()) effects += Crop(2 * clip.crop.left - 1, 2 * clip.crop.right - 1,
                1 - 2 * clip.crop.bottom, 1 - 2 * clip.crop.top)
            if (clip.rotation != 0 || clip.flip) effects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(clip.rotation.toFloat()).setScale(if (clip.flip) -1f else 1f, 1f).build()
            if (clip.backgroundRemoval.enabled && clip.backgroundRemoval.mode == BackgroundMode.REMOVE) {
                effects += BackgroundRemovalExportEffect(clip.backgroundRemoval)
            }
            if (clip.brightness != 0f) effects += Brightness(clip.brightness)
            if (clip.contrast != 0f) effects += Contrast(clip.contrast)
            if (clip.saturation != 0f || clip.hue != 0f || clip.lightness != 0f) effects += HslAdjustment.Builder()
                .adjustSaturation(clip.saturation).adjustHue(clip.hue).adjustLightness(clip.lightness).build()
            if (clip.temperature != 0f) effects += RgbAdjustment.Builder()
                .setRedScale(1f + clip.temperature * .18f)
                .setGreenScale(1f + clip.temperature * .03f)
                .setBlueScale(1f - clip.temperature * .18f).build()
            if (clip.blur > 0f) effects += GaussianBlur(clip.blur)
            if (clip.needsStudioEffect()) {
                effects += StudioEffect(clip, project.backgroundColor, placed.startUs, preserveAlpha = true)
            }
            clip.effects.filter {
                it.enabled && (it.intensity > 0 ||
                    it.keyframes[com.termex.replay15.editor.assets.EffectInstance.INTENSITY].orEmpty().any { key -> key.value > 0 })
            }
                .forEach { effects += PackageEffect(it, clip, placed.startUs) }
            if (clip.mask != null) effects += LayerMaskEffect(clip, placed.startUs)
            effects += Presentation.createForWidthAndHeight(width, height, if (project.canvasFill == CanvasFill.FILL)
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT)
            if (
                clip.zoom != 1f ||
                clip.fineRotation != 0f ||
                clip.offsetX != 0f ||
                clip.offsetY != 0f ||
                clip.motion != ClipMotion.NONE ||
                clip.keyframes.isNotEmpty() ||
                clip.is3D ||
                clip.transform3D != Transform3D.DEFAULT ||
                clip.parentId != null
            ) {
                effects += MotionMatrix3D(clip, placed.startUs, project, applyProjectCamera = false)
            }
            if (clip.transitionIn != ClipTransition.NONE || clip.transitionOut != ClipTransition.NONE) effects += TransitionMatrix(
                clip.transitionIn, clip.transitionOut, clip.transitionDurationUs, clip.durationUs, placed.startUs,
            )
            val transOut = project.transitions.firstOrNull { it.leftClipId == clip.id }
            if (transOut != null) {
                effects += TransitionCaptureEffect(
                    transOut.id,
                    transitionBridgeKeys.getValue(transOut.id),
                    transOut.startUs(project),
                    clip.durationUs,
                )
            }
            val transIn = project.transitions.firstOrNull { it.rightClipId == clip.id }
            if (transIn != null) {
                val def = com.termex.replay15.editor.assets.TransitionCatalog.findById(context, transIn.transitionId)
                    ?: com.termex.replay15.editor.assets.BuiltInTransitions.definitions(context).first()
                effects += TransitionRenderEffect(
                    transIn,
                    def,
                    transitionBridgeKeys.getValue(transIn.id),
                    transIn.startUs(project),
                    transIn.durationUs,
                )
            }
            val audioFadeIn = maxOf(clip.audioFadeInUs, transIn?.durationUs ?: 0L)
            val audioFadeOut = maxOf(clip.audioFadeOutUs, transOut?.durationUs ?: 0L)
            val audioEffects = gain(clip.volume, audioFadeIn, audioFadeOut, clip.durationUs) +
                if (clip.enhance.isNeutral) emptyList() else listOf(AudioEnhanceProcessorEffect(clip.enhance))
            val item = EditedMediaItem.Builder(media.build())
                .setDurationUs((if (clip.image) clip.outUs - clip.inUs else clip.sourceUs).coerceAtLeast(1000L))
                .setRemoveAudio(!project.audioEnabled(layer.id))
                .setFrameRate(project.export.fps)
                .setEffects(Effects(audioEffects, effects))
                .setSpeed(androidx.media3.common.SpeedParameters(ClipSpeedProvider(clip), clip.preservePitch)).build()
            sequence.addItem(item); endUs = placed.endUs
          }
          if (endUs < project.durationUs) sequence.addGap(project.durationUs - endUs)
          sequence.build()
        }.toMutableList()
        // An opaque bottom input avoids double alpha application after Media3's compositor.
        // It also keeps track visibility/compositor behavior identical for one or many videos.
        val canvas = EditedMediaItem.Builder(MediaItem.Builder().setUri(CanvasSource.uri(context, project.backgroundColor))
            .setImageDurationMs((project.durationUs + 999) / 1000).build())
            .setDurationUs(project.durationUs.coerceAtLeast(1000L)).setFrameRate(project.export.fps).setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_STRETCH_TO_FIT))))
            .build()
        sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(canvas).build()
        project.audio.filter { it.startUs < project.durationUs && project.audioEnabled("audio:${it.id}") }.forEach { audio ->
            val end = minOf(audio.outUs, audio.inUs + project.durationUs - audio.startUs)
            val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(audio.uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(audio.inUs).setEndPositionUs(end).build()).build())
                .setDurationUs(audio.sourceUs.coerceAtLeast(1000L)).setRemoveVideo(true)
                .setEffects(Effects(
                    gain(audio.volume, audio.fadeInUs, audio.fadeOutUs, end - audio.inUs) +
                        if (audio.enhance.isNeutral) emptyList() else listOf(AudioEnhanceProcessorEffect(audio.enhance)),
                    emptyList())).build()
            val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            if (audio.startUs > 0) sequence.addGap(audio.startUs)
            sequence.addItem(item)
            val remaining = project.durationUs - audio.startUs - (end - audio.inUs)
            if (remaining > 0) sequence.addGap(remaining)
            sequences += sequence.build()
        }
        val effects = mutableListOf<Effect>()
        project.adjustmentClips.filter { it.enabled }.sortedBy { it.startUs }.forEach { adjustment ->
            adjustment.effects.filter { it.enabled }.forEach { effect ->
                val ranged = effect.copy(
                    startTimeUs = maxOf(adjustment.startUs, effect.startTimeUs ?: adjustment.startUs),
                    endTimeUs = minOf(adjustment.endUs, effect.endTimeUs ?: adjustment.endUs),
                )
                if (ranged.endTimeUs!! > ranged.startTimeUs!!) {
                    effects += PackageEffect(ranged, null, 0L, project.durationUs)
                }
            }
        }
        val texts = project.texts.takeIf { project.visualEnabled(TEXT_TRACK) }
            ?.map { CaptionStyleResolver.resolve(project, it) } ?: emptyList()
        val stickers = project.stickers.takeIf { project.visualEnabled(STICKER_TRACK) } ?: emptyList()
        if (texts.isNotEmpty() || stickers.isNotEmpty()) {
            effects += OverlayEffect(listOf(TextCanvasOverlay(context, texts, stickers, width, height)))
        }
        if (!project.camera.isDefault) effects += SceneCameraEffect(project.camera, project.backgroundColor)
        // This limits output cadence; it never fabricates interpolated frames.
        effects += FrameDropEffect.createDefaultFrameDropEffect(project.export.fps.toFloat())
        return Composition.Builder(sequences).setEffects(Effects(emptyList(), effects))
            .setVideoCompositorSettings(TrackCompositor(project, layers, width, height, transitionBridgeKeys, context.isEditorDebuggable()))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
    }
}
