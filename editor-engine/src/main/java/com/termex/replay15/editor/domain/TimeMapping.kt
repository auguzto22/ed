package com.termex.replay15.editor.domain

import kotlin.math.roundToLong

/** The single conversion boundary between project time and media time. */
interface ClipTimeMapper {
    fun projectToSource(clip: VideoClip, clipStartUs: Long, projectTimeUs: Long): Long
    fun sourceToProject(clip: VideoClip, clipStartUs: Long, sourceTimeUs: Long): Long
}

/** Preview and export share [VideoClip.timeMap], including trims and speed ramps. */
object ProjectClipTimeMapper : ClipTimeMapper {
    override fun projectToSource(clip: VideoClip, clipStartUs: Long, projectTimeUs: Long): Long =
        clip.timeMap.sourceAt(projectTimeUs - clipStartUs).coerceIn(clip.inUs, (clip.outUs - 1L).coerceAtLeast(clip.inUs))

    override fun sourceToProject(clip: VideoClip, clipStartUs: Long, sourceTimeUs: Long): Long =
        clipStartUs + clip.timeMap.timelineAt(sourceTimeUs)
}

data class SpeedPoint(val sourceUs: Long, val speed: Float) {
    init { require(sourceUs >= 0 && speed in MIN_CLIP_SPEED..MAX_CLIP_SPEED) }
}

/** Source-anchored sampling keeps ramps intact when a clip is trimmed or split. */
class ClipTimeMap(private val clip: VideoClip) {
    data class Segment(val sourceUs: Long, val endUs: Long, val speed: Float, val timelineUs: Long) {
        val durationUs: Long get() = ((endUs - sourceUs) / speed.toDouble()).roundToLong()
    }

    private val full: List<Segment> = buildList {
        val boundaries = sortedSetOf(0L, clip.sourceUs)
        if (clip.speedCurve.isNotEmpty()) {
            val step = maxOf(20_000L, ((clip.sourceUs + 2047L) / 2048L + 19_999L) / 20_000L * 20_000L)
            var at = step
            while (at < clip.sourceUs) { boundaries.add(at); at += step }
            clip.speedCurve.forEach { boundaries.add(it.sourceUs) }
        }
        var output = 0L
        boundaries.zipWithNext().forEach { (start, end) ->
            val segment = Segment(start, end, speedAtSource(start + (end - start) / 2), output)
            add(segment); output += segment.durationUs
        }
    }
    val segments: List<Segment> = buildList {
        var output = 0L
        full.forEach { segment ->
            val start = maxOf(clip.inUs, segment.sourceUs); val end = minOf(clip.outUs, segment.endUs)
            if (end > start) {
                val item = Segment(start, end, segment.speed, output)
                add(item); output += item.durationUs
            }
        }
    }
    val durationUs: Long = segments.last().let { it.timelineUs + it.durationUs }

    private fun speedAtSource(timeUs: Long): Float {
        val points = clip.speedCurve
        if (points.isEmpty()) return clip.speed
        val upper = points.indexOfFirst { it.sourceUs > timeUs }
        if (upper == 0) return points.first().speed
        if (upper < 0) return points.last().speed
        val a = points[upper - 1]; val b = points[upper]
        val t = (timeUs - a.sourceUs).toDouble() / (b.sourceUs - a.sourceUs)
        return (a.speed + (b.speed - a.speed) * t).toFloat().coerceIn(MIN_CLIP_SPEED, MAX_CLIP_SPEED)
    }

    fun sourceAt(timelineUs: Long): Long {
        if (timelineUs <= 0) return clip.inUs
        if (timelineUs >= durationUs) return clip.outUs
        val index = segments.binarySearch { it.timelineUs.compareTo(timelineUs) }.let { if (it >= 0) it else -it - 2 }
        val item = segments[index.coerceAtLeast(0)]
        return (item.sourceUs + ((timelineUs - item.timelineUs) * item.speed.toDouble()).roundToLong()).coerceIn(item.sourceUs, item.endUs)
    }

    fun timelineAt(sourceUs: Long): Long {
        val source = sourceUs.coerceIn(clip.inUs, clip.outUs)
        val index = segments.binarySearch { it.sourceUs.compareTo(source) }.let { if (it >= 0) it else -it - 2 }
        val item = segments[index.coerceAtLeast(0)]
        return (item.timelineUs + ((source - item.sourceUs) / item.speed.toDouble()).roundToLong()).coerceIn(0, durationUs)
    }

    /** Trim handles may recover media outside the currently visible source window. */
    fun extendedSourceAt(timelineUs: Long): Long {
        if (timelineUs in 0..durationUs) return sourceAt(timelineUs)
        fun absoluteTime(source: Long): Long {
            val item = full.last { it.sourceUs <= source }
            return item.timelineUs + ((source - item.sourceUs) / item.speed.toDouble()).roundToLong()
        }
        val target = if (timelineUs < 0) absoluteTime(clip.inUs) + timelineUs
            else absoluteTime(clip.outUs) + timelineUs - durationUs
        val item = full.lastOrNull { it.timelineUs <= target } ?: full.first()
        return (item.sourceUs + ((target - item.timelineUs) * item.speed.toDouble()).roundToLong()).coerceIn(0, clip.sourceUs)
    }

    fun speedAtInput(relativeSourceUs: Long): Float = segmentAtInput(relativeSourceUs).speed
    fun nextInputChange(relativeSourceUs: Long): Long? = segmentAtInput(relativeSourceUs).endUs
        .takeIf { it < clip.outUs }?.minus(clip.inUs)
    private fun segmentAtInput(relativeSourceUs: Long): Segment {
        val source = (clip.inUs + relativeSourceUs.coerceAtLeast(0)).coerceAtMost(clip.outUs)
        val index = segments.binarySearch { it.sourceUs.compareTo(source) }.let { if (it >= 0) it else -it - 2 }
        return segments[index.coerceIn(0, segments.lastIndex)]
    }
}
