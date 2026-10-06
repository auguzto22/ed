package com.termex.replay15.editor.domain

/**
 * A nested sequence: several consecutive main-track clips that the editor treats as one unit.
 *
 * A compound is **referencing, not re-structuring**. Its [childIds] point at clips that stay in
 * [Project.videos] on the same positions, in the same order. Nothing is re-parented, flattened or
 * re-rendered, so preview, decoders, transitions, audio and export keep reading the exact same flat
 * sequence they read before the compound existed. Opening a compound is a view change (the editor
 * restricts editing to the child range); it never rewrites the timeline.
 */
data class CompoundClip(
    val id: String = newId(),
    val name: String = "Compound",
    /** Timeline order; must be contiguous in [Project.videos] and owned by no other compound. */
    val childIds: List<String> = emptyList(),
    val color: Int = DEFAULT_COMPOUND_COLOR,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && name.length in 1..100)
        require(childIds.size in 2..MAX_COMPOUND_CHILDREN)
        require(childIds.all { it.matches(Regex("[a-zA-Z0-9-]{1,80}")) && it != id })
        require(childIds.distinct().size == childIds.size)
    }

    val childCount: Int get() = childIds.size

    companion object {
        fun forChildCount(count: Int): String = "Compound $count"
    }
}

const val MAX_COMPOUND_CHILDREN = 64
const val MAX_COMPOUNDS = 40
const val DEFAULT_COMPOUND_COLOR = 0xFF8E7CF0.toInt()

/**
 * Group/nest editing over the flat main sequence. Every function returns a new [Project]; none of
 * them touch decoder, renderer or export state because the underlying clips never move.
 */
object CompoundEditing {

    /** True when [clipId] sits inside a compound; a grouped clip cannot start its own. */
    fun owning(project: Project, clipId: String): CompoundClip? =
        project.compounds.firstOrNull { clipId in it.childIds }

    fun isGrouped(project: Project, clipId: String): Boolean = owning(project, clipId) != null

    /** The compound [clipId] belongs to, or the compound that contains index [index]. */
    fun at(project: Project, clipId: String? = null, index: Int = -1): CompoundClip? {
        if (clipId != null) return owning(project, clipId)
        if (index !in project.videos.indices) return null
        return owning(project, project.videos[index].id)
    }

    /** Children in timeline order, skipping ids whose clip is no longer in the main sequence. */
    fun children(project: Project, compound: CompoundClip): List<VideoClip> {
        val byId = project.videos.associateBy(VideoClip::id)
        return compound.childIds.mapNotNull(byId::get)
    }

    fun indices(project: Project, compound: CompoundClip): List<Int> {
        val positions = project.videos.withIndex().associate { (i, clip) -> clip.id to i }
        return compound.childIds.mapNotNull(positions::get)
    }

    fun startUs(project: Project, compound: CompoundClip): Long =
        indices(project, compound).minOrNull()?.let { project.startOf(it) } ?: 0L

    fun endUs(project: Project, compound: CompoundClip): Long =
        indices(project, compound).maxOrNull()?.let { i -> project.startOf(i) + project.videos[i].durationUs } ?: 0L

    fun durationUs(project: Project, compound: CompoundClip): Long = (endUs(project, compound) - startUs(project, compound))
        .coerceAtLeast(0L)

    /**
     * Groups [firstIndex]..[lastIndex] of the main sequence. Returns the project unchanged when the
     * range is not a valid, free, contiguous run of at least two clips.
     */
    fun group(project: Project, firstIndex: Int, lastIndex: Int = firstIndex + 1, name: String? = null): Project {
        if (firstIndex !in project.videos.indices || lastIndex !in project.videos.indices) return project
        val from = minOf(firstIndex, lastIndex); val to = maxOf(firstIndex, lastIndex)
        if (to - from < 1) return project
        if (project.compounds.size >= MAX_COMPOUNDS) return project
        val span = project.videos.subList(from, to + 1)
        if (span.any { clip -> isGrouped(project, clip.id) }) return project
        val compound = CompoundClip(
            name = name?.takeIf { it.isNotBlank() } ?: CompoundClip.forChildCount(span.size),
            childIds = span.map(VideoClip::id),
        )
        // Keep the list ordered by timeline position so codecs, UI and undo snapshots are stable.
        val ordered = (project.compounds + compound).sortedBy { startUs(project, it) }
        return sanitize(project.copy(compounds = ordered))
    }

    /** Groups every selected main clip, in timeline order. Needs two or more ungrouped clips. */
    fun groupSelection(project: Project, indices: Collection<Int>, name: String? = null): Project {
        val ordered = indices.distinct().sorted()
        if (ordered.size < 2) return project
        return group(project, ordered.first(), ordered.last(), name)
    }

    /** Drops the group. The clips stay exactly where they are; only the grouping is lost. */
    fun ungroup(project: Project, compoundId: String): Project =
        sanitize(project.copy(compounds = project.compounds.filterNot { it.id == compoundId }))

    fun ungroupAll(project: Project): Project = sanitize(project.copy(compounds = emptyList()))

    /** Renames a compound; the child sequence is untouched. */
    fun rename(project: Project, compoundId: String, name: String): Project {
        val clean = name.trim().take(100)
        if (clean.isEmpty()) return project
        return project.copy(compounds = project.compounds.map {
            if (it.id == compoundId) it.copy(name = clean) else it
        })
    }

    /**
     * Removes groups that no longer describe reality: fewer than two surviving children, a child that
     * left the main sequence, or a child claimed by two groups. Run after any structural edit so the
     * project can never hold a dangling compound.
     */
    fun sanitize(project: Project): Project {
        val known = project.videos.mapTo(HashSet(project.videos.size), VideoClip::id)
        val used = HashSet<String>(project.compounds.size * 2)
        val kept = project.compounds.filter { compound ->
            val alive = compound.childIds.filter { it in known }
            if (alive.size < 2) return@filter false
            if (compound.childIds.size != alive.size) return@filter false
            true
        }
        val deduped = kept.filter { compound ->
            compound.childIds.all { used.add(it) }
        }
        if (deduped.size == project.compounds.size && deduped.indices.all { deduped[it] == project.compounds[it] }) return project
        return project.copy(compounds = deduped)
    }
}
