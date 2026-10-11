package com.termex.replay15.editor.render

import com.termex.replay15.editor.domain.*
import kotlin.math.roundToLong

/**
 * One Media3 item of an export sequence.
 *
 * A main-track clip that takes part in a transition is exported as up to two items, so both clips
 * keep moving through the window exactly like the preview:
 *  - a [hold] item repeating the first available frame where the source has no media before the
 *    in point (an NLE's "insufficient media");
 *  - the clip's own item, extended by the head and tail media ("handles") the source does have.
 * Every other clip keeps a single item covering exactly its own span.
 */
data class ExportPiece(
    val clip: VideoClip,
    /** Project time of the clip's own first frame; keyframes and effects are evaluated against it. */
    val clipStartUs: Long,
    /** Span of this item in the composition. */
    val startUs: Long,
    val endUs: Long,
    /** Clipping range in the source file (ignored for photos). */
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val hold: Boolean = false,
) {
    val durationUs: Long get() = endUs - startUs

    /** True when the item covers more than the clip's own span. */
    val extended: Boolean get() = hold || startUs != clipStartUs || endUs != clipStartUs + clip.durationUs

    /** Constant speed of an extended item. Items that are not extended keep the clip's own speed map. */
    val speed: Float get() = when {
        hold -> ((sourceEndUs - sourceStartUs).toDouble() / durationUs.coerceAtLeast(1L)).toFloat()
        clip.image -> 1f
        else -> clip.speed
    }

    fun contains(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs

    companion object {
        fun of(placed: TimedVideoClip) = ExportPiece(
            placed.clip, placed.startUs, placed.startUs, placed.endUs, placed.clip.inUs, placed.clip.outUs,
        )
    }
}

object ExportPlan {
    /** Outgoing frames are captured slightly before the window in case its decoder runs late. */
    const val CAPTURE_PREROLL_US = 100_000L

    /** Items for a main-track clip given the transition windows entering and leaving it. */
    fun mainPieces(placed: TimedVideoClip, entering: TransitionWindow?, leaving: TransitionWindow?): List<ExportPiece> {
        val clip = placed.clip
        if (clip.isNullObject || (entering == null && leaving == null)) return listOf(ExportPiece.of(placed))
        val wantedHeadUs = entering?.rightExtensionUs ?: 0L
        val headUs = TransitionHandles.usableHeadUs(clip, wantedHeadUs)
        val tailUs = TransitionHandles.usableTailUs(clip, leaving?.leftExtensionUs ?: 0L)
        val speed = clip.speed.toDouble()
        val sourceStartUs = if (clip.image || headUs == 0L) clip.inUs
            else (clip.inUs - (headUs * speed).roundToLong()).coerceAtLeast(0L)
        val sourceEndUs = if (clip.image || tailUs == 0L) clip.outUs
            else (clip.outUs + (tailUs * speed).roundToLong()).coerceAtMost(clip.sourceUs)
        val main = ExportPiece(clip, placed.startUs, placed.startUs - headUs, placed.endUs + tailUs, sourceStartUs, sourceEndUs)
        val holdUs = wantedHeadUs - headUs
        if (entering == null || holdUs < TransitionHandles.MIN_USABLE_HANDLE_US) return listOf(main)
        // No media before the in point: decode from the first frame the clip shows and freeze it.
        val holdSourceUs = minOf(holdUs, (clip.sourceUs - sourceStartUs).coerceAtLeast(1L))
        val hold = ExportPiece(clip, placed.startUs, main.startUs - holdUs, main.startUs,
            sourceStartUs, sourceStartUs + holdSourceUs, hold = true)
        return listOf(hold, main)
    }
}

/**
 * Converts the timestamps Media3 hands to per-clip effects into project time.
 *
 * Media3 1.11 passes composition time; an item-relative clock is still accepted for safety. The
 * span used to recognise composition time is the item's, which includes handles and holds, so a
 * frame before the clip's own start is not mistaken for an item-relative timestamp.
 */
object ExportClock {
    fun projectTimeUs(presentationTimeUs: Long, itemStartUs: Long, itemEndUs: Long): Long =
        if (presentationTimeUs in itemStartUs..itemEndUs) presentationTimeUs else itemStartUs + presentationTimeUs
}

/** Which main-track clip the export compositor shows during a transition window. */
object TransitionVisibility {
    /**
     * Once the incoming clip has drawn the composited transition it carries the whole picture.
     * Until then, or if the shader failed, the edit stays a plain cut at its real position, where
     * both clips always have their own media.
     */
    fun visible(window: TransitionWindow, clipId: String, timeUs: Long, shaderSinceUs: Long): Boolean {
        val incoming = window.transition.rightClipId == clipId
        return if (timeUs >= shaderSinceUs) incoming else (timeUs >= window.cutUs) == incoming
    }
}
