package com.termex.replay15.editor.ui

import com.termex.replay15.editor.assets.TransitionDefinition
import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.domain.*
import com.termex.replay15.editor.transitions.TransitionPrograms
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * What the transition panel is editing for one cut. Every change only produces a draft project for
 * the preview; [committed] is what the panel finally applies in a single history step.
 *
 * Pure Kotlin, so the panel's rules (limits, frames, alignment, apply to all) are unit tested.
 */
class TransitionDraft(
    private val original: Project,
    val leftClipId: String,
    val rightClipId: String,
    private val definitions: List<TransitionDefinition>,
) {
    private val existing = original.transitionBetween(leftClipId, rightClipId)
    private val instanceId = existing?.id ?: newId()
    private var durationTouched = existing != null

    /** Frames per second used to show and step durations. */
    val fps: Int = original.export.fps.coerceAtLeast(1)

    /** Selected transition; null means "no transition" on this cut. */
    var definition: TransitionDefinition? = existing?.let { e -> definitions.firstOrNull { it.id == e.transitionId } }
        private set
    var alignment: TransitionAlignment = existing?.alignment ?: TransitionAlignment.CENTER
        private set
    var easing: Easing = existing?.easing ?: Easing.SMOOTH
    var durationUs: Long = existing?.durationUs ?: DEFAULT_DURATION_US
        private set
    private val values = existing?.parameters.orEmpty().filterKeys { it != TransitionPrograms.MODE }.toMutableMap()

    val hadTransition: Boolean get() = existing != null

    /** Parameters the user can edit for the current transition (never the internal mode). */
    val editableParameters get() = definition?.parameters.orEmpty().filter { it.id != TransitionPrograms.MODE }

    fun valueOf(id: String): Float {
        val parameter = definition?.parameters?.firstOrNull { it.id == id }
        return values[id] ?: parameter?.default ?: 0f
    }

    fun setValue(id: String, value: Float) {
        val parameter = definition?.parameters?.firstOrNull { it.id == id } ?: return
        values[id] = value.coerceIn(parameter.min, parameter.max)
    }

    fun select(next: TransitionDefinition?) {
        if (next?.id == definition?.id) return
        definition = next
        values.clear()
        if (next != null && !durationTouched) durationUs = next.defaultDurationUs
        durationUs = durationUs.coerceIn(minDurationUs, maxDurationUs())
    }

    fun setAlignment(next: TransitionAlignment) {
        alignment = next
        durationUs = durationUs.coerceIn(minDurationUs, maxDurationUs())
    }

    /** Longest duration this cut allows with the current alignment, given its neighbours. */
    fun maxDurationUs(): Long = TransitionTiming.maxDurationUs(original, probe(TransitionTiming.MIN_TRANSITION_US))

    val minDurationUs: Long get() = TransitionTiming.MIN_TRANSITION_US

    fun minFrames(): Int = ceil(minDurationUs * fps / 1_000_000.0 - 1e-6).toInt().coerceAtLeast(1)
    fun maxFrames(): Int = floor(maxDurationUs() * fps / 1_000_000.0 + 1e-6).toInt().coerceAtLeast(minFrames())
    fun frames(): Int = (durationUs * fps / 1_000_000.0).roundToLong().toInt().coerceIn(minFrames(), maxFrames())

    fun setFrames(frames: Int) {
        durationTouched = true
        val clamped = frames.coerceIn(minFrames(), maxFrames())
        durationUs = (clamped * 1_000_000.0 / fps).roundToLong().coerceIn(minDurationUs, maxDurationUs())
    }

    fun setDurationUs(value: Long) = setFrames((value * fps / 1_000_000.0).roundToLong().toInt())

    fun instance(): TransitionInstance? = definition?.let { probe(durationUs).copy(transitionId = it.id) }

    private fun probe(duration: Long) = TransitionInstance(
        id = instanceId,
        transitionId = definition?.id ?: "cross_dissolve",
        leftClipId = leftClipId,
        rightClipId = rightClipId,
        durationUs = duration,
        parameters = values.toMap(),
        easing = easing,
        alignment = alignment,
    )

    /** The project as it would look with the current choice; used for the live preview. */
    fun draftProject(): Project = instance()?.let(original::withTransition) ?: original.withoutTransition(leftClipId, rightClipId)

    /** The same transition on every cut of the main track, each clamped to what its cut allows. */
    fun applyToAllCuts(): Project {
        val template = instance() ?: return original.copy(transitions = emptyList())
        var result = original
        original.videos.zipWithNext().forEach { (left, right) ->
            val id = if (left.id == leftClipId && right.id == rightClipId) template.id
                else original.transitionBetween(left.id, right.id)?.id ?: newId()
            result = result.withTransition(template.copy(id = id, leftClipId = left.id, rightClipId = right.id))
        }
        return result
    }

    /** Where the current transition plays in the draft, for previewing it. */
    fun window(project: Project = draftProject()): TransitionWindow? =
        instance()?.let { TransitionTiming.window(project, it) }

    fun cutUs(): Long {
        val index = original.videos.indexOfFirst { it.id == rightClipId }
        return if (index >= 0) original.startOf(index) else 0L
    }

    /** "0,50 s · 15 quadros" */
    fun durationLabel(us: Long = durationUs): String {
        val frames = (us * fps / 1_000_000.0).roundToLong()
        val seconds = String.format(java.util.Locale("pt", "BR"), "%.2f", us / 1_000_000.0)
        return "$seconds s · $frames ${if (frames == 1L) "quadro" else "quadros"}"
    }

    companion object {
        const val DEFAULT_DURATION_US = 500_000L

        /** Quick picks offered under the duration slider. */
        val DURATION_PRESETS_US = listOf(250_000L, 500_000L, 1_000_000L, 1_500_000L, 2_000_000L)
        val EASING_PRESETS = listOf(Easing.SMOOTH, Easing.LINEAR, Easing.EASE_IN, Easing.EASE_OUT)
        val ALIGNMENTS = listOf(TransitionAlignment.END_AT_CUT, TransitionAlignment.CENTER, TransitionAlignment.START_AT_CUT)
    }
}

