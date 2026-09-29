package com.termex.replay15.editor.render

import com.termex.replay15.editor.domain.*
import kotlin.math.roundToLong

data class RenderLayer(val id: String, val clips: List<TimedVideoClip>) {
    fun activeAt(timeUs: Long) = clips.firstOrNull { timeUs in it.startUs until it.endUs }
}

object RenderPlan {
    /** Media3 input zero is on top. Every sequence is padded to the same end. */
    fun layers(project: Project): List<RenderLayer> {
        val upper = project.videoTracks.asReversed().filter { it.clips.isNotEmpty() }
            .map { RenderLayer(it.id, coalesce(it.clips)) }
        return if (project.transitions.isEmpty()) {
            var time = 0L
            val main = project.videos.map { clip -> TimedVideoClip(time, clip).also { time += clip.durationUs } }
            upper + RenderLayer(MAIN_TRACK, coalesce(main))
        } else {
            val mainA = mutableListOf<TimedVideoClip>()
            val mainB = mutableListOf<TimedVideoClip>()
            project.videos.forEachIndexed { index, clip ->
                val timed = TimedVideoClip(project.startOf(index), clip)
                if (index % 2 == 0) mainA += timed else mainB += timed
            }
            upper + listOf(
                RenderLayer(MAIN_TRACK_A, coalesce(mainA)),
                RenderLayer(MAIN_TRACK_B, coalesce(mainB)),
            )
        }
    }

    /** Preserve project placement in each independent Media3 input sequence. */
    fun gapBefore(previousEndUs: Long, placed: TimedVideoClip): Long {
        require(placed.startUs >= previousEndUs) { "Overlapping clips in export sequence" }
        return placed.startUs - previousEndUs
    }

    /** A split is an editing boundary, but identical adjacent pieces need not restart a decoder. */
    fun coalesce(clips: List<TimedVideoClip>): List<TimedVideoClip> {
        if (clips.size < 2) return clips
        val result = mutableListOf<TimedVideoClip>()
        clips.forEach { placed ->
            val previous = result.lastOrNull()
            if (previous != null && previous.endUs == placed.startUs && canCoalesce(previous.clip, placed.clip)) {
                val joined = previous.clip.copy(outUs = placed.clip.outUs, transitionOut = placed.clip.transitionOut)
                result[result.lastIndex] = previous.copy(clip = joined)
            } else result += placed
        }
        return result
    }

    private fun canCoalesce(left: VideoClip, right: VideoClip): Boolean {
        if (left.uri != right.uri || left.outUs != right.inUs || left.image != right.image) return false
        return left.copy(id = right.id, outUs = right.outUs, transitionOut = right.transitionOut) ==
            right.copy(inUs = left.inUs, transitionIn = left.transitionIn)
    }

    fun playbackBoundaries(project: Project): List<Long> = layers(project).flatMap { layer ->
        layer.clips.dropLast(1).map { it.endUs }
    }.filterNot { boundaryUs ->
        project.transitions.any { transition ->
            boundaryUs in transition.startUs(project)..transition.endUs(project)
        }
    }.distinct().sorted()

    fun sourceTime(clip: VideoClip, startUs: Long, projectTimeUs: Long): Long =
        clip.timeMap.sourceAt(projectTimeUs - startUs)
}
