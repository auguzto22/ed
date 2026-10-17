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

    /**
     * Perfil do aparelho para a exportação. A exportação é offline: não desce de qualidade por
     * temperatura nem pelo nível da prévia. O `highp` real é conferido na thread GL pelo próprio
     * efeito ([MotionRetimeEffect]), que cai para mistura se faltar.
     */
    private fun exportDevice(context: android.content.Context): com.termex.replay15.editor.speed.DeviceMotionProfile {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        return com.termex.replay15.editor.speed.DeviceMotionProfile(
            memoryClassMb = am?.memoryClass ?: 256, lowRamDevice = am?.isLowRamDevice ?: false,
            highpFragmentShaders = true, maxTextureSize = 4096,
        )
    }

    fun build(context: android.content.Context, project: Project): Composition {
        require(project.allVideos.isNotEmpty()) { "Adicione um video ou foto" }
        val motionContext = if (project.export.motionQuality == com.termex.replay15.editor.speed.MotionExportQuality.ECONOMY)
            com.termex.replay15.editor.speed.MotionContext.EXPORT_ECONOMY else com.termex.replay15.editor.speed.MotionContext.EXPORT_HIGH
        val device = exportDevice(context)
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
            // Mask first, on the untransformed frame: the preview masks the source texture before
            // crop/rotation/flip, and the mask, its editor and motion tracking share that space.
            if (clip.mask != null) effects += LayerMaskEffect(clip, placed.startUs)
            if (clip.crop != CropRect()) effects += Crop(2 * clip.crop.left - 1, 2 * clip.crop.right - 1,
                1 - 2 * clip.crop.bottom, 1 - 2 * clip.crop.top)
            if (clip.rotation != 0 || clip.flip) effects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(clip.rotation.toFloat()).setScale(if (clip.flip) -1f else 1f, 1f).build()
            if (clip.backgroundRemoval.enabled && clip.backgroundRemoval.mode == BackgroundMode.REMOVE) {
                effects += BackgroundRemovalExportEffect(clip.backgroundRemoval, stillImage = clip.image,
                    targetWidth = width, targetHeight = height, fill = project.canvasFill == CanvasFill.FILL)
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
            // Background removal runs first, on the untouched source frame, as in the preview
            // (GpuPreviewRenderer removes the background before effects, mask and crop). Added
            // after the layer mask, crop and rotation, the model saw a different picture (cropped,
            // masked-out areas, rotated) and the exported cut-out did not match the preview.
            effects.indexOfFirst { it is BackgroundRemovalExportEffect }.takeIf { it > 0 }
                ?.let { index -> effects.add(0, effects.removeAt(index)) }
            // Movimento (câmera lenta suave, blur por vetores, acumulação): logo depois da remoção
            // de fundo, que segmenta só os quadros reais; tudo depois recebe os quadros novos.
            val motionPlan = if (clip.image) null else com.termex.replay15.editor.speed.MotionPolicy.plan(clip, motionContext, device, project.export.fps.toFloat())
            val accumulation = motionPlan != null && clip.motionBlur.enabled && motionPlan.blur != com.termex.replay15.editor.speed.BlurMethod.LAYER_ANIMATION &&
                clip.timeMap.speedRange().endInclusive * clip.fps > project.export.fps * 1.5f
            if (motionPlan != null && (!motionPlan.isPassThrough || accumulation)) {
                val exposure = if (motionPlan.blur == com.termex.replay15.editor.speed.BlurMethod.LAYER_ANIMATION) 0f else clip.motionBlur.exposureFraction
                val index = if (effects.firstOrNull() is BackgroundRemovalExportEffect) 1 else 0
                effects.add(index, MotionRetimeEffect(motionPlan, project.export.fps, exposure))
            }
            if (motionPlan?.blur == com.termex.replay15.editor.speed.BlurMethod.LAYER_ANIMATION) {
                com.termex.replay15.editor.motion.MotionBlurEngine.layerEffect(clip, clip.motionBlur, project.export.fps)
                    ?.let { blur -> effects += PackageEffect(blur, clip, placed.startUs) } // junto dos efeitos do pacote
            }
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
            // Same envelope as preview: the outgoing clip is not faded across the whole transition
            // (that left a silent dip at every cut), only a short tail to avoid a click.
            val audioFadeOut = maxOf(clip.audioFadeOutUs, transOut?.durationUs?.coerceAtMost(TRANSITION_AUDIO_TAIL_US) ?: 0L)
            // "Silenciar" no painel de velocidade: ganho zero (o trilho de áudio continua existindo,
            // então a sequência e a sincronização não mudam).
            val retimed = clip.speed != 1f || clip.speedCurve.isNotEmpty()
            val volume = if (clip.retimedAudioMuted && retimed) 0f else clip.volume
            val audioEffects = gain(volume, audioFadeIn, audioFadeOut, clip.durationUs) +
                if (clip.enhance.isNeutral) emptyList() else listOf(AudioEnhanceProcessorEffect(clip.enhance))
            val item = EditedMediaItem.Builder(media.build())
                .setDurationUs((if (clip.image) clip.outUs - clip.inUs else clip.sourceUs).coerceAtLeast(1000L))
                .setRemoveAudio(!project.audioEnabled(layer.id))
                // O limite de quadros descarta quadros antes dos efeitos. Com acumulação temporal o
                // efeito de movimento precisa dos quadros do trecho acelerado (até 8 por saída).
                .setFrameRate(if (accumulation) project.export.fps * 8 else project.export.fps)
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
