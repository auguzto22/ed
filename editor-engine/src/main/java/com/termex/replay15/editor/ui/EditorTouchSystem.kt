package com.termex.replay15.editor.ui

import android.graphics.Rect
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import com.termex.replay15.editor.domain.*
import kotlin.math.*

/**
 * Composite touch delegate that expands the clickable bounds of multiple child views
 * to reach the required minimum interactive size (e.g. 48dp) without altering their
 * compact visual appearance. Overlapping touch areas resolve to the nearest center.
 */
class CompositeTouchDelegate(private val parentView: View) : TouchDelegate(emptyRect, parentView) {
    data class Entry(val targetView: View, val bounds: Rect, val slopBounds: Rect)

    private val entries = mutableListOf<Entry>()

    fun add(view: View, targetBounds: Rect) {
        entries.removeAll { it.targetView == view }
        val slopBounds = Rect(targetBounds)
        val slop = (12 * view.resources.displayMetrics.density).toInt()
        slopBounds.inset(-slop, -slop)
        entries.add(Entry(view, targetBounds, slopBounds))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.toInt()
        val y = event.y.toInt()

        var bestEntry: Entry? = null
        var bestDistSq = Long.MAX_VALUE

        for (entry in entries) {
            if (!entry.targetView.isShown || !entry.targetView.isClickable) continue
            if (entry.bounds.contains(x, y)) {
                val cx = entry.bounds.centerX()
                val cy = entry.bounds.centerY()
                val distSq = ((x - cx).toLong() * (x - cx) + (y - cy).toLong() * (y - cy))
                if (distSq < bestDistSq) {
                    bestDistSq = distSq
                    bestEntry = entry
                }
            }
        }

        if (bestEntry != null) {
            val originalX = event.x
            val originalY = event.y
            event.setLocation(bestEntry.bounds.exactCenterX(), bestEntry.bounds.exactCenterY())
            val handled = bestEntry.targetView.dispatchTouchEvent(event)
            event.setLocation(originalX, originalY)
            return handled
        }
        return false
    }

    companion object {
        private val emptyRect = Rect()
    }
}

/**
 * Ensures an interactive view possesses at least [minSizeDp] x [minSizeDp] touch area
 * by registering an expanded hit rectangle on its parent container.
 */
fun View.expandTouchTarget(minSizeDp: Int = 48) {
    val parentGroup = parent as? ViewGroup ?: return
    post {
        if (!isAttachedToWindow) return@post
        val rect = Rect()
        getHitRect(rect)
        val density = resources.displayMetrics.density
        val minPx = (minSizeDp * density).toInt()
        val dx = maxOf(0, (minPx - rect.width()) / 2)
        val dy = maxOf(0, (minPx - rect.height()) / 2)
        rect.left -= dx
        rect.top -= dy
        rect.right += dx
        rect.bottom += dy

        val delegate = parentGroup.touchDelegate as? CompositeTouchDelegate
            ?: CompositeTouchDelegate(parentGroup).also { parentGroup.touchDelegate = it }
        delegate.add(this, rect)
    }
}

/**
 * Resolved touch candidates on the custom Timeline Canvas.
 */
