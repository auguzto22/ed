package com.termex.replay15.editor.domain

import com.termex.replay15.editor.assets.TransitionInstance
import kotlin.math.roundToLong

/**
 * Where a transition sits relative to the cut, as in Premiere/Resolve.
 *
 * The cut never moves and the project duration never changes: the window only decides which
 * side of the cut is covered. Both clips keep playing through the window (see [TransitionHandles]).
 */
enum class TransitionAlignment(val label: String) {
    /** Half before the cut, half after. The professional default. */
    CENTER("No corte"),
    /** The whole window is after the cut (the only behavior of projects saved before schema 29). */
    START_AT_CUT("Depois do corte"),
    /** The whole window is before the cut. */
    END_AT_CUT("Antes do corte"),
}

/**
 * One resolved transition on the main track, in project time.
 *
 * During [startUs, cutUs) the incoming clip is shown before its in point (its head handle);
 * during [cutUs, endUs) the outgoing clip keeps playing after its out point (its tail handle).
 */
data class TransitionWindow(
    val transition: TransitionInstance,
    val leftIndex: Int,
    val cutUs: Long,
    val startUs: Long,
    val endUs: Long,
) {
    val rightIndex: Int get() = leftIndex + 1
    val durationUs: Long get() = endUs - startUs
    /** How long the outgoing clip plays past its out point. */
    val leftExtensionUs: Long get() = endUs - cutUs
    /** How long the incoming clip plays before its in point. */
    val rightExtensionUs: Long get() = cutUs - startUs
    val midpointUs: Long get() = startUs + durationUs / 2

    fun contains(timeUs: Long): Boolean = timeUs >= startUs && timeUs < endUs

    /** Linear progress in [0, 1]; easing is applied by the caller. */
    fun linearProgress(timeUs: Long): Float =
        ((timeUs - startUs).toDouble() / durationUs.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)

    fun progress(timeUs: Long): Float = transition.easing.apply(linearProgress(timeUs), transition.bezier)
}

/**
 * Single source of truth for transition placement. Preview video, preview audio, export,
 * the timeline and the transition panel all read windows from here.
 */
object TransitionTiming {
    /** Splits [durationUs] into (before cut, after cut) for [alignment]. */
    fun split(durationUs: Long, alignment: TransitionAlignment): Pair<Long, Long> {
        val d = durationUs.coerceAtLeast(0L)
        return when (alignment) {
            TransitionAlignment.CENTER -> d / 2 to d - d / 2
            TransitionAlignment.START_AT_CUT -> 0L to d
            TransitionAlignment.END_AT_CUT -> d to 0L
        }
    }

    /**
     * Resolves every transition between adjacent main-track clips.
     *
     * Each part of a window lives inside exactly one clip: the part before the cut inside the
     * outgoing clip, the part after the cut inside the incoming clip. A clip that is too short
     * for both of its neighbouring windows shrinks them proportionally, so windows never overlap
     * and a clip is never covered by two transitions at once, whatever was trimmed afterwards.
     */
    fun windows(project: Project): List<TransitionWindow> {
        val videos = project.videos
        if (project.transitions.isEmpty() || videos.size < 2) return emptyList()
        val byLeft = arrayOfNulls<TransitionInstance>(videos.size)
        val indexById = HashMap<String, Int>(videos.size * 2)
        videos.forEachIndexed { index, clip -> indexById[clip.id] = index }
        project.transitions.forEach { transition ->
            val left = indexById[transition.leftClipId] ?: return@forEach
            if (left < videos.lastIndex && videos[left + 1].id == transition.rightClipId && byLeft[left] == null) {
                byLeft[left] = transition
            }
        }
        val before = LongArray(videos.size)
        val after = LongArray(videos.size)
        for (index in 0 until videos.lastIndex) {
            val transition = byLeft[index] ?: continue
            val (b, a) = split(transition.durationUs, transition.alignment)
            before[index] = b
            after[index] = a
        }
        // Clip k hosts the tail of the window entering it and the head of the window leaving it.
        for (k in videos.indices) {
            val entering = if (k > 0) after[k - 1] else 0L
            val leaving = before[k]
            val need = entering + leaving
            val available = videos[k].durationUs
            if (need > available && need > 0L) {
                val scale = available.toDouble() / need
                if (k > 0) after[k - 1] = (entering * scale).toLong()
                before[k] = (leaving * scale).toLong()
            }
        }
        val result = ArrayList<TransitionWindow>()
        var cut = 0L
        for (index in 0 until videos.lastIndex) {
            cut += videos[index].durationUs
            val transition = byLeft[index] ?: continue
            if (before[index] + after[index] <= 0L) continue
            result += TransitionWindow(transition, index, cut, cut - before[index], cut + after[index])
        }
        return result
    }

    fun window(project: Project, transition: TransitionInstance): TransitionWindow? =
        windows(project).firstOrNull { it.transition.id == transition.id }

