package com.termex.replay15.editor.domain

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.audio.AudioEnhance
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToLong

fun newId(): String = UUID.randomUUID().toString()
const val SECOND = 1_000_000L
const val MIN_CLIP = 100_000L

data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    init { require(left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right - left >= .05f && bottom - top >= .05f) }
}

enum class VideoFilter(
    val label: String,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val hue: Float = 0f,
    val lightness: Float = 0f,
    val red: Float = 1f,
    val green: Float = 1f,
    val blue: Float = 1f,
) {
    ORIGINAL("Original"),
    MONO("Preto e branco"),
    NEGATIVE("Negativo"),
    CINEMA("Cinema", -.03f, .12f, 10f, 0f, -4f, 1.06f, .99f, .92f),
    VIBRANT("Vibrante", 0f, .10f, 35f),
    WARM("Quente", 0f, 0f, 5f, 0f, 3f, 1.12f, 1.03f, .86f),
    COOL("Frio", 0f, 0f, 3f, 0f, 2f, .88f, 1.02f, 1.14f),
    VINTAGE("Vintage", .03f, -.12f, -30f, -5f, 8f, 1.08f, 1f, .9f),
    GOLDEN("Golden hour", .06f, .08f, 18f, -5f, 8f, 1.15f, 1.04f, .8f),
    ROSE("Rose", .04f, -.02f, 8f, -8f, 7f, 1.14f, .94f, 1.03f),
    TEAL_ORANGE("Teal & Orange", -.03f, .16f, 25f, -6f, -2f, 1.1f, .98f, 1.08f),
    NIGHT("Noite", -.12f, .2f, -8f, -5f, -12f, .82f, .93f, 1.15f),
    FADE("Fade", .09f, -.22f, -20f, 0f, 10f),
    MATTE("Matte", .05f, -.18f, -5f, 0f, 4f),
    RETRO("Retro", .06f, .03f, -18f, 10f, 8f, 1.1f, .96f, .82f),
    DREAM("Sonho", .12f, -.12f, -12f, -8f, 12f, 1.06f, .97f, 1.08f),
    TROPICAL("Tropical", .05f, .12f, 40f, -3f, 8f, .98f, 1.08f, 1.03f),
    URBAN("Urbano", -.06f, .2f, -25f, 0f, -6f, .95f, .98f, 1.05f),
    FOOD("Comida", .08f, .16f, 32f, -3f, 7f, 1.12f, 1.05f, .92f),
    PORTRAIT("Retrato", .06f, .06f, -5f, -5f, 8f, 1.08f, .99f, .98f),
    AUTUMN("Outono", .04f, .12f, 22f, 12f, 4f, 1.16f, 1.02f, .78f),
    ICE("Gelo", .06f, .08f, -4f, 5f, 8f, .86f, 1.02f, 1.18f),
    NEON("Neon", -.05f, .25f, 48f, 0f, -4f, .98f, 1.05f, 1.1f),
    DOCUMENTARY("Documentario", -.04f, .22f, -60f, 0f, -6f),
    SUNSET("Por do sol", .05f, .1f, 28f, -12f, 4f, 1.18f, .96f, .78f),
    LAVENDER("Lavanda", .08f, -.05f, 5f, 8f, 10f, 1.04f, .93f, 1.14f),
    EMERALD("Esmeralda", -.02f, .12f, 24f, -10f, 0f, .9f, 1.12f, 1.02f),
    NOIR("Noir", -.08f, .28f, -100f, 0f, -10f),
    SOFT("Pele suave", .1f, -.08f, -10f, -4f, 10f, 1.08f, 1f, .98f),
    DRAMA("Drama", -.08f, .34f, -18f, 0f, -8f),
    LOW_LIGHT("Baixa luz", .18f, .08f, 8f, 0f, 8f, 1.03f, 1.03f, 1.08f),
    CLEAN("Clean", .07f, .05f, 8f, 0f, 6f),
}

enum class ClipTransition(val label: String) {
    NONE("Sem transicao"),
    FADE_BLACK("Fade escuro"),
    FADE_WHITE("Fade claro"),
}

enum class ClipMotion(val label: String) {
    NONE("Sem movimento"),
    ZOOM_IN("Zoom aproximando"),
    ZOOM_OUT("Zoom afastando"),
    PAN_LEFT("Panoramica esquerda"),
    PAN_RIGHT("Panoramica direita"),
}