sealed interface TimelineHitTarget {
    data class KeyframeTarget(
        val clipIndex: Int,
        val keyIndex: Int,
        val sourceUs: Long,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class TrimTarget(
        val clipIndex: Int,
        val side: Int, // -1: start trim, 1: end trim
        val boundaryUs: Long,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class TransitionTarget(
        val leftClipId: String,
        val rightClipId: String,
        val transitionIndex: Int,
        val midUs: Long,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class PlayheadTarget(
        val positionUs: Long,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class MarkerTarget(
        val markerId: String,
        val timeUs: Long,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class TextTrimTarget(
        val textId: String,
        val side: Int, // -1: left/start handle, 1: right/end handle
        val boundaryUs: Long,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class TextBodyTarget(
        val textId: String,
        val textClip: TextClip,
    ) : TimelineHitTarget

    data class StickerTrimTarget(
        val stickerId: String,
        val side: Int, // -1: left/start handle, 1: right/end handle
        val boundaryUs: Long,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class StickerBodyTarget(
        val stickerId: String,
        val stickerClip: StickerClip,
    ) : TimelineHitTarget

    data class AudioTrimTarget(
        val audioId: String,
        val side: Int, // -1: left/start handle, 1: right/end handle
        val boundaryUs: Long,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class AudioFadeTarget(
        val audioId: String,
        val isFadeIn: Boolean,
        val durationUs: Long,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class AudioBodyTarget(
        val audioId: String,
        val audioClip: AudioClip,
    ) : TimelineHitTarget

    data class AudioVolumeKeyframeTarget(
        val audioId: String,
        val keyframeIndex: Int,
        val timeUs: Long,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class LayerTrimTarget(
        val trackId: String,
        val clipId: String,
        val side: Int,
        val distancePx: Float,
    ) : TimelineHitTarget

    data class LayerBodyTarget(
        val trackId: String,
        val item: TimedVideoClip,
    ) : TimelineHitTarget

    data class ClipBodyTarget(
        val clipIndex: Int,
        val clip: VideoClip,
    ) : TimelineHitTarget

    data class TrackHeaderTarget(
        val trackId: String,
        val type: Int,
    ) : TimelineHitTarget
}

data class TimelineSegmentSnapshot(val id: String, val startUs: Long, val endUs: Long)
data class TimelineLaneSnapshot(val type: Int, val segments: List<TimelineSegmentSnapshot>, val trackId: String = "")

/**
 * Active locked gesture state during an in-flight touch sequence.
 */
enum class TimelineGestureLock {
    NONE,
    SCRUB,
    PLAYHEAD_GRAB,
    TRIM_START,
    TRIM_END,
    KEYFRAME_DRAG,
    CLIP_REORDER,
    LANE_SCROLL,
    LAYER_DRAG,
    LAYER_TRIM,
    TEXT_TRIM_START,
    TEXT_TRIM_END,
    TEXT_DRAG,
    STICKER_TRIM_START,
    STICKER_TRIM_END,
    STICKER_DRAG,
    AUDIO_TRIM_START,
    AUDIO_TRIM_END,
    AUDIO_DRAG,
    AUDIO_FADE_IN,
    AUDIO_FADE_OUT,
    AUDIO_VOLUME_KEYFRAME,
}

/**
 * Context provided to the hit testing resolver to make intelligent, priority-based decisions.
 */
data class TimelineHitContext(
    val project: Project,
    val selectedClipIndex: Int,
    val selectedLayerId: String?,
    val pixelsPerSecond: Float,
    val positionUs: Long,
    val width: Float,
    val height: Float,
    val density: Float,
    val verticalScroll: Float,
    val gutterWidth: Float,
    val timeToX: (Long) -> Float,
    val xToTime: (Float) -> Long,
    val visualStart: (Int) -> Long,
    val visualEnd: (Int) -> Long,
    val visualTime: (Int, VideoClip, Long) -> Long,
    val transitionBoundaryAfter: (Int) -> Long,
    val getLaneAt: ((Int) -> TimelineLaneSnapshot?)? = null,
)

/**
 * Intelligent hit resolver for the timeline canvas.
 * Implements context-aware hit priority:
 * 1. Keyframes near touch (radius 24dp = 48dp target), prioritizing selected clip's keyframes.
 * 2. Trim handles on selected clip (radius 28dp ~ 56dp capture width).
 * 3. Transition buttons between clips at cut boundary (target 44-48dp).
 * 4. Playhead head/line direct grab (radius 20-32dp).
 * 5. Markers (radius 24dp).
 * 6. Layers / PIP tracks and their handles.
 * 7. Clip body selection.
 */
object TimelineHitResolver {
    fun resolve(
        touchX: Float,
        touchY: Float,
        context: TimelineHitContext,
    ): TimelineHitTarget? {
        val dp = { v: Float -> v * context.density }
        val p = context.project

        // 1. Header / Gutter tap
        if (touchX < context.gutterWidth) {
            if (touchY in dp(44f)..dp(124f)) {
                return TimelineHitTarget.TrackHeaderTarget(MAIN_TRACK, 3)
            }
            return null
        }

        // 2. Markers (top bar: y in 20dp..45dp)
        if (touchY in dp(20f)..dp(45f)) {
            val markerHit = p.markers.asSequence()
                .filter { it.timeUs <= p.durationUs }
                .map { m ->
                    val mx = context.timeToX(m.timeUs)
                    val dist = abs(touchX - mx)
                    TimelineHitTarget.MarkerTarget(m.id, m.timeUs, dist)
                }
                .filter { it.distancePx <= dp(24f) }
                .minByOrNull { it.distancePx }
            if (markerHit != null) return markerHit
        }

        // 3. Keyframes: evaluate keyframes in clips
        // Visual size: 10dp. Touchable hitbox: 48dp (radius 24dp).
        if (touchY in dp(42f)..dp(82f) && p.videos.isNotEmpty()) {
            val selectedIdx = context.selectedClipIndex
            // First check selected clip's keyframes if valid
            if (selectedIdx in p.videos.indices) {
                val clip = p.videos[selectedIdx]
                val keyHit = clip.keyframes.indices.asSequence()
                    .filter { clip.keyframes[it].sourceUs in clip.inUs..clip.outUs }
                    .map { keyIdx ->
                        val kTime = context.visualTime(selectedIdx, clip, clip.keyframes[keyIdx].sourceUs)
                        val kx = context.timeToX(kTime)
                        val dist = hypot(touchX - kx, touchY - dp(60f))
                        TimelineHitTarget.KeyframeTarget(selectedIdx, keyIdx, clip.keyframes[keyIdx].sourceUs, dist)
                    }
                    .filter { it.distancePx <= dp(24f) }
                    .minByOrNull { it.distancePx }
                if (keyHit != null) return keyHit
            }

            // Then check other clips
            val otherKeyHit = p.videos.indices.asSequence()
                .filter { it != selectedIdx }
                .flatMap { clipIdx ->
                    val clip = p.videos[clipIdx]
                    clip.keyframes.indices.asSequence()
                        .filter { clip.keyframes[it].sourceUs in clip.inUs..clip.outUs }
                        .map { keyIdx ->
                            val kTime = context.visualTime(clipIdx, clip, clip.keyframes[keyIdx].sourceUs)
                            val kx = context.timeToX(kTime)
                            val dist = hypot(touchX - kx, touchY - dp(60f))
                            TimelineHitTarget.KeyframeTarget(clipIdx, keyIdx, clip.keyframes[keyIdx].sourceUs, dist)
                        }
                }
                .filter { it.distancePx <= dp(24f) }
                .minByOrNull { it.distancePx }
            if (otherKeyHit != null) return otherKeyHit
        }

        // 4. Trim Handles on selected clip
        // Generous capture zone: 28dp radius (56dp wide) across the cut edge
        if (context.selectedClipIndex in p.videos.indices && touchY in dp(44f)..dp(124f)) {
            val sIdx = context.selectedClipIndex
            val startUs = context.visualStart(sIdx)
            val endUs = context.visualEnd(sIdx)
            val startX = context.timeToX(startUs)
            val endX = context.timeToX(endUs)
            val distStart = abs(touchX - startX)
            val distEnd = abs(touchX - endX)
            val trimRadius = dp(28f)

            if (distStart <= trimRadius || distEnd <= trimRadius) {
                return if (distStart <= distEnd) {
                    TimelineHitTarget.TrimTarget(sIdx, -1, startUs, distStart)
                } else {
                    TimelineHitTarget.TrimTarget(sIdx, 1, endUs, distEnd)
                }
            }
        }

        // 5. Transitions between adjacent clips
        // Visual button 18x22dp, touch area 48dp x 48dp (radius 24dp)
        if (touchY in dp(56f)..dp(110f) && p.videos.size >= 2) {
            for (i in 0 until p.videos.size - 1) {
                val midUs = context.transitionBoundaryAfter(i)
                val jx = context.timeToX(midUs)
                val jy = dp(83f)
                val dist = hypot(touchX - jx, touchY - jy)
                if (dist <= dp(24f)) {
                    val left = p.videos[i]
                    val right = p.videos[i + 1]
                    return TimelineHitTarget.TransitionTarget(left.id, right.id, i, midUs, dist)
                }
            }
        }

        // 6. Playhead Top Pin & Direct Line Grab
        // Playhead visual: 1.5dp line with 7dp circle at top (width / 2f, dp(43f)).
        // Touch target: 36dp area around the pin.
        val playheadX = context.width / 2f
        val distToPlayheadPin = hypot(touchX - playheadX, touchY - dp(43f))
        if (distToPlayheadPin <= dp(22f)) {
            return TimelineHitTarget.PlayheadTarget(context.positionUs, distToPlayheadPin)
        }

        // 7. Layer tracks (y >= 130dp)
        if (touchY >= dp(130f)) {
            val laneIndex = ((touchY - dp(132f) + context.verticalScroll) / dp(39f)).toInt()
            if (laneIndex >= 0) {
                val touchTimeUs = context.xToTime(touchX)
                val laneSnapshot = context.getLaneAt?.invoke(laneIndex)
                if (laneSnapshot != null) {
                    when (laneSnapshot.type) {
                        0 -> { // Audio track
                            val selectedSegment = if (context.selectedLayerId != null) {
                                laneSnapshot.segments.firstOrNull { it.id == context.selectedLayerId }
                            } else null

                            val selectedAudio = if (selectedSegment != null) {
                                p.audio.firstOrNull { it.id == selectedSegment.id }
                            } else null

                            if (selectedAudio != null) {
                                val startX = context.timeToX(selectedAudio.startUs)
                                val endX = context.timeToX(selectedAudio.startUs + selectedAudio.durationUs)
                                val laneY = dp(132f) + laneIndex * dp(39f) - context.verticalScroll

                                // 1: Fade Handles at Top-Left and Top-Right
                                val fadeInX = startX + (selectedAudio.fadeInUs / com.termex.replay15.editor.domain.SECOND.toFloat() * context.pixelsPerSecond)
                                val fadeOutX = endX - (selectedAudio.fadeOutUs / com.termex.replay15.editor.domain.SECOND.toFloat() * context.pixelsPerSecond)
                                val fadeHandleY = laneY + dp(6f)

                                val dFadeIn = hypot(touchX - fadeInX, touchY - fadeHandleY)
                                val dFadeOut = hypot(touchX - fadeOutX, touchY - fadeHandleY)
                                val fadeRadius = dp(18f)

                                if (dFadeIn <= fadeRadius || dFadeOut <= fadeRadius) {
                                    return if (dFadeIn <= dFadeOut) {
                                        TimelineHitTarget.AudioFadeTarget(selectedAudio.id, true, selectedAudio.fadeInUs, dFadeIn)
                                    } else {
                                        TimelineHitTarget.AudioFadeTarget(selectedAudio.id, false, selectedAudio.fadeOutUs, dFadeOut)
                                    }
                                }

                                // 2: Trim Handles on selected audio clip
                                val dStart = abs(touchX - startX)
                                val dEnd = abs(touchX - endX)
                                val audioHandleRadius = dp(16f)
                                if (dStart <= audioHandleRadius || dEnd <= audioHandleRadius) {
                                    return if (dStart <= dEnd) {
                                        TimelineHitTarget.AudioTrimTarget(selectedAudio.id, -1, selectedAudio.startUs, dStart)
                                    } else {
                                        TimelineHitTarget.AudioTrimTarget(selectedAudio.id, 1, selectedAudio.startUs + selectedAudio.durationUs, dEnd)
                                    }
                                }

                                // 3: Volume Keyframe Target
                                if (selectedAudio.volumeKeyframes.isNotEmpty()) {
                                    val keyHit = selectedAudio.volumeKeyframes.withIndex().firstOrNull { (_, kf) ->
                                        val kfX = context.timeToX(selectedAudio.startUs + kf.timeUs)
                                        hypot(touchX - kfX, touchY - (laneY + dp(17f))) <= dp(18f)
                                    }
                                    if (keyHit != null) {
                                        return TimelineHitTarget.AudioVolumeKeyframeTarget(selectedAudio.id, keyHit.index, keyHit.value.timeUs, dp(18f))
                                    }
                                }
                            }

                            // 4: Audio Clip Body
                            val hitSegment = laneSnapshot.segments.firstOrNull {
                                val sX = context.timeToX(it.startUs)
                                val eX = context.timeToX(it.endUs)
                                touchX in (sX - dp(4f))..(eX + dp(4f)) || touchTimeUs in it.startUs until it.endUs
                            } ?: selectedSegment

                            if (hitSegment != null) {
                                val audioClip = p.audio.firstOrNull { it.id == hitSegment.id }
                                if (audioClip != null) {
                                    return TimelineHitTarget.AudioBodyTarget(hitSegment.id, audioClip)
                                }
                            }
                        }
                        1 -> { // Text track
                            // 1 & 2: Handles on selected text clip
                            val selectedSegment = if (context.selectedLayerId != null) {
                                laneSnapshot.segments.firstOrNull { it.id == context.selectedLayerId }
                            } else null

                            if (selectedSegment != null) {
                                val startX = context.timeToX(selectedSegment.startUs)
                                val endX = context.timeToX(selectedSegment.endUs)
                                val dStart = abs(touchX - startX)
                                val dEnd = abs(touchX - endX)
                                val textHandleRadius = dp(16f) // 32dp touch width
                                if (dStart <= textHandleRadius || dEnd <= textHandleRadius) {
                                    return if (dStart <= dEnd) {
                                        TimelineHitTarget.TextTrimTarget(selectedSegment.id, -1, selectedSegment.startUs, dStart)
                                    } else {
                                        TimelineHitTarget.TextTrimTarget(selectedSegment.id, 1, selectedSegment.endUs, dEnd)
                                    }
                                }
                            }

                            // 3: Text Clip Body
                            val hitSegment = laneSnapshot.segments.firstOrNull {
                                val sX = context.timeToX(it.startUs)
                                val eX = context.timeToX(it.endUs)
                                touchX in (sX - dp(4f))..(eX + dp(4f)) || touchTimeUs in it.startUs until it.endUs
                            } ?: selectedSegment

                            if (hitSegment != null) {
                                val textClip = p.texts.firstOrNull { it.id == hitSegment.id }
                                if (textClip != null) {
                                    return TimelineHitTarget.TextBodyTarget(hitSegment.id, textClip)
                                }
                            }
                        }
                        2 -> { // Sticker / Image overlay track
                            val selectedSegment = if (context.selectedLayerId != null) {
                                laneSnapshot.segments.firstOrNull { it.id == context.selectedLayerId }
                            } else null

                            if (selectedSegment != null) {
                                val startX = context.timeToX(selectedSegment.startUs)
                                val endX = context.timeToX(selectedSegment.endUs)
                                val dStart = abs(touchX - startX)
                                val dEnd = abs(touchX - endX)
                                val stickerHandleRadius = dp(16f)
                                if (dStart <= stickerHandleRadius || dEnd <= stickerHandleRadius) {
                                    return if (dStart <= dEnd) {
                                        TimelineHitTarget.StickerTrimTarget(selectedSegment.id, -1, selectedSegment.startUs, dStart)
                                    } else {
                                        TimelineHitTarget.StickerTrimTarget(selectedSegment.id, 1, selectedSegment.endUs, dEnd)
                                    }
                                }
                            }

                            val hitSegment = laneSnapshot.segments.firstOrNull {
                                val sX = context.timeToX(it.startUs)
                                val eX = context.timeToX(it.endUs)
                                touchX in (sX - dp(4f))..(eX + dp(4f)) || touchTimeUs in it.startUs until it.endUs
                            } ?: selectedSegment

                            if (hitSegment != null) {
                                val stickerClip = p.stickers.firstOrNull { it.id == hitSegment.id }
                                if (stickerClip != null) {
                                    return TimelineHitTarget.StickerBodyTarget(hitSegment.id, stickerClip)
                                }
                            }
                        }
                        3 -> { // Video PIP track
                            val track = p.videoTracks.firstOrNull { it.id == laneSnapshot.trackId }
                            val item = track?.activeAt(touchTimeUs)
                            if (track != null && item != null && !p.trackState(track.id).locked) {
                                if (context.selectedLayerId == item.clip.id) {
                                    val startX = context.timeToX(item.startUs)
                                    val endX = context.timeToX(item.endUs)
                                    val dStart = abs(touchX - startX)
                                    val dEnd = abs(touchX - endX)
                                    val layerTrimRadius = dp(24f)
                                    if (dStart <= layerTrimRadius || dEnd <= layerTrimRadius) {
                                        return if (dStart <= dEnd) {
                                            TimelineHitTarget.LayerTrimTarget(track.id, item.clip.id, -1, dStart)
                                        } else {
                                            TimelineHitTarget.LayerTrimTarget(track.id, item.clip.id, 1, dEnd)
                                        }
                                    }
                                }
                                return TimelineHitTarget.LayerBodyTarget(track.id, item)
                            }
                        }
                    }
                } else {
                    // Fallback to checking video tracks if laneSnapshot not provided
                    for (track in p.videoTracks) {
                        val item = track.activeAt(touchTimeUs)
                        if (item != null && !p.trackState(track.id).locked) {
                            if (context.selectedLayerId == item.clip.id) {
                                val startX = context.timeToX(item.startUs)
                                val endX = context.timeToX(item.endUs)
                                val dStart = abs(touchX - startX)
                                val dEnd = abs(touchX - endX)
                                val layerTrimRadius = dp(24f)
                                if (dStart <= layerTrimRadius || dEnd <= layerTrimRadius) {
                                    return if (dStart <= dEnd) {
                                        TimelineHitTarget.LayerTrimTarget(track.id, item.clip.id, -1, dStart)
                                    } else {
                                        TimelineHitTarget.LayerTrimTarget(track.id, item.clip.id, 1, dEnd)
                                    }
                                }
                            }
                            return TimelineHitTarget.LayerBodyTarget(track.id, item)
                        }
                    }
                }
            }
        }

        // 8. Main Clip Body (y in 48dp..120dp)
        if (touchY in dp(48f)..dp(120f) && p.videos.isNotEmpty()) {
            val touchTimeUs = context.xToTime(touchX)
            val clipIndex = p.videos.indices.firstOrNull { touchTimeUs < context.visualEnd(it) } ?: p.videos.lastIndex
            if (clipIndex in p.videos.indices) {
                return TimelineHitTarget.ClipBodyTarget(clipIndex, p.videos[clipIndex])
            }
        }

        return null
    }
}

/**
 * Magnetic snapping engine with hysteresis to prevent rapid oscillating/jittering.
 * - enterThresholdDp: distance to latch into a snap point (~8dp)
 * - releaseThresholdDp: distance required to break free from the latched snap point (~14dp)
 */
class MagneticSnapper(
    var snappingEnabled: Boolean = true,
    var enterThresholdDp: Float = 8f,
    var releaseThresholdDp: Float = 14f,
) {
    var activeSnapTarget: Long? = null
        private set
    var lastHapticSnapTarget: Long? = null
        private set

    fun reset() {
        activeSnapTarget = null
        lastHapticSnapTarget = null
    }

    fun snap(
        timeUs: Long,
        targets: Sequence<Long>,
        pixelsPerSecond: Float,
        density: Float,
        onHaptic: () -> Unit = {},
    ): Long {
        if (!snappingEnabled) {
            activeSnapTarget = null
            return timeUs
        }

        val enterPx = enterThresholdDp * density
        val releasePx = releaseThresholdDp * density

        // If currently latched, verify if finger has moved beyond release threshold
        val current = activeSnapTarget
        if (current != null) {
            val distPx = abs(current - timeUs) / 1_000_000f * pixelsPerSecond
            if (distPx <= releasePx) {
                return current
            } else {
                activeSnapTarget = null
            }
        }

        // Search for nearest snap target within entry threshold
        var bestTarget: Long? = null
        var bestDistPx = Float.MAX_VALUE

        for (target in targets) {
            val distPx = abs(target - timeUs) / 1_000_000f * pixelsPerSecond
            if (distPx <= enterPx && distPx < bestDistPx) {
                bestDistPx = distPx
                bestTarget = target
            }
        }

        if (bestTarget != null) {
            activeSnapTarget = bestTarget
            if (bestTarget != lastHapticSnapTarget) {
                lastHapticSnapTarget = bestTarget
                onHaptic()
            }
            return bestTarget
        }

        return timeUs
    }
}

/**
 * Precision Drag Controller.
 * Allows switching between 1.0x normal movement and 0.25x precision movement for frame-perfect edits.
 */
class PrecisionDragController(
    var isPrecisionActive: Boolean = false,
    var precisionFactor: Float = 0.25f,
) {
    fun scaleDelta(deltaPx: Float): Float {
        return if (isPrecisionActive) deltaPx * precisionFactor else deltaPx
    }
}