/** Curated groups shown as chips in the transition panel. */
object TransitionGroups {
    data class Group(val label: String, val categories: Set<String> = emptySet(), val ids: List<String> = emptyList())

    val ESSENTIALS = listOf(
        "cross_dissolve", "fade", "dip_to_black", "dip_to_white", "slide_left", "push_left", "wipe_left",
        "circle_wipe", "zoom_in", "smooth_zoom", "whip_left", "blur_dissolve", "white_flash", "light_leak",
        "cube_left", "spin_zoom", "page_turn", "luma_fade",
    )

    val GROUPS = listOf(
        Group("Essenciais", ids = ESSENTIALS),
        Group("Básico", setOf("Basic", "Fade")),
        Group("Movimento", setOf("Slide", "Motion")),
        Group("Zoom", setOf("Zoom")),
        Group("Cortina", setOf("Wipe", "Luma")),
        Group("Desfoque", setOf("Blur")),
        Group("Luz", setOf("Light", "Cinematic")),
        Group("3D", setOf("3D-like")),
        Group("Glitch", setOf("Glitch", "RGB")),
        Group("Distorção", setOf("Distortion")),
        Group("Criativo", setOf("Creative", "Film", "Social", "Gaming")),
    )

    fun items(group: Group, definitions: List<TransitionDefinition>): List<TransitionDefinition> =
        if (group.ids.isNotEmpty()) group.ids.mapNotNull { id -> definitions.firstOrNull { it.id == id } }
        else definitions.filter { com.termex.replay15.editor.assets.TransitionCatalog.canonicalCategory(it.category) in group.categories }
}