data class VideoClip(
    val id: String = newId(), val uri: String, val name: String,
    val sourceUs: Long, val width: Int, val height: Int, val fps: Float = 30f,
    val image: Boolean = false, val inUs: Long = 0, val outUs: Long = sourceUs,
    val speed: Float = 1f, val volume: Float = 1f, val rotation: Int = 0,
    val flip: Boolean = false, val crop: CropRect = CropRect(), val filter: Int = 0,
    val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f,
    val hue: Float = 0f, val lightness: Float = 0f, val temperature: Float = 0f,
    val filterStrength: Float = 1f,
    val zoom: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f,
    val fineRotation: Float = 0f, val opacity: Float = 1f, val blur: Float = 0f,
    val motion: ClipMotion = ClipMotion.NONE,
    val transitionIn: ClipTransition = ClipTransition.NONE,
    val transitionOut: ClipTransition = ClipTransition.NONE,
    val transitionDurationUs: Long = 400_000L,
    val audioFadeInUs: Long = 0L, val audioFadeOutUs: Long = 0L,
    val keyframes: List<TransformKeyframe> = emptyList(),
    val grade: StudioGrade = StudioGrade(),
    val effects: List<com.termex.replay15.editor.assets.EffectInstance> = emptyList(),
    val speedCurve: List<SpeedPoint> = emptyList(),
    val preservePitch: Boolean = false,
    val mimeType: String = "",
    val proxyUri: String? = null,
    /**
     * Frame-interpolated render of a slowed-down clip, produced in the background by
     * [com.termex.replay15.editor.motion.SmoothSlowMoCache]. Like [proxyUri] this is a
     * preview-only artifact: it is never persisted and export always reads [uri], so the
     * original media is never replaced.
     */
    val derivedUri: String? = null,
    val is3D: Boolean = false,
    val transform3D: Transform3D = Transform3D(),
    val parentId: String? = null,
    val isNullObject: Boolean = false,
    val backgroundRemoval: BackgroundRemovalEffect = BackgroundRemovalEffect(),
    val mask: MaskState? = null,
    val trackingTracks: List<TrackingTrack> = emptyList(),
    val enhance: AudioEnhance = AudioEnhance.NEUTRAL,
) {
    val effectivePreviewUri: String get() = derivedUri ?: proxyUri ?: uri
    init {
        require(parentId == null || (parentId != id && parentId.matches(Regex("[a-zA-Z0-9-]{1,80}"))))
        require(sourceUs > 0 && inUs >= 0 && outUs <= sourceUs && outUs > inUs)
        require(width > 0 && height > 0 && speed in MIN_CLIP_SPEED..MAX_CLIP_SPEED && volume in 0f..2f)
        require(fps.isFinite() && fps > 0 && rotation in listOf(0, 90, 180, 270) && filter in VideoFilter.entries.indices)
        require(brightness in -1f..1f && contrast in -.9f.. .9f)
        require(saturation in -100f..100f && hue in -180f..180f && lightness in -100f..100f && temperature in -1f..1f)
        require(filterStrength in 0f..1f)
        require(zoom in .25f..4f && offsetX in -.5f.. .5f && offsetY in -.5f.. .5f)
        require(fineRotation in -180f..180f && opacity in 0f..1f && blur in 0f..25f)
        require(transitionDurationUs in 100_000L..2_000_000L)
        require(audioFadeInUs in 0L..10_000_000L && audioFadeOutUs in 0L..10_000_000L)
        require(keyframes.size <= 200 && keyframes.all { it.sourceUs <= sourceUs })
        require(keyframes.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs })
        require(effects.size <= 12 && effects.map { it.id }.distinct().size == effects.size)
        require(effects.flatMap { it.keyframes.values.flatten() }.all { it.sourceUs <= sourceUs })
        require(speedCurve.size <= 32 && speedCurve.all { it.sourceUs <= sourceUs })
        require(speedCurve.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs })
        require(mask?.keyframes?.all { it.timeUs <= sourceUs } != false)
        require(trackingTracks.size <= 8 && trackingTracks.map { it.id }.distinct().size == trackingTracks.size)
    }
    val timeMap: ClipTimeMap by lazy(LazyThreadSafetyMode.PUBLICATION) { ClipTimeMap(this) }
    val durationUs: Long get() = if (speedCurve.isEmpty()) ((outUs - inUs) / speed.toDouble()).roundToLong() else timeMap.durationUs
}

