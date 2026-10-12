package com.termex.replay15.editor.domain

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.transitions.NativeTransitionBridge
import com.termex.replay15.editor.transitions.TransitionPrograms
import org.json.JSONArray
import org.json.JSONObject

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

    fun progress(timeUs: Long): Float = NativeTransitionBridge.nativeProgress(linearProgress(timeUs), transition.easing.ordinal,
        transition.bezier.x1, transition.bezier.y1, transition.bezier.x2, transition.bezier.y2)
}

/**
 * Single source of truth for transition placement. Preview video, preview audio, export,
 * the timeline and the transition panel all read windows from here.
 */
object TransitionTiming {
    private fun edit(t: TransitionInstance) = JSONObject().put("id", t.id)
        .put("leftClipId", t.leftClipId).put("rightClipId", t.rightClipId)
        .put("durationUs", t.durationUs).put("alignment", t.alignment.ordinal)
    private fun timeline(project: Project) = JSONObject()
        .put("clips", JSONArray().apply { project.videos.forEach { put(JSONObject().put("id", it.id).put("durationUs", it.durationUs)) } })
        .put("transitions", JSONArray().apply { project.transitions.forEach { put(edit(it)) } })
    fun split(durationUs: Long, alignment: TransitionAlignment): Pair<Long, Long> {
        val a = TransitionPrograms.query(JSONObject().put("op", "split").put("duration", durationUs).put("alignment", alignment.ordinal)) as JSONArray
        return a.getLong(0) to a.getLong(1)
    }
    fun windows(project: Project): List<TransitionWindow> {
        if (project.transitions.isEmpty() || project.videos.size < 2) return emptyList()
        val result = TransitionPrograms.query(JSONObject().put("op", "plan").put("timeline", timeline(project))) as JSONArray
        val edits = project.transitions.associateBy { it.id }
        return List(result.length()) { index ->
            val w = result.getJSONObject(index)
            TransitionWindow(edits.getValue(w.getString("id")), w.getInt("leftIndex"), w.getLong("cutUs"), w.getLong("startUs"), w.getLong("endUs"))
        }
    }
    fun window(project: Project, transition: TransitionInstance): TransitionWindow? = windows(project).firstOrNull { it.transition.id == transition.id }
    fun maxDurationUs(project: Project, transition: TransitionInstance, ceilingUs: Long = MAX_TRANSITION_US): Long =
        (TransitionPrograms.query(JSONObject().put("op", "maximum").put("timeline", timeline(project)).put("edit", edit(transition)).put("ceiling", ceilingUs)) as Number).toLong()
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
        return NativeTransitionBridge.nativeHandleSource(offsetUs, duration, clip.inUs, clip.outUs,
            clip.sourceUs, clip.speed, supportsHandles(clip), clip.image, bounded)
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