    /**
     * Longest duration [transition] can have at its cut without overlapping its neighbours'
     * windows, for the transition's own alignment. Neighbours keep their current placement.
     */
    fun maxDurationUs(project: Project, transition: TransitionInstance, ceilingUs: Long = MAX_TRANSITION_US): Long {
        val videos = project.videos
        val left = videos.indexOfFirst { it.id == transition.leftClipId }
        if (left < 0 || left >= videos.lastIndex || videos[left + 1].id != transition.rightClipId) return MIN_TRANSITION_US
        val others = windows(project.copy(transitions = project.transitions.filterNot {
            it.leftClipId == transition.leftClipId && it.rightClipId == transition.rightClipId
        }))
        val enteringLeft = others.firstOrNull { it.rightIndex == left }?.leftExtensionUs ?: 0L
        val leavingRight = others.firstOrNull { it.leftIndex == left + 1 }?.rightExtensionUs ?: 0L
        val roomBefore = (videos[left].durationUs - enteringLeft).coerceAtLeast(0L)
        val roomAfter = (videos[left + 1].durationUs - leavingRight).coerceAtLeast(0L)
        val byAlignment = when (transition.alignment) {
            TransitionAlignment.CENTER -> 2 * minOf(roomBefore, roomAfter)
            TransitionAlignment.START_AT_CUT -> roomAfter
            TransitionAlignment.END_AT_CUT -> roomBefore
        }
        // Never longer than the shorter clip: a window that swallows a whole clip reads as a glitch.
        val limit = minOf(byAlignment, videos[left].durationUs, videos[left + 1].durationUs)
        return limit.coerceIn(MIN_TRANSITION_US, ceilingUs)
    }

    const val MIN_TRANSITION_US = 100_000L
    const val MAX_TRANSITION_US = 5_000_000L
}

/**
 * Media beyond the trimmed window ("handles"). A transition plays the outgoing clip past its out
 * point and the incoming clip before its in point, so neither side freezes. When the source has
 * no more media the last available frame is held, exactly like an NLE's "insufficient media".
 *
 * Handles are used for forward clips with a constant speed and for photos. Reversed clips and
 * speed ramps hold their boundary frame instead, so preview and export stay identical.
 */
object TransitionHandles {
    fun supportsHandles(clip: VideoClip): Boolean =
        !clip.isNullObject && (clip.image || (!clip.reverse && clip.speedCurve.isEmpty()))

    /** Source time for an offset relative to the clip's timeline start. Negative = head, >= duration = tail. */
    fun sourceAt(clip: VideoClip, offsetUs: Long): Long {
        val duration = clip.durationUs
        val inside = offsetUs.coerceIn(0L, (duration - 1L).coerceAtLeast(0L))
        val bounded = clip.timeMap.sourceAt(inside).coerceIn(minOf(clip.inUs, clip.outUs), maxOf(clip.inUs, clip.outUs))
        if (offsetUs in 0L until duration) return bounded
        if (clip.image || !supportsHandles(clip)) return bounded
        val lastFrame = (clip.sourceUs - 1L).coerceAtLeast(0L)
        return if (offsetUs < 0L) {
            (clip.inUs + (offsetUs * clip.speed.toDouble()).roundToLong()).coerceIn(0L, lastFrame)
        } else {
            (clip.outUs + ((offsetUs - duration) * clip.speed.toDouble()).roundToLong()).coerceIn(0L, lastFrame)
        }
    }

    /** Timeline time of real media available before the in point (photos: unlimited). */
    fun availableHeadUs(clip: VideoClip): Long = when {
        clip.image -> Long.MAX_VALUE / 4
        !supportsHandles(clip) -> 0L
        else -> (clip.inUs / clip.speed.toDouble()).toLong()
    }

    /** Timeline time of real media available after the out point (photos: unlimited). */
    fun availableTailUs(clip: VideoClip): Long = when {
        clip.image -> Long.MAX_VALUE / 4
        !supportsHandles(clip) -> 0L
        else -> ((clip.sourceUs - clip.outUs) / clip.speed.toDouble()).toLong()
    }

    /** Source range [first, last] actually read by a clip whose span is extended by [headUs]/[tailUs]. */
    fun sourceRange(clip: VideoClip, headUs: Long, tailUs: Long): LongRange {
        if (clip.image || !supportsHandles(clip)) return clip.inUs..clip.outUs
        val first = sourceAt(clip, -headUs.coerceAtLeast(0L)).coerceAtMost(clip.inUs)
        val last = if (tailUs <= 0L) clip.outUs else (sourceAt(clip, clip.durationUs + tailUs - 1L) + 1L).coerceIn(clip.outUs, clip.sourceUs)
        return first..last
    }

    /** Handles shorter than about one frame are held instead of decoded as a separate stretch. */
    const val MIN_USABLE_HANDLE_US = 40_000L

    /** Real media played before the in point when a window wants [wantedUs] of it. */
    fun usableHeadUs(clip: VideoClip, wantedUs: Long): Long =
        minOf(wantedUs.coerceAtLeast(0L), availableHeadUs(clip)).takeIf { it >= MIN_USABLE_HANDLE_US } ?: 0L

    /** Real media played after the out point when a window wants [wantedUs] of it. */
    fun usableTailUs(clip: VideoClip, wantedUs: Long): Long =
        minOf(wantedUs.coerceAtLeast(0L), availableTailUs(clip)).takeIf { it >= MIN_USABLE_HANDLE_US } ?: 0L
}

/** Window start of this transition in project time, including its alignment. */
fun TransitionInstance.startUs(project: Project): Long =
    TransitionTiming.window(project, this)?.startUs ?: fallbackCut(project)

/** Window end of this transition in project time, including its alignment. */
fun TransitionInstance.endUs(project: Project): Long =
    TransitionTiming.window(project, this)?.endUs ?: fallbackCut(project)

private fun TransitionInstance.fallbackCut(project: Project): Long {
    val rightIdx = project.videos.indexOfFirst { it.id == rightClipId }
    return if (rightIdx >= 0) project.startOf(rightIdx) else 0L
}