data class VolumeKeyframe(
    val timeUs: Long,
    val volume: Float,
) {
    init {
        require(timeUs >= 0L) { "VolumeKeyframe timeUs must be non-negative" }
        require(volume in 0f..2f) { "Volume must be in 0f..2f" }
    }
}

data class AudioClip(
    val id: String = newId(), val uri: String, val name: String, val sourceUs: Long,
    val startUs: Long = 0, val inUs: Long = 0, val outUs: Long = sourceUs, val volume: Float = 1f,
    val fadeInUs: Long = 0L, val fadeOutUs: Long = 0L,
    val volumeKeyframes: List<VolumeKeyframe> = emptyList(),
    val enhance: AudioEnhance = AudioEnhance.NEUTRAL,
) {
    init {
        require(sourceUs > 0 && startUs >= 0 && inUs >= 0 && outUs > inUs && outUs <= sourceUs && volume in 0f..2f)
        require(fadeInUs in 0L..10_000_000L && fadeOutUs in 0L..10_000_000L)
        require(volumeKeyframes.size <= 200)
    }
    val durationUs: Long get() = outUs - inUs
    val endUs: Long get() = startUs + durationUs

    fun volumeAt(localTimeUs: Long): Float {
        if (volumeKeyframes.isEmpty()) return volume
        val sorted = if (volumeKeyframes.zipWithNext().all { (a, b) -> a.timeUs <= b.timeUs }) volumeKeyframes else volumeKeyframes.sortedBy { it.timeUs }
        if (localTimeUs <= sorted.first().timeUs) return sorted.first().volume
        if (localTimeUs >= sorted.last().timeUs) return sorted.last().volume
        val nextIndex = sorted.indexOfFirst { it.timeUs >= localTimeUs }
        if (nextIndex <= 0) return sorted.first().volume
        val prev = sorted[nextIndex - 1]
        val next = sorted[nextIndex]
        val span = next.timeUs - prev.timeUs
        if (span <= 0) return prev.volume
        val t = ((localTimeUs - prev.timeUs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        return prev.volume + (next.volume - prev.volume) * t
    }
}

/** Legacy enum kept only for schema <= 12 and reference-analysis compatibility. */
enum class TextFont(val id: String, val label: String, val family: String, val asset: String = "") {
    MODERNA("system_sans", "Moderna", "sans-serif"),
    ELEGANTE("system_serif", "Elegante", "serif"),
    IMPACTO("system_sans_black", "Impacto", "sans-serif-black"),
    CONDENSADA("system_sans_condensed", "Condensada", "sans-serif-condensed"),
    LEVE("system_sans_light", "Leve", "sans-serif-light"),
    MAQUINA("system_monospace", "Maquina", "monospace"),
    EDITORIAL("system_serif_monospace", "Editorial", "serif-monospace"),
    MANUSCRITA("system_cursive", "Manuscrita", "cursive"),
    CASUAL("system_casual", "Casual", "casual"),
    OUTFIT("outfit_regular", "Outfit", "sans-serif", "outfit.ttf"),
    MONTSERRAT("montserrat_regular", "Montserrat", "sans-serif", "montserrat.ttf"),
    BEBAS("bebas_neue_regular", "Bebas Neue", "sans-serif-condensed", "bebas.ttf"),
    PLAYFAIR("playfair_display_regular", "Playfair Display", "serif", "playfair.ttf"),
    CAVEAT("caveat_regular", "Caveat", "cursive", "caveat.ttf"),
    SPACE_MONO("space_mono_regular", "Space Mono", "monospace", "spacemono.ttf"),
}

enum class TextAlignment { LEFT, CENTER, RIGHT }
enum class TextAnimation(val label: String) {
    NONE("Sem animacao"),
    FADE("Suave"),
    POP("Pop"),
    SLIDE_UP("Subir"),
    SLIDE_LEFT("Lateral"),
    SLIDE_DOWN("Descer"),
    SLIDE_RIGHT("Direita"),
    BOUNCE("Bounce"),
    ZOOM("Zoom"),
    BLUR_IN("Blur in"),
    PULSE("Pulse"),
    SOFT_BOUNCE("Soft bounce"),
    FLOAT("Float"),
    SHAKE("Shake"),
    WORD_POP("Word pop"),
    KARAOKE("Karaoke"),
    CURRENT_WORD_HIGHLIGHT("Palavra atual"),
    FADE_OUT("Fade out"),
    ZOOM_OUT("Zoom out"),
    BLUR_OUT("Blur out"),
    TYPEWRITER("Typewriter"),
    SCALE("Scale"),
    GLITCH("Glitch"),
    ELASTIC("Elastic"),
    WAVE("Wave"),
    TRACKING("Tracking"),
}

/** A measured word boundary used by karaoke and word-level animation. */
data class SubtitleWordCue(val text: String, val startUs: Long, val endUs: Long) {
    init {
        require(text.isNotBlank() && text.length <= 120)
        require(startUs >= 0L && endUs > startUs)
    }
}

/** Per-word appearance. A zero color means "keep the layer color". */
data class SubtitleWordStyle(
    val normalColor: Int = 0,
    val spokenColor: Int = 0,
    val activeColor: Int = 0,
    val futureColor: Int = 0,
    val activeScale: Float = 1f,
    val boldCurrentWord: Boolean = false,
) {
    init {
        require(activeScale in .8f..1.5f)
    }
}

data class StickerClip(
    val id: String = newId(), val uri: String, val name: String,
    val startUs: Long, val endUs: Long,
    val x: Float = .5f, val y: Float = .5f, val size: Float = .25f,
    val rotation: Float = 0f, val opacity: Float = 1f, val flip: Boolean = false,
    val animation: TextAnimation = TextAnimation.NONE,
    val transformKeyframes: List<com.termex.replay15.editor.transform.TransformKeyframe> = emptyList(),
    val scale: Float = 1f,
    val backgroundRemoval: BackgroundRemovalEffect = BackgroundRemovalEffect(),
    val mask: MaskState? = null,
) {
    val durationUs: Long
        get() = (endUs - startUs).coerceAtLeast(0L)

    fun withDuration(newDurationUs: Long): StickerClip = copy(endUs = startUs + newDurationUs)

    val baseTransform: com.termex.replay15.editor.transform.TransformState
        get() = com.termex.replay15.editor.transform.TransformState(
            x = x,
            y = y,
            scaleX = scale,
            scaleY = scale,
            rotation = rotation,
            opacity = opacity,
        )

    init {
        require(uri.isNotBlank() && name.length <= 200 && startUs >= 0 && endUs > startUs)
        require(x in 0f..1f && y in 0f..1f && size in .03f..1.5f)
        require(scale.isFinite() && scale in 0.05f..20f)
        require(rotation.isFinite() && rotation in -180f..180f && opacity in .05f..1f)
        require(transformKeyframes.size <= 200)
        require(mask?.keyframes?.all { it.timeUs <= durationUs } != false)
    }

    fun transformAt(timeUs: Long): com.termex.replay15.editor.transform.TransformState {
        return com.termex.replay15.editor.transform.LayerStateEvaluator.evaluate(this, timeUs, realtime = false).transform
    }
}

const val MIN_STICKER_DURATION_US = 200_000L
const val MIN_AUDIO_DURATION_US = 200_000L
const val MIN_TEXT_DURATION_US = 200_000L

data class TextClip(
    val id: String = newId(), val text: String, val startUs: Long, val endUs: Long,
    val x: Float = .5f, val y: Float = .75f, val size: Float = .06f,
    val color: Int = -1, val bold: Boolean = true,
    val fontId: String = TextFont.MODERNA.id, val italic: Boolean = false,
    val underline: Boolean = false, val alignment: TextAlignment = TextAlignment.CENTER,
    val opacity: Float = 1f, val letterSpacing: Float = 0f, val lineSpacing: Float = 1f,
    val boxWidth: Float = 0f, val rotation: Float = 0f,
    val backgroundColor: Int = 0, val outlineColor: Int = 0xFF000000.toInt(),
    val outlineWidth: Float = 0f, val shadow: Boolean = false,
    val animation: TextAnimation = TextAnimation.NONE,
    val fontFamily: String = "",
    val fontWeight: Int = 400,
    val fontStyle: String = "normal",
    val fontVersion: String = "",
    val transformKeyframes: List<com.termex.replay15.editor.transform.TransformKeyframe> = emptyList(),
    val scale: Float = 1f,
    val isCaption: Boolean = false,
    val captionFontOverride: String? = null,
    val enterAnimation: TextAnimation = TextAnimation.NONE,
    val duringAnimation: TextAnimation = TextAnimation.NONE,
    val exitAnimation: TextAnimation = TextAnimation.NONE,
    val wordCues: List<SubtitleWordCue> = emptyList(),
    val wordStyle: SubtitleWordStyle = SubtitleWordStyle(),
    val mask: MaskState? = null,
) {
    val durationUs: Long
        get() = (endUs - startUs).coerceAtLeast(0L)

    fun withDuration(newDurationUs: Long): TextClip = copy(endUs = startUs + newDurationUs)

    val baseTransform: com.termex.replay15.editor.transform.TransformState
        get() = com.termex.replay15.editor.transform.TransformState(
            x = x,
            y = y,
            scaleX = scale,
            scaleY = scale,
            rotation = rotation,
            opacity = opacity,
        )

    init {
        require(text.isNotBlank() && text.length <= 500 && startUs >= 0 && endUs > startUs)
        require(fontId.matches(Regex("[a-z0-9_]{1,80}")))
        require(fontWeight in 1..1000)
        require(fontFamily.length <= 100 && fontStyle.length <= 40 && fontVersion.length <= 40)
        require(x in 0f..1f && y in 0f..1f && size in .02f.. .25f)
        require(scale.isFinite() && scale in 0.05f..20f)
        require(opacity in .1f..1f && letterSpacing in -.05f.. .3f && lineSpacing in .7f..2f)
        require(boxWidth == 0f || boxWidth in .2f.. .95f)
        require(rotation.isFinite() && rotation in -180f..180f && outlineWidth in 0f.. .04f)
        require(transformKeyframes.size <= 200)
        require(wordCues.size <= 300 && wordCues.zipWithNext().all { (a, b) -> a.startUs <= b.startUs })
        require(wordCues.all { it.startUs >= startUs && it.endUs <= endUs })
        require(mask?.keyframes?.all { it.timeUs <= durationUs } != false)
    }

    fun transformAt(timeUs: Long): com.termex.replay15.editor.transform.TransformState {
        return com.termex.replay15.editor.transform.LayerStateEvaluator.evaluate(this, timeUs, realtime = false).transform
    }
}

data class ExportSettings(
    val shortSide: Int = 720, val fps: Int = 30, val bitrate: Int = 8_000_000,
    val audioBitrate: Int = 192_000,
) {
    init {
        require(shortSide in listOf(480, 720, 1080, 1440, 2160))
        require(fps in listOf(24, 25, 30, 50, 60, 120, 144) && bitrate in 1_000_000..160_000_000)
        require(audioBitrate in listOf(96_000, 128_000, 192_000, 256_000, 320_000))
    }
    fun estimatedBytes(durationUs: Long): Long = ((bitrate.toLong() + audioBitrate) * (durationUs / 1_000_000.0) / 8).roundToLong()

    companion object {
        /** Matches a newly-created project to its source without inventing resolution or frames. */
        fun forSource(source: VideoClip, audioBitrate: Int = 192_000): ExportSettings {
            val sourceShortSide = min(source.width, source.height)
            val shortSide = listOf(480, 720, 1080, 1440, 2160).lastOrNull { it <= sourceShortSide + 8 } ?: 480
            val fps = listOf(24, 25, 30, 50, 60, 120, 144).lastOrNull { it <= source.fps + .5f } ?: 24
            val baseBitrate = when (shortSide) {
                480 -> 4_000_000
                720 -> 8_000_000
                1080 -> 16_000_000
                1440 -> 28_000_000
                else -> 48_000_000
            }
            val fpsScale = when {
                fps >= 144 -> 4.1
                fps >= 120 -> 3.4
                fps >= 60 -> 1.8
                fps > 30 -> 1.5
                else -> 1.0
            }
            val bitrate = (baseBitrate * fpsScale).roundToLong().toInt().coerceAtMost(160_000_000)
            return ExportSettings(shortSide, fps, bitrate, audioBitrate)
        }
    }
}

data class Project(
    val id: String = newId(), val name: String = "Novo projeto",
    val videos: List<VideoClip> = emptyList(), val audio: List<AudioClip> = emptyList(),
    val texts: List<TextClip> = emptyList(), val aspect: Float = 0f,
    val export: ExportSettings = ExportSettings(),
    val stickers: List<StickerClip> = emptyList(),
    val markers: List<TimelineMarker> = emptyList(),
    val backgroundColor: Int = 0xFF000000.toInt(),
    val canvasFill: CanvasFill = CanvasFill.FIT,
    val videoTracks: List<VideoTrack> = emptyList(),
    val trackStates: Map<String, TrackState> = emptyMap(),
    val adjustmentClips: List<AdjustmentClip> = emptyList(),
    val transitions: List<com.termex.replay15.editor.assets.TransitionInstance> = emptyList(),
    val schemaVersion: Int = PROJECT_SCHEMA,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val camera: Camera3D = Camera3D(),
    val captionGlobalFontId: String = TextFont.MODERNA.id,
    val captionLanguage: String = "pt-BR",
    val captionVocabulary: Set<String> = emptySet(),
    /** Nested sequences over [videos]. Grouping is metadata only: the clips never leave this list. */
    val compounds: List<CompoundClip> = emptyList(),
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && name.length <= 200)
        require(videos.size <= 200 && audio.size <= 8 && texts.size <= MAX_TEXTS && stickers.size <= 24)
        require(markers.size <= 500 && backgroundColor ushr 24 == 255)
        require(aspect == 0f || aspect in .2f..5f)
        require(schemaVersion == PROJECT_SCHEMA && createdAt >= 0 && modifiedAt >= 0)
        require(captionLanguage in setOf("pt-BR", "en-US", "auto"))
        require(captionVocabulary.size <= 500 && captionVocabulary.all { it.length in 1..80 })
        require(videoTracks.size <= 7 && videoTracks.map { it.id }.distinct().size == videoTracks.size)
        require(adjustmentClips.size <= 8 && adjustmentClips.map { it.id }.distinct().size == adjustmentClips.size)
        require(transitions.size <= 200 && transitions.map { it.id }.distinct().size == transitions.size)
        require(videoTracks.none { it.id == MAIN_TRACK || it.id == TEXT_TRACK || it.id == STICKER_TRACK })
        require(trackStates.size <= 64 && trackStates.keys.all { it.length in 1..100 })
        require(videos.sumOf { it.durationUs } <= MAX_PROJECT_US)
        require(compounds.size <= MAX_COMPOUNDS && compounds.map { it.id }.distinct().size == compounds.size)
        val owned = compounds.flatMap { it.childIds }
        require(owned.distinct().size == owned.size) { "Um clipe nao pode pertencer a dois compounds" }
        require(owned.all { child -> videos.any { it.id == child } }) { "Compound referencia um clipe inexistente" }
    }

    /** Transitions are render windows at cuts; they never shorten clip placement. */
    val overlapUs: Long get() = 0L
    val durationUs: Long get() = maxOf(videos.sumOf { it.durationUs }, videoTracks.maxOfOrNull { it.clips.lastOrNull()?.endUs ?: 0L } ?: 0L)

    fun transitionBetween(leftClipId: String, rightClipId: String): com.termex.replay15.editor.assets.TransitionInstance? =
        transitions.firstOrNull { it.leftClipId == leftClipId && it.rightClipId == rightClipId }

    fun withTransition(transition: com.termex.replay15.editor.assets.TransitionInstance): Project {
        val left = videos.firstOrNull { it.id == transition.leftClipId }
        val right = videos.firstOrNull { it.id == transition.rightClipId }
        val transEnteringLeft = transitions.firstOrNull { it.rightClipId == transition.leftClipId }?.durationUs ?: 0L
        val transLeavingRight = transitions.firstOrNull { it.leftClipId == transition.rightClipId }?.durationUs ?: 0L
        val availableLeft = if (left != null) (left.durationUs - transEnteringLeft).coerceAtLeast(100_000L) else transition.durationUs
        val availableRight = if (right != null) (right.durationUs - transLeavingRight).coerceAtLeast(100_000L) else transition.durationUs
        val maxDuration = minOf(availableLeft, availableRight, 5_000_000L)
        val clampedDuration = transition.durationUs.coerceIn(100_000L, maxDuration)
        val safeTransition = transition.copy(durationUs = clampedDuration)
        val filtered = transitions.filterNot { it.leftClipId == safeTransition.leftClipId && it.rightClipId == safeTransition.rightClipId }
        return copy(transitions = filtered + safeTransition)
    }

    fun withoutTransition(leftClipId: String, rightClipId: String): Project =
        copy(transitions = transitions.filterNot { it.leftClipId == leftClipId && it.rightClipId == rightClipId })

    fun cleanTransitions(targetVideos: List<VideoClip> = videos): List<com.termex.replay15.editor.assets.TransitionInstance> {
        val pairs = targetVideos.zipWithNext().map { (a, b) -> a.id to b.id }.toSet()
        return transitions.filter { (it.leftClipId to it.rightClipId) in pairs }
    }

    fun startOf(index: Int): Long {
        var start = 0L
        for (i in 0 until index.coerceAtMost(videos.size)) {
            start += videos[i].durationUs
        }
        return start
    }

    fun indexAt(timeUs: Long): Int {
        if (videos.isEmpty()) return -1
        for (i in 0 until videos.lastIndex) {
            val nextStart = startOf(i + 1)
            if (timeUs < nextStart) return i
        }
        return videos.lastIndex
    }
    fun dimensions(shortSide: Int = export.shortSide): Pair<Int, Int> {
        val first = videos.firstOrNull() ?: videoTracks.firstOrNull()?.clips?.firstOrNull()?.clip
        val sourceRatio = first?.let {
            val ratio = it.width * (it.crop.right - it.crop.left) / (it.height * (it.crop.bottom - it.crop.top))
            if (it.rotation % 180 == 0) ratio else 1f / ratio
        } ?: (16f / 9f)
        val ratio = if (aspect == 0f) sourceRatio else aspect
        fun even(value: Float) = ((value / 2).toInt() * 2).coerceAtLeast(2)
        return if (ratio >= 1f) even(shortSide * ratio) to shortSide else shortSide to even(shortSide / ratio)
    }
    fun split(index: Int, playheadUs: Long): Project {
        if (videos.size >= 200) return this
        val clip = videos.getOrNull(index) ?: return this
        val offset = playheadUs - startOf(index)
        if (offset < MIN_CLIP || clip.durationUs - offset < MIN_CLIP) return this
        val cut = clip.timeMap.sourceAt(offset)
        if (cut <= clip.inUs || cut >= clip.outUs) return this
        val result = videos.toMutableList()
        val cutTransform = clip.transformAt(cut).copy(sourceUs = cut)
        val leftKeys = if (clip.keyframes.isEmpty()) {
            emptyList()
        } else {
            (clip.keyframes.filter { it.sourceUs < cut } + cutTransform)
                .distinctBy { it.sourceUs }
                .sortedBy { it.sourceUs }
        }
        val rightKeys = if (clip.keyframes.isEmpty()) {
            emptyList()
        } else {
            (listOf(cutTransform) + clip.keyframes.filter { it.sourceUs > cut })
                .distinctBy { it.sourceUs }
                .sortedBy { it.sourceUs }
        }
        result[index] = clip.copy(
            outUs = cut,
            transitionOut = ClipTransition.NONE,
            keyframes = leftKeys,
        )
        result.add(
            index + 1,
            clip.copy(
                id = newId(),
                inUs = cut,
                transitionIn = ClipTransition.NONE,
                keyframes = rightKeys,
            ),
        )
        // Groups survive a split; a group that would lose a child is dropped by the sanitizer.
        return CompoundEditing.sanitize(copy(videos = result, compounds = emptyList()))
    }
    fun splitAt(playheadUs: Long): Project = split(indexAt(playheadUs), playheadUs)
    fun changeVideo(index: Int, transform: (VideoClip) -> VideoClip): Project =
        copy(videos = videos.mapIndexed { i, v -> if (i == index) transform(v) else v })
    fun moveVideo(from: Int, to: Int): Project {
        if (from !in videos.indices || to !in videos.indices || from == to) return this
        val list = videos.toMutableList(); list.add(to, list.removeAt(from))
        return CompoundEditing.sanitize(copy(videos = list, compounds = emptyList(), transitions = cleanTransitions(list)))
    }
}

fun TransitionInstance.startUs(project: Project): Long {
    val rightIdx = project.videos.indexOfFirst { it.id == rightClipId }
    return if (rightIdx >= 0) project.startOf(rightIdx) else 0L
}

fun TransitionInstance.endUs(project: Project): Long = startUs(project) + durationUs
