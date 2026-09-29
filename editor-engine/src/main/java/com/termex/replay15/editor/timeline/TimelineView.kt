package com.termex.replay15.editor.timeline

import com.termex.replay15.editor.core.PreviewPacingProbe
import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.*
import com.termex.replay15.editor.domain.*
import kotlin.math.*

class TimelineView(context: Context) : View(context) {
    private data class Segment(val id: String, val name: String, val start: Long, val end: Long, val speedBadge: String? = null)
    private data class Lane(val label: String, val color: Int, val type: Int, val segments: MutableList<Segment>, val trackId: String = "")
    private var lanes = listOf<Lane>()
    var project = Project(); set(value) { field = value; positionUs = positionUs.coerceIn(0, value.durationUs); buildLanes(); invalidate() }
    var selected = -1; set(value) { field = value; invalidate() }
    var positionUs = 0L; set(value) { field = value; invalidate() }
    var snapping = true
    var onSeek: (Long) -> Unit = {}
    /** High-frequency drag callback. The host coalesces decoder seeks while updating its UI. */
    var onScrub: (Long) -> Unit = {}
    var onScrubStart: () -> Unit = {}
    var onScrubEnd: () -> Unit = {}
    var onSelect: (Int) -> Unit = {}
    var onClearSelection: () -> Unit = {}
    var onTransitionClick: (String, String) -> Unit = { _, _ -> }
    var onTrim: (Int, Long, Long) -> Unit = { _, _, _ -> }
    var onMove: (Int, Int) -> Unit = { _, _ -> }
    var onKeyframeMove: (Int, Int, Long) -> Unit = { _, _, _ -> }
    var onText: (String) -> Unit = {}
    var onTextTrim: (String, Long, Long) -> Unit = { _, _, _ -> }
    var onTextMove: (String, Long, Long) -> Unit = { _, _, _ -> }
    var onTextPreviewChange: (String, Long, Long) -> Unit = { _, _, _ -> }
    var onTextKeyframeClick: (String, com.termex.replay15.editor.transform.TransformKeyframe) -> Unit = { _, _ -> }
    var onAudio: (String) -> Unit = {}
    var onAudioTrim: (String, Long, Long, Long) -> Unit = { _, _, _, _ -> }
    var onAudioMove: (String, Long) -> Unit = { _, _ -> }
    var onAudioFade: (String, Long, Long) -> Unit = { _, _, _ -> }
    var onAudioPreviewChange: (String, Long, Long, Long) -> Unit = { _, _, _, _ -> }
    var onAudioFadePreviewChange: (String, Long, Long) -> Unit = { _, _, _ -> }
    var onAudioVolumeKeyframeClick: (String, com.termex.replay15.editor.domain.VolumeKeyframe) -> Unit = { _, _ -> }
    var onAudioVolumeKeyframeMove: (String, Int, Long) -> Unit = { _, _, _ -> }
    var onSticker: (String) -> Unit = {}
    var onStickerTrim: (String, Long, Long) -> Unit = { _, _, _ -> }
    var onStickerMove: (String, Long, Long) -> Unit = { _, _, _ -> }
    var onStickerPreviewChange: (String, Long, Long) -> Unit = { _, _, _ -> }
    var onStickerKeyframeClick: (String, com.termex.replay15.editor.transform.TransformKeyframe) -> Unit = { _, _ -> }
    var onMarker: (String) -> Unit = {}
    var onAdjustment: (String) -> Unit = {}
    var onVideoLayer: (String, String) -> Unit = { _, _ -> }
    var onLayerMove: (String, String, Long) -> Unit = { _, _, _ -> }
    var onLayerTrim: (String, VideoClip, Long, Long) -> Unit = { _, _, _, _ -> }
    var onTrack: (String) -> Unit = {}
    var selectedLayerId: String? = null; set(value) { field = value; invalidate() }
    private var layerDrag: Pair<String, TimedVideoClip>? = null
    private var layerTrim = 0
    private var textDrag: Pair<String, TextClip>? = null
    private var textTrimSide = 0
    private var textDraftStartUs: Long? = null
    private var textDraftEndUs: Long? = null
    private var textInitialStartUs = 0L
    private var textInitialEndUs = 0L
    private var stickerDrag: Pair<String, StickerClip>? = null
    private var stickerTrimSide = 0
    private var stickerDraftStartUs: Long? = null
    private var stickerDraftEndUs: Long? = null
    private var stickerInitialStartUs = 0L
    private var stickerInitialEndUs = 0L
    private var audioDrag: Pair<String, AudioClip>? = null
    private var audioTrimSide = 0
    private var audioFadeSide = 0
    private var audioDraftStartUs: Long? = null
    private var audioDraftInUs: Long? = null
    private var audioDraftOutUs: Long? = null
    private var audioDraftFadeInUs: Long? = null
    private var audioDraftFadeOutUs: Long? = null
    private var audioInitialStartUs = 0L
    private var audioInitialInUs = 0L
    private var audioInitialOutUs = 0L
    private var audioInitialFadeInUs = 0L
    private var audioInitialFadeOutUs = 0L
    private var audioKeyframeDrag: Pair<String, Int>? = null
    private var audioKeyframeDraftTimeUs: Long? = null
    private var keyframeDrag: Pair<Int, Int>? = null
    private var keyframeDraftSourceUs: Long? = null
    val snapper = com.termex.replay15.editor.ui.MagneticSnapper()
    val precisionController = com.termex.replay15.editor.ui.PrecisionDragController()
    var isPrecisionMode: Boolean
        get() = precisionController.isPrecisionActive
        set(value) { precisionController.isPrecisionActive = value; invalidate() }
    private var isPlayheadGrabbed = false
    private var isDragging = false
    private var scrubSessionActive = false
    private var pendingTransition: Pair<String, String>? = null
    private var pendingMarkerId: String? = null
    private var pendingTrackHeader: String? = null
    private var pendingClipSelect = -1
    private var lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.NONE

    private fun beginScrubSession() {
        if (!scrubSessionActive) {
            scrubSessionActive = true
            onScrubStart()
        }
    }

    private fun endScrubSession() {
        if (scrubSessionActive) {
            scrubSessionActive = false
            onScrubEnd()
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbs = ThumbnailCache(context) { invalidate() }
    private val waves = com.termex.replay15.editor.audio.WaveformCache(context) { invalidate() }
    private var pixelsPerSecond = dp(56f)
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downPosition = 0L
    private var trimSide = 0
    private var moveTarget = -1
    private var delta = 0f
    private var vertical = 0f
    private var gesture = 0
    private var scaled = false
    private var lastSnapTarget: Long? = null
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaled = true
            trimSide = 0
            zoomAround(detector.scaleFactor, detector.focusX)
            return true
        }
    })
    init {
        setBackgroundColor(Color.BLACK); isFocusable = true
        contentDescription = "Linha do tempo. Arraste para buscar ou ver faixas. Pince para ampliar."
    }
    fun zoom(factor: Float) { zoomAround(factor, width / 2f) }

    fun zoomAround(factor: Float, focusX: Float) {
        if (width <= 0) return
        val focalTime = time(focusX)
        val oldPps = pixelsPerSecond
        val newPps = (oldPps * factor).coerceIn(dp(4f), dp(600f))
        if (newPps == oldPps) return
        pixelsPerSecond = newPps
        val newPositionUs = (focalTime - ((focusX - width / 2f) / newPps * SECOND).toLong()).coerceIn(0, project.durationUs)
        positionUs = newPositionUs
        onScrub(positionUs)
        invalidate()
    }
    fun fit() {
        pixelsPerSecond = ((width - dp(70f)) / (project.durationUs / SECOND.toFloat()).coerceAtLeast(1f)).coerceIn(dp(4f), dp(600f))
        invalidate()
    }
    private fun buildLanes() {
        val result = mutableListOf<Lane>()
        fun pack(segments: List<Segment>, prefix: String, color: Int, type: Int) {
            val packed = mutableListOf<Lane>()
            segments.sortedBy { it.start }.forEach { s ->
                val lane = packed.firstOrNull { it.segments.last().end <= s.start }
                    ?: Lane("$prefix${packed.size + 1}", color, type, mutableListOf(), if (type == 1) TEXT_TRACK else STICKER_TRACK).also { packed += it }
                lane.segments += s
            }
            result += packed
        }
        pack(project.adjustmentClips.map { Segment(it.id, it.name, it.startUs, it.endUs) }, "FX", 0xFFC39BFF.toInt(), 4)
        project.videoTracks.withIndex().toList().asReversed().forEach { (index, track) ->
            result += Lane("V${index + 2}", 0xFF89BCFF.toInt(), 3,
                track.clips.map { Segment(it.clip.id, it.clip.name, it.startUs, it.endUs, it.clip.speedBadge) }.toMutableList(), track.id)
        }
        project.audio.forEachIndexed { n, a -> result += Lane("A${n + 1}", 0xFF5BC7BC.toInt(), 0,
            mutableListOf(Segment(a.id, a.name, a.startUs, min(project.durationUs, a.startUs + a.durationUs))), "audio:${a.id}") }
        pack(project.texts.map { Segment(it.id, it.text.replace('\n', ' '), it.startUs, it.endUs) }, "T", 0xFFE6B965.toInt(), 1)
        pack(project.stickers.map { Segment(it.id, it.name, it.startUs, it.endUs) }, "L", 0xFF93A8F8.toInt(), 2)
        lanes = result; vertical = vertical.coerceIn(0f, maxScroll())
    }
    private fun maxScroll() = max(0f, lanes.size * dp(39f) - (height - dp(140f)))
    private fun x(time: Long) = width / 2f + (time - positionUs) / SECOND.toFloat() * pixelsPerSecond
    private fun time(xx: Float) = (positionUs + (xx - width / 2f) / pixelsPerSecond * SECOND).toLong().coerceIn(0, project.durationUs)
    private fun transitionBoundaryAfter(index: Int): Long {
        if (index !in 0 until project.videos.lastIndex) return project.durationUs
        val left = project.videos[index]
        val right = project.videos[index + 1]
        val transitionUs = project.transitionBetween(left.id, right.id)?.durationUs ?: 0L
        return project.startOf(index + 1) + transitionUs / 2
    }
    private fun visualStart(index: Int): Long =
        if (index <= 0) 0L else transitionBoundaryAfter(index - 1)
    private fun visualEnd(index: Int): Long =
        if (index >= project.videos.lastIndex) project.durationUs else transitionBoundaryAfter(index)
    private fun visualIndexAt(timeUs: Long): Int {
        if (project.videos.isEmpty()) return -1
        return project.videos.indices.firstOrNull { timeUs < visualEnd(it) } ?: project.videos.lastIndex
    }
    private fun visualTime(index: Int, clip: VideoClip, sourceUs: Long): Long {
        val start = visualStart(index)
        val duration = (visualEnd(index) - start).coerceAtLeast(1L)
        val localUs = clip.timeMap.timelineAt(sourceUs).coerceIn(0L, clip.durationUs)
        return start + (duration.toDouble() * localUs / clip.durationUs.coerceAtLeast(1L)).toLong()
    }
    private fun sourceAtVisualPosition(index: Int, clip: VideoClip, xx: Float): Long {
        val start = visualStart(index)
        val duration = (visualEnd(index) - start).coerceAtLeast(1L)
        val localUs = ((time(xx) - start).toDouble() / duration * clip.durationUs)
            .toLong().coerceIn(0L, clip.durationUs)
        return clip.timeMap.sourceAt(localUs)
    }
    private fun snap(t: Long): Long {
        if (!snapping) return t
        val targets = sequence {
            yieldAll(project.videos.indices.map(::visualStart))
            yieldAll(project.videos.indices.map(::visualEnd))
            yield(project.durationUs)
            yieldAll(project.markers.map { it.timeUs })
            yieldAll(project.camera.keyframes.map { it.timeUs })
            yieldAll(project.videoTracks.flatMap { it.clips.flatMap { c -> listOf(c.startUs, c.endUs) } })
            yieldAll(project.adjustmentClips.flatMap { listOf(it.startUs, it.endUs) })
            yieldAll(project.texts.filter { it.id != (textDrag?.first ?: "") }.flatMap { listOf(it.startUs, it.endUs) })
            yieldAll(project.stickers.filter { it.id != (stickerDrag?.first ?: "") }.flatMap { listOf(it.startUs, it.endUs) })
            yieldAll(project.audio.filter { it.id != (audioDrag?.first ?: "") }.flatMap { listOf(it.startUs, it.endUs) })
            yieldAll(project.texts.flatMap { it.transformKeyframes.map { kf -> kf.timeUs } })
            yieldAll(project.stickers.flatMap { it.transformKeyframes.map { kf -> kf.timeUs } })
            yieldAll(project.audio.flatMap { it.volumeKeyframes.map { kf -> it.startUs + kf.timeUs } })
            yield(positionUs)
        }
        return snapper.snap(t, targets, pixelsPerSecond, resources.displayMetrics.density) {
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
    private fun formatTimecodeFrames(us: Long, fps: Int): String {
        val safeUs = us.coerceAtLeast(0L)
        val totalSec = safeUs / 1_000_000L
        val frames = ((safeUs % 1_000_000L) * fps / 1_000_000L).toInt()
        val m = (totalSec / 60).toInt()
        val s = (totalSec % 60).toInt()
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", m, s, frames)
    }
    private fun text(c: Canvas, value: String, xx: Float, yy: Float, color: Int = Color.WHITE, size: Float = 10f) {
        paint.color = color; paint.textSize = dp(size); paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        c.drawText(value, xx, yy, paint)
    }
    private fun drawSpeedBadge(c: Canvas, value: String?, left: Float, right: Float, top: Float, bottom: Float) {
        if (value == null || right - left < dp(34f)) return
        paint.textSize = dp(10f); paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val textWidth = paint.measureText(value)
        val badgeWidth = textWidth + dp(12f)
        val badgeRight = right - dp(5f)
        val badgeLeft = max(left + dp(5f), badgeRight - badgeWidth)
        if (badgeRight - badgeLeft < textWidth + dp(7f)) return
        val badgeTop = top + dp(5f)
        val badgeBottom = min(bottom - dp(4f), badgeTop + dp(20f))
        paint.color = 0xD9000000.toInt(); paint.style = Paint.Style.FILL
        c.drawRoundRect(badgeLeft, badgeTop, badgeRight, badgeBottom, dp(6f), dp(6f), paint)
        paint.color = Color.WHITE
        c.drawText(value, badgeLeft + (badgeRight - badgeLeft - textWidth) / 2f, badgeBottom - dp(5f), paint)
    }
    override fun onDraw(c: Canvas) {
        val started = PreviewPacingProbe.begin("Recly.TimelineView.draw")
        try { drawMeasured(c) }
        finally { PreviewPacingProbe.end("Recly.TimelineView.draw", started) }
    }
    private fun drawMeasured(c: Canvas) {
        super.onDraw(c)
        val gutter = dp(40f)
        c.save(); c.clipRect(gutter, 0f, width.toFloat(), height.toFloat())
        val step = when { pixelsPerSecond >= dp(150f) -> SECOND / 2; pixelsPerSecond >= dp(40f) -> SECOND; pixelsPerSecond >= dp(15f) -> 5 * SECOND; else -> 10 * SECOND }
        var tick = time(gutter) / step * step
        while (tick <= project.durationUs && x(tick) <= width) {
            val xx = x(tick)
            text(c, java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d", tick / SECOND / 60, tick / SECOND % 60), xx + dp(3f), dp(16f), 0xFF8E8E93.toInt(), 9f)
            paint.color = 0xFF222222.toInt(); paint.strokeWidth = dp(1f)
            c.drawLine(xx, dp(22f), xx, height.toFloat(), paint); tick += step
        }
        project.camera.keyframes.forEach { key ->
            val xx = x(key.timeUs)
            if (xx >= gutter && xx <= width) text(c, "◆", xx - dp(4f), dp(30f),
                if (kotlin.math.abs(key.timeUs - positionUs) <= 20_000L) Color.WHITE else 0xFFB58AFF.toInt(), 10f)
        }
        // Magnetic snap guide line
        snapper.activeSnapTarget?.let { snapT ->
            val sx = x(snapT)
            if (sx in gutter..width.toFloat()) {
                paint.color = Color.WHITE
                paint.strokeWidth = dp(1.5f)
                c.drawLine(sx, dp(22f), sx, height.toFloat(), paint)
                paint.style = Paint.Style.FILL
                c.drawCircle(sx, dp(24f), dp(3.5f), paint)
            }
        }
        project.markers.filter { it.timeUs <= project.durationUs }.forEach { m ->
            val xx = x(m.timeUs); paint.color = m.color; c.drawCircle(xx, dp(34f), dp(4f), paint)
            text(c, m.name, xx + dp(7f), dp(37f), m.color, 9f)
        }
        project.videos.forEachIndexed { index, clip ->
            val l = x(visualStart(index)); val r = x(visualEnd(index))
            if (r >= gutter && l <= width) {
                paint.color = 0xFF161616.toInt(); c.drawRoundRect(l, dp(50f), r - dp(2f), dp(116f), dp(8f), dp(8f), paint)
                c.save(); c.clipRect(l, dp(50f), r - dp(2f), dp(116f))
                var xx = l + floor(max(0f, gutter - l) / dp(64f)) * dp(64f)
                while (xx < min(r, width.toFloat())) {
                    val source = sourceAtVisualPosition(index, clip, xx)
                    thumbs.get(clip, source.coerceIn(clip.inUs, clip.outUs - 1))?.let { bitmap ->
                        c.drawBitmap(bitmap, null, RectF(xx, dp(50f), xx + dp(64f), dp(116f)), null)
                    }
                    xx += dp(64f)
                }
                paint.color = 0xAA000000.toInt(); c.drawRect(l, dp(96f), r, dp(116f), paint)
                text(c, clip.name, max(l, gutter) + dp(6f), dp(110f))
                drawSpeedBadge(c, clip.speedBadge, max(l, gutter), min(r, width.toFloat()), dp(50f), dp(96f))
                clip.keyframes.forEachIndexed { keyIndex, key ->
                    if (key.sourceUs !in clip.inUs..clip.outUs) return@forEachIndexed
                    val shownSource = if (keyframeDrag == index to keyIndex) keyframeDraftSourceUs ?: key.sourceUs else key.sourceUs
                    val kx = x(visualTime(index, clip, shownSource))
                    val isUnderPlayhead = abs(kx - width / 2f) < dp(3f)
                    val isKeyDrag = keyframeDrag == index to keyIndex
                    val isCurrentClipSelected = index == selected
                    paint.color = when {
                        isKeyDrag -> Color.WHITE
                        isUnderPlayhead -> 0xFFFFFFFF.toInt()
                        isCurrentClipSelected -> Color.WHITE
                        else -> 0xCCFFFFFF.toInt()
                    }
                    c.save(); c.rotate(45f, kx, dp(60f))
                    c.drawRect(kx - dp(4.5f), dp(55.5f), kx + dp(4.5f), dp(64.5f), paint)
                    if (isKeyDrag || isUnderPlayhead) {
                        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f)
                        c.drawRect(kx - dp(5.5f), dp(54.5f), kx + dp(5.5f), dp(65.5f), paint)
                        paint.style = Paint.Style.FILL
                    }
                    c.restore()
                }
                c.restore()
                if (index == selected) {
                    val left = l + if (trimSide == -1) delta else 0f; val right = r + if (trimSide == 1) delta else 0f
                    paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f)
                    c.drawRoundRect(left, dp(48f), right, dp(118f), dp(8f), dp(8f), paint); paint.style = Paint.Style.FILL
                    paint.color = Color.WHITE
                    c.drawRoundRect(left - dp(3.5f), dp(68f), left + dp(4.5f), dp(98f), dp(3.5f), dp(3.5f), paint)
                    paint.color = Color.WHITE
                    c.drawRoundRect(right - dp(4.5f), dp(68f), right + dp(3.5f), dp(98f), dp(3.5f), dp(3.5f), paint)

                    // Professional Trim Preview HUD
                    if (trimSide != 0 && gesture == 1) {
                        val shift = (delta / pixelsPerSecond * SECOND).toLong()
                        val liveDurationUs = when (trimSide) {
                            -1 -> (clip.outUs - (clip.inUs + shift)).coerceAtLeast(MIN_CLIP)
                            1 -> ((clip.outUs + shift) - clip.inUs).coerceAtLeast(MIN_CLIP)
                            else -> clip.durationUs
                        }
                        val fps = project.export.fps.coerceAtLeast(1)
                        val badgeText = "${if (trimSide < 0) "IN" else "OUT"}  ${formatTimecodeFrames(liveDurationUs, fps)}"
                        paint.textSize = dp(11f); paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        val textW = paint.measureText(badgeText)
                        val badgeX = (if (trimSide < 0) left else right - textW - dp(16f)).coerceIn(gutter + dp(8f), width - textW - dp(24f))
                        val badgeY = dp(36f)
                        paint.color = 0xEE18181C.toInt(); paint.style = Paint.Style.FILL
                        c.drawRoundRect(badgeX - dp(8f), badgeY - dp(14f), badgeX + textW + dp(8f), badgeY + dp(6f), dp(6f), dp(6f), paint)
                        paint.color = 0xFF3F3F46.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f)
                        c.drawRoundRect(badgeX - dp(8f), badgeY - dp(14f), badgeX + textW + dp(8f), badgeY + dp(6f), dp(6f), dp(6f), paint)
                        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                        c.drawText(badgeText, badgeX, badgeY, paint)
                    }
                }
            }
        }
        // Desenha indicadores ◇ de transição entre clips adjacentes
        for (i in 0 until project.videos.size - 1) {
            val leftClip = project.videos[i]
            val rightClip = project.videos[i + 1]
            val trans = project.transitionBetween(leftClip.id, rightClip.id)
            val midUs = transitionBoundaryAfter(i)
            val jx = x(midUs)
            if (jx >= gutter - dp(20f) && jx <= width + dp(20f)) {
                val cy = dp(83f)
                val btnW = dp(18f)
                val btnH = dp(22f)
                val rect = RectF(jx - btnW / 2, cy - btnH / 2, jx + btnW / 2, cy + btnH / 2)
                if (trans != null) {
                    paint.color = 0xFF3F3F46.toInt()
                    paint.style = Paint.Style.FILL
                    c.drawRoundRect(rect, dp(6f), dp(6f), paint)
                    paint.color = Color.WHITE
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1.5f)
                    c.drawRoundRect(rect, dp(6f), dp(6f), paint)
                    paint.style = Paint.Style.FILL
                    val path = Path().apply {
                        moveTo(jx, cy - dp(5f))
                        lineTo(jx + dp(4.5f), cy)
                        lineTo(jx, cy + dp(5f))
                        lineTo(jx - dp(4.5f), cy)
                        close()
                    }
                    c.drawPath(path, paint)
                } else {
                    paint.color = 0xEE222228.toInt()
                    paint.style = Paint.Style.FILL
                    c.drawRoundRect(rect, dp(5f), dp(5f), paint)
                    paint.color = 0x88FFFFFF.toInt()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1f)
                    c.drawRoundRect(rect, dp(5f), dp(5f), paint)
                    val path = Path().apply {
                        moveTo(jx, cy - dp(4f))
                        lineTo(jx + dp(3.5f), cy)
                        lineTo(jx, cy + dp(4f))
                        lineTo(jx - dp(3.5f), cy)
                        close()
                    }
                    paint.style = Paint.Style.STROKE
                    paint.color = 0xDDFFFFFF.toInt()
                    c.drawPath(path, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
        c.save(); c.clipRect(gutter, dp(130f), width.toFloat(), height.toFloat())
        lanes.forEachIndexed { i, lane ->
            val y = dp(132f) + i * dp(39f) - vertical
            if (y + dp(35f) >= dp(130f) && y < height) {
                paint.color = 0xFF121212.toInt(); c.drawRect(gutter, y, width.toFloat(), y + dp(34f), paint)
                lane.segments.forEach { s ->
                    val isTextTrack = lane.type == 1
                    val isStickerTrack = lane.type == 2
                    val isAudioTrack = lane.type == 0
                    val isCurrentTextDragged = isTextTrack && textDrag?.first == s.id
                    val isCurrentStickerDragged = isStickerTrack && stickerDrag?.first == s.id
                    val isCurrentAudioDragged = isAudioTrack && audioDrag?.first == s.id

                    val liveStart = when {
                        isCurrentTextDragged && textDraftStartUs != null -> textDraftStartUs!!
                        isCurrentStickerDragged && stickerDraftStartUs != null -> stickerDraftStartUs!!
                        isCurrentAudioDragged && audioDraftStartUs != null -> audioDraftStartUs!!
                        else -> s.start
                    }
                    val liveEnd = when {
                        isCurrentTextDragged && textDraftEndUs != null -> textDraftEndUs!!
                        isCurrentStickerDragged && stickerDraftEndUs != null -> stickerDraftEndUs!!
                        isCurrentAudioDragged -> {
                            val audioClip = project.audio.firstOrNull { it.id == s.id }
                            val curIn = if (audioDraftInUs != null) audioDraftInUs!! else (audioClip?.inUs ?: 0L)
                            val curOut = if (audioDraftOutUs != null) audioDraftOutUs!! else (audioClip?.outUs ?: s.end - s.start)
                            liveStart + (curOut - curIn)
                        }
                        else -> s.end
                    }
                    val l = x(liveStart); val r = x(liveEnd)
                    if (r > gutter && l < width && r > l) {
                        paint.color = (lane.color and 0xFFFFFF) or 0x55000000
                        c.drawRoundRect(l, y, r, y + dp(33f), dp(6f), dp(6f), paint)

                        if (isAudioTrack) {
                            val audio = project.audio.firstOrNull { it.id == s.id }
                            val wave = audio?.let { waves.get(it.uri, it.sourceUs) }
                            val liveIn = if (isCurrentAudioDragged && audioDraftInUs != null) audioDraftInUs!! else (audio?.inUs ?: 0L)
                            val liveFadeIn = if (isCurrentAudioDragged && audioDraftFadeInUs != null) audioDraftFadeInUs!! else (audio?.fadeInUs ?: 0L)
                            val liveFadeOut = if (isCurrentAudioDragged && audioDraftFadeOutUs != null) audioDraftFadeOutUs!! else (audio?.fadeOutUs ?: 0L)

                            if (audio != null && wave != null) {
                                paint.color = 0x885BC7BC.toInt(); paint.strokeWidth = dp(1f)
                                var px = max(gutter, l)
                                while (px < min(r, width.toFloat())) {
                                    val amplitude = wave.at(liveIn + time(px) - liveStart) * dp(14f)
                                    c.drawLine(px, y + dp(17f) - amplitude, px, y + dp(17f) + amplitude, paint)
                                    px += dp(2f)
                                }
                            }

                            val isSelectedAudio = s.id == selectedLayerId || isCurrentAudioDragged
                            if (isSelectedAudio && audio != null) {
                                // 1. Visible Contour
                                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.color = 0xFF5BC7BC.toInt()
                                c.drawRoundRect(l, y, r, y + dp(33f), dp(6f), dp(6f), paint)
                                paint.style = Paint.Style.FILL

                                // 2. Left Handle (|◀|) & Right Handle (|▶|)
                                val handleW = dp(14f).coerceAtMost((r - l) / 2f)
                                if (handleW >= dp(6f)) {
                                    c.save()
                                    c.clipRect(l, y, l + handleW, y + dp(33f))
                                    paint.color = 0xFF80E5DA.toInt()
                                    c.drawRoundRect(l, y, l + handleW * 2f, y + dp(33f), dp(6f), dp(6f), paint)
                                    paint.color = 0xFF1C1C1E.toInt(); paint.strokeWidth = dp(1.5f); paint.style = Paint.Style.STROKE
                                    c.drawLine(l + dp(4f), y + dp(9f), l + dp(4f), y + dp(24f), paint)
                                    val leftPath = Path().apply {
                                        moveTo(l + dp(6.5f), y + dp(12f))
                                        lineTo(l + dp(10.5f), y + dp(16.5f))
                                        lineTo(l + dp(6.5f), y + dp(21f))
                                    }
                                    c.drawPath(leftPath, paint)
                                    paint.style = Paint.Style.FILL
                                    c.restore()

                                    c.save()
                                    c.clipRect(r - handleW, y, r, y + dp(33f))
                                    paint.color = 0xFF80E5DA.toInt()
                                    c.drawRoundRect(r - handleW * 2f, y, r, y + dp(33f), dp(6f), dp(6f), paint)
                                    paint.color = 0xFF1C1C1E.toInt(); paint.strokeWidth = dp(1.5f); paint.style = Paint.Style.STROKE
                                    c.drawLine(r - dp(4f), y + dp(9f), r - dp(4f), y + dp(24f), paint)
                                    val rightPath = Path().apply {
                                        moveTo(r - dp(6.5f), y + dp(12f))
                                        lineTo(r - dp(10.5f), y + dp(16.5f))
                                        lineTo(r - dp(6.5f), y + dp(21f))
                                    }
                                    c.drawPath(rightPath, paint)
                                    paint.style = Paint.Style.FILL
                                    c.restore()
                                }

                                // 3. Fade In & Fade Out Lines / Handles
                                if (liveFadeIn > 0L) {
                                    val fadeEndX = l + (liveFadeIn / SECOND.toFloat() * pixelsPerSecond).coerceAtMost(r - l)
                                    paint.color = 0xAA5BC7BC.toInt(); paint.strokeWidth = dp(1.5f); paint.style = Paint.Style.STROKE
                                    c.drawLine(l, y + dp(33f), fadeEndX, y, paint)
                                    paint.style = Paint.Style.FILL; paint.color = 0xFF80E5DA.toInt()
                                    c.drawCircle(fadeEndX, y + dp(4f), dp(3.5f), paint)
                                }
                                if (liveFadeOut > 0L) {
                                    val fadeStartX = r - (liveFadeOut / SECOND.toFloat() * pixelsPerSecond).coerceAtMost(r - l)
                                    paint.color = 0xAA5BC7BC.toInt(); paint.strokeWidth = dp(1.5f); paint.style = Paint.Style.STROKE
                                    c.drawLine(fadeStartX, y, r, y + dp(33f), paint)
                                    paint.style = Paint.Style.FILL; paint.color = 0xFF80E5DA.toInt()
                                    c.drawCircle(fadeStartX, y + dp(4f), dp(3.5f), paint)
                                }

                                // 4. Audio HUD Badge
                                if (isDragging && isCurrentAudioDragged && gesture == 8) {
                                    val fps = project.export.fps.coerceAtLeast(1)
                                    val badgeText = when {
                                        audioFadeSide < 0 -> "FADE IN  ${liveFadeIn / 1000L}ms"
                                        audioFadeSide > 0 -> "FADE OUT  ${liveFadeOut / 1000L}ms"
                                        audioTrimSide < 0 -> "IN  ${formatTimecodeFrames(liveStart, fps)}"
                                        audioTrimSide > 0 -> "OUT  ${formatTimecodeFrames(liveEnd, fps)}  (DUR ${formatTimecodeFrames(liveEnd - liveStart, fps)})"
                                        else -> "IN ${formatTimecodeFrames(liveStart, fps)} - OUT ${formatTimecodeFrames(liveEnd, fps)}"
                                    }
                                    paint.textSize = dp(10.5f); paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                                    val textW = paint.measureText(badgeText)
                                    val badgeX = (l + (r - l - textW) / 2f).coerceIn(gutter + dp(8f), width - textW - dp(20f))
                                    val badgeY = if (y - dp(18f) >= dp(130f)) y - dp(6f) else y + dp(48f)
                                    paint.color = 0xEE18181C.toInt(); paint.style = Paint.Style.FILL
                                    c.drawRoundRect(badgeX - dp(6f), badgeY - dp(13f), badgeX + textW + dp(6f), badgeY + dp(5f), dp(5f), dp(5f), paint)
                                    paint.color = 0xFF5BC7BC.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f)
                                    c.drawRoundRect(badgeX - dp(6f), badgeY - dp(13f), badgeX + textW + dp(6f), badgeY + dp(5f), dp(5f), dp(5f), paint)
                                    paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                                    c.drawText(badgeText, badgeX, badgeY, paint)
                                }
                            }

                            // Volume Keyframes
                            if (audio != null && audio.volumeKeyframes.isNotEmpty()) {
                                audio.volumeKeyframes.forEach { kf ->
                                    val kfTimelineUs = liveStart + kf.timeUs
                                    if (kfTimelineUs in liveStart..liveEnd) {
                                        val kx = x(kfTimelineUs)
                                        if (kx >= l - dp(4f) && kx <= r + dp(4f)) {
                                            val ky = y + dp(30f) - (kf.volume * dp(12f)).coerceIn(0f, dp(26f))
                                            val isUnderPlayhead = abs(kx - width / 2f) < dp(3f)
                                            val isCurrentSelected = s.id == selectedLayerId
                                            paint.color = when {
                                                isUnderPlayhead -> Color.WHITE
                                                isCurrentSelected -> 0xFF80E5DA.toInt()
                                                else -> 0xCC80E5DA.toInt()
                                            }
                                            c.drawCircle(kx, ky, dp(3.5f), paint)
                                            if (isUnderPlayhead) {
                                                paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f)
                                                c.drawCircle(kx, ky, dp(4.5f), paint)
                                                paint.style = Paint.Style.FILL
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (lane.type == 3 && s.id == selectedLayerId) {
                            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.color = Color.WHITE
                            c.drawRoundRect(l, y, r, y + dp(33f), dp(6f), dp(6f), paint); paint.style = Paint.Style.FILL
                        }

                        if (isStickerTrack) {
                            val isSelectedSticker = s.id == selectedLayerId || isCurrentStickerDragged
                            if (isSelectedSticker) {
                                // 1. Visible Contour
                                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.color = 0xFF93A8F8.toInt()
                                c.drawRoundRect(l, y, r, y + dp(33f), dp(6f), dp(6f), paint)
                                paint.style = Paint.Style.FILL

                                // 2. Left Handle (|◀|) & Right Handle (|▶|)
                                val handleW = dp(14f).coerceAtMost((r - l) / 2f)
                                if (handleW >= dp(6f)) {
                                    c.save()
                                    c.clipRect(l, y, l + handleW, y + dp(33f))
                                    paint.color = 0xFFB8C7FF.toInt()
                                    c.drawRoundRect(l, y, l + handleW * 2f, y + dp(33f), dp(6f), dp(6f), paint)
                                    paint.color = 0xFF1C1C1E.toInt(); paint.strokeWidth = dp(1.5f); paint.style = Paint.Style.STROKE
                                    c.drawLine(l + dp(4f), y + dp(9f), l + dp(4f), y + dp(24f), paint)
                                    val leftPath = Path().apply {
                                        moveTo(l + dp(6.5f), y + dp(12f))
                                        lineTo(l + dp(10.5f), y + dp(16.5f))
                                        lineTo(l + dp(6.5f), y + dp(21f))
                                    }
                                    c.drawPath(leftPath, paint)
                                    paint.style = Paint.Style.FILL
                                    c.restore()

                                    c.save()
                                    c.clipRect(r - handleW, y, r, y + dp(33f))
                                    paint.color = 0xFFB8C7FF.toInt()
                                    c.drawRoundRect(r - handleW * 2f, y, r, y + dp(33f), dp(6f), dp(6f), paint)
                                    paint.color = 0xFF1C1C1E.toInt(); paint.strokeWidth = dp(1.5f); paint.style = Paint.Style.STROKE
                                    c.drawLine(r - dp(4f), y + dp(9f), r - dp(4f), y + dp(24f), paint)
                                    val rightPath = Path().apply {
                                        moveTo(r - dp(6.5f), y + dp(12f))
                                        lineTo(r - dp(10.5f), y + dp(16.5f))
                                        lineTo(r - dp(6.5f), y + dp(21f))
                                    }
                                    c.drawPath(rightPath, paint)
                                    paint.style = Paint.Style.FILL
                                    c.restore()
                                }

                                // 3. Professional Trim / Move HUD Badge
                                if (isDragging && isCurrentStickerDragged && gesture == 7) {
                                    val fps = project.export.fps.coerceAtLeast(1)
                                    val badgeText = when {
                                        stickerTrimSide < 0 -> "IN  ${formatTimecodeFrames(liveStart, fps)}"
                                        stickerTrimSide > 0 -> "OUT  ${formatTimecodeFrames(liveEnd, fps)}  (DUR ${formatTimecodeFrames(liveEnd - liveStart, fps)})"
                                        else -> "IN ${formatTimecodeFrames(liveStart, fps)} - OUT ${formatTimecodeFrames(liveEnd, fps)}"
                                    }
                                    paint.textSize = dp(10.5f); paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                                    val textW = paint.measureText(badgeText)
                                    val badgeX = (l + (r - l - textW) / 2f).coerceIn(gutter + dp(8f), width - textW - dp(20f))
                                    val badgeY = if (y - dp(18f) >= dp(130f)) y - dp(6f) else y + dp(48f)
                                    paint.color = 0xEE18181C.toInt(); paint.style = Paint.Style.FILL
                                    c.drawRoundRect(badgeX - dp(6f), badgeY - dp(13f), badgeX + textW + dp(6f), badgeY + dp(5f), dp(5f), dp(5f), paint)
                                    paint.color = 0xFF93A8F8.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f)
                                    c.drawRoundRect(badgeX - dp(6f), badgeY - dp(13f), badgeX + textW + dp(6f), badgeY + dp(5f), dp(5f), dp(5f), paint)
                                    paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                                    c.drawText(badgeText, badgeX, badgeY, paint)
                                }
                            }

                            val stickerClip = project.stickers.firstOrNull { it.id == s.id }
                            if (stickerClip != null && stickerClip.transformKeyframes.isNotEmpty()) {
                                stickerClip.transformKeyframes.forEach { kf ->
                                    if (kf.timeUs in stickerClip.startUs..stickerClip.endUs) {
                                        val kx = x(kf.timeUs)
                                        if (kx >= l - dp(4f) && kx <= r + dp(4f)) {
                                            val cy = y + dp(17f)
                                            val isUnderPlayhead = abs(kx - width / 2f) < dp(3f)
                                            val isCurrentSelected = s.id == selectedLayerId
                                            paint.color = when {
                                                isUnderPlayhead -> Color.WHITE
                                                isCurrentSelected -> 0xFFB8C7FF.toInt()
                                                else -> 0xCCB8C7FF.toInt()
                                            }
                                            c.save(); c.rotate(45f, kx, cy)
                                            c.drawRect(kx - dp(4.5f), cy - dp(4.5f), kx + dp(4.5f), cy + dp(4.5f), paint)
                                            if (isUnderPlayhead) {
                                                paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f)
                                                c.drawRect(kx - dp(5.5f), cy - dp(5.5f), kx + dp(5.5f), cy + dp(5.5f), paint)
                                                paint.style = Paint.Style.FILL
                                            }
                                            c.restore()
                                        }
                                    }
                                }
                            }
                        }

                        if (isTextTrack) {
                            val isSelectedText = s.id == selectedLayerId || isCurrentTextDragged
                            if (isSelectedText) {
                                // 1. Visible Contour
                                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.color = 0xFFFFD700.toInt()
                                c.drawRoundRect(l, y, r, y + dp(33f), dp(6f), dp(6f), paint)
                                paint.style = Paint.Style.FILL

                                // 2. Left Handle (|◀|) & Right Handle (|▶|)
                                val handleW = dp(14f).coerceAtMost((r - l) / 2f)
                                if (handleW >= dp(6f)) {
                                    // Left Handle
                                    c.save()
                                    c.clipRect(l, y, l + handleW, y + dp(33f))
                                    paint.color = 0xFFFFE082.toInt()
                                    c.drawRoundRect(l, y, l + handleW * 2f, y + dp(33f), dp(6f), dp(6f), paint)
                                    paint.color = 0xFF1C1C1E.toInt()
                                    paint.strokeWidth = dp(1.5f)
                                    paint.style = Paint.Style.STROKE
                                    c.drawLine(l + dp(4f), y + dp(9f), l + dp(4f), y + dp(24f), paint)
                                    val leftPath = Path().apply {
                                        moveTo(l + dp(6.5f), y + dp(12f))
                                        lineTo(l + dp(10.5f), y + dp(16.5f))
                                        lineTo(l + dp(6.5f), y + dp(21f))
                                    }
                                    c.drawPath(leftPath, paint)
                                    paint.style = Paint.Style.FILL
                                    c.restore()

                                    // Right Handle
                                    c.save()
                                    c.clipRect(r - handleW, y, r, y + dp(33f))
                                    paint.color = 0xFFFFE082.toInt()
                                    c.drawRoundRect(r - handleW * 2f, y, r, y + dp(33f), dp(6f), dp(6f), paint)
                                    paint.color = 0xFF1C1C1E.toInt()
                                    paint.strokeWidth = dp(1.5f)
                                    paint.style = Paint.Style.STROKE
                                    c.drawLine(r - dp(4f), y + dp(9f), r - dp(4f), y + dp(24f), paint)
                                    val rightPath = Path().apply {
                                        moveTo(r - dp(6.5f), y + dp(12f))
                                        lineTo(r - dp(10.5f), y + dp(16.5f))
                                        lineTo(r - dp(6.5f), y + dp(21f))
                                    }
                                    c.drawPath(rightPath, paint)
                                    paint.style = Paint.Style.FILL
                                    c.restore()
                                }

                                // 3. Professional Trim / Move HUD Badge
                                if (isDragging && isCurrentTextDragged && gesture == 6) {
                                    val fps = project.export.fps.coerceAtLeast(1)
                                    val badgeText = when {
                                        textTrimSide < 0 -> "IN  ${formatTimecodeFrames(liveStart, fps)}"
                                        textTrimSide > 0 -> "OUT  ${formatTimecodeFrames(liveEnd, fps)}  (DUR ${formatTimecodeFrames(liveEnd - liveStart, fps)})"
                                        else -> "IN ${formatTimecodeFrames(liveStart, fps)} - OUT ${formatTimecodeFrames(liveEnd, fps)}"
                                    }
                                    paint.textSize = dp(10.5f)
                                    paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                                    val textW = paint.measureText(badgeText)
                                    val badgeX = (l + (r - l - textW) / 2f).coerceIn(gutter + dp(8f), width - textW - dp(20f))
                                    val badgeY = if (y - dp(18f) >= dp(130f)) y - dp(6f) else y + dp(48f)
                                    paint.color = 0xEE18181C.toInt(); paint.style = Paint.Style.FILL
                                    c.drawRoundRect(badgeX - dp(6f), badgeY - dp(13f), badgeX + textW + dp(6f), badgeY + dp(5f), dp(5f), dp(5f), paint)
                                    paint.color = 0xFFFFD700.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f)
                                    c.drawRoundRect(badgeX - dp(6f), badgeY - dp(13f), badgeX + textW + dp(6f), badgeY + dp(5f), dp(5f), dp(5f), paint)
                                    paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                                    c.drawText(badgeText, badgeX, badgeY, paint)
                                }
                            }

                            val textClip = project.texts.firstOrNull { it.id == s.id }
                            if (textClip != null && textClip.transformKeyframes.isNotEmpty()) {
                                textClip.transformKeyframes.forEach { kf ->
                                    if (kf.timeUs in textClip.startUs..textClip.endUs) {
                                        val kx = x(kf.timeUs)
                                        if (kx >= l - dp(4f) && kx <= r + dp(4f)) {
                                            val cy = y + dp(17f)
                                            val isUnderPlayhead = abs(kx - width / 2f) < dp(3f)
                                            val isCurrentSelected = s.id == selectedLayerId
                                            paint.color = when {
                                                isUnderPlayhead -> Color.WHITE
                                                isCurrentSelected -> 0xFFFFCE80.toInt()
                                                else -> 0xCCFFCE80.toInt()
                                            }
                                            c.save(); c.rotate(45f, kx, cy)
                                            c.drawRect(kx - dp(4.5f), cy - dp(4.5f), kx + dp(4.5f), cy + dp(4.5f), paint)
                                            if (isUnderPlayhead) {
                                                paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f)
                                                c.drawRect(kx - dp(5.5f), cy - dp(5.5f), kx + dp(5.5f), cy + dp(5.5f), paint)
                                                paint.style = Paint.Style.FILL
                                            }
                                            c.restore()
                                        }
                                    }
                                }
                            }
                        }
                        paint.color = lane.color; c.drawRect(l, y + dp(6f), l + dp(2f), y + dp(27f), paint)
                        val hasHandles = (isTextTrack || isStickerTrack || isAudioTrack) && (s.id == selectedLayerId || isCurrentTextDragged || isCurrentStickerDragged || isCurrentAudioDragged)
                        val textPadStart = if (hasHandles) dp(16f) else 0f
                        val textPadEnd = if (hasHandles) dp(16f) else 0f
                        c.save(); c.clipRect(max(gutter, l + textPadStart), y, min(r - textPadEnd, width.toFloat()), y + dp(33f))
                        text(c, s.name, max(gutter, l + textPadStart) + dp(7f), y + dp(21f), lane.color)
                        drawSpeedBadge(c, s.speedBadge, max(gutter, l), min(r, width.toFloat()), y, y + dp(33f)); c.restore()
                    }
                }
            }
        }
        if (lanes.isEmpty()) text(c, "Adicione audio, texto ou uma camada", width / 2f - dp(88f), dp(166f), 0xFF8E8E93.toInt())
        c.restore(); c.restore()
        paint.color = 0xFF121212.toInt(); c.drawRect(0f, 0f, gutter, height.toFloat(), paint)
        text(c, "V1", dp(9f), dp(90f), Color.WHITE)
        c.save(); c.clipRect(0f, dp(130f), gutter, height.toFloat())
        lanes.forEachIndexed { i, lane -> text(c, lane.label, dp(9f), dp(154f) + i * dp(39f) - vertical, lane.color) }; c.restore()
        paint.color = Color.WHITE
        paint.strokeWidth = dp(if (isPlayheadGrabbed) 2.5f else 1.8f)
        c.drawLine(width / 2f, dp(43f), width / 2f, height.toFloat(), paint)
        if (isPlayheadGrabbed) {
            paint.color = 0x33FFFFFF.toInt()
            c.drawCircle(width / 2f, dp(43f), dp(10f), paint)
            paint.color = Color.WHITE
            c.drawCircle(width / 2f, dp(43f), dp(5.5f), paint)
        } else {
            c.drawCircle(width / 2f, dp(43f), dp(3.5f), paint)
        }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; downTime = e.eventTime
                downPosition = positionUs; delta = 0f; moveTarget = -1; scaled = false; gesture = 0
                isDragging = false
                lastSnapTarget = null
                snapper.reset()
                layerDrag = null; layerTrim = 0; keyframeDrag = null; keyframeDraftSourceUs = null
                textDrag = null; textTrimSide = 0; textDraftStartUs = null; textDraftEndUs = null
                trimSide = 0
                isPlayheadGrabbed = false
                pendingTransition = null
                pendingMarkerId = null
                pendingTrackHeader = null
                pendingClipSelect = -1
                lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.NONE

                val hitCtx = com.termex.replay15.editor.ui.TimelineHitContext(
                    project = project,
                    selectedClipIndex = selected,
                    selectedLayerId = selectedLayerId,
                    pixelsPerSecond = pixelsPerSecond,
                    positionUs = positionUs,
                    width = width.toFloat(),
                    height = height.toFloat(),
                    density = resources.displayMetrics.density,
                    verticalScroll = vertical,
                    gutterWidth = dp(40f),
                    timeToX = ::x,
                    xToTime = ::time,
                    visualStart = ::visualStart,
                    visualEnd = ::visualEnd,
                    visualTime = ::visualTime,
                    transitionBoundaryAfter = ::transitionBoundaryAfter,
                    getLaneAt = { idx ->
                        lanes.getOrNull(idx)?.let { lane ->
                            com.termex.replay15.editor.ui.TimelineLaneSnapshot(
                                lane.type,
                                lane.segments.map { com.termex.replay15.editor.ui.TimelineSegmentSnapshot(it.id, it.start, it.end) },
                                lane.trackId
                            )
                        }
                    },
                )

                when (val hit = com.termex.replay15.editor.ui.TimelineHitResolver.resolve(e.x, e.y, hitCtx)) {
                    is com.termex.replay15.editor.ui.TimelineHitTarget.StickerTrimTarget -> {
                        val sticker = project.stickers.firstOrNull { it.id == hit.stickerId }
                        if (sticker != null) {
                            stickerDrag = hit.stickerId to sticker
                            stickerTrimSide = hit.side
                            stickerInitialStartUs = sticker.startUs
                            stickerInitialEndUs = sticker.endUs
                            stickerDraftStartUs = stickerInitialStartUs
                            stickerDraftEndUs = stickerInitialEndUs
                            lockedTarget = if (hit.side < 0) com.termex.replay15.editor.ui.TimelineGestureLock.STICKER_TRIM_START else com.termex.replay15.editor.ui.TimelineGestureLock.STICKER_TRIM_END
                            selectedLayerId = hit.stickerId
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.StickerBodyTarget -> {
                        stickerDrag = hit.stickerId to hit.stickerClip
                        stickerTrimSide = 0
                        stickerInitialStartUs = hit.stickerClip.startUs
                        stickerInitialEndUs = hit.stickerClip.endUs
                        stickerDraftStartUs = stickerInitialStartUs
                        stickerDraftEndUs = stickerInitialEndUs
                        lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.STICKER_DRAG
                        selectedLayerId = hit.stickerId
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.AudioTrimTarget -> {
                        val audio = project.audio.firstOrNull { it.id == hit.audioId }
                        if (audio != null) {
                            audioDrag = hit.audioId to audio
                            audioTrimSide = hit.side
                            audioFadeSide = 0
                            audioInitialStartUs = audio.startUs
                            audioInitialInUs = audio.inUs
                            audioInitialOutUs = audio.outUs
                            audioInitialFadeInUs = audio.fadeInUs
                            audioInitialFadeOutUs = audio.fadeOutUs
                            audioDraftStartUs = audioInitialStartUs
                            audioDraftInUs = audioInitialInUs
                            audioDraftOutUs = audioInitialOutUs
                            lockedTarget = if (hit.side < 0) com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_TRIM_START else com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_TRIM_END
                            selectedLayerId = hit.audioId
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.AudioFadeTarget -> {
                        val audio = project.audio.firstOrNull { it.id == hit.audioId }
                        if (audio != null) {
                            val side = if (hit.isFadeIn) -1 else 1
                            audioDrag = hit.audioId to audio
                            audioTrimSide = 0
                            audioFadeSide = side
                            audioInitialStartUs = audio.startUs
                            audioInitialInUs = audio.inUs
                            audioInitialOutUs = audio.outUs
                            audioInitialFadeInUs = audio.fadeInUs
                            audioInitialFadeOutUs = audio.fadeOutUs
                            audioDraftFadeInUs = audioInitialFadeInUs
                            audioDraftFadeOutUs = audioInitialFadeOutUs
                            lockedTarget = if (side < 0) com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_FADE_IN else com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_FADE_OUT
                            selectedLayerId = hit.audioId
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.AudioBodyTarget -> {
                        audioDrag = hit.audioId to hit.audioClip
                        audioTrimSide = 0
                        audioFadeSide = 0
                        audioInitialStartUs = hit.audioClip.startUs
                        audioInitialInUs = hit.audioClip.inUs
                        audioInitialOutUs = hit.audioClip.outUs
                        audioInitialFadeInUs = hit.audioClip.fadeInUs
                        audioInitialFadeOutUs = hit.audioClip.fadeOutUs
                        audioDraftStartUs = audioInitialStartUs
                        audioDraftInUs = audioInitialInUs
                        audioDraftOutUs = audioInitialOutUs
                        lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_DRAG
                        selectedLayerId = hit.audioId
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.AudioVolumeKeyframeTarget -> {
                        val audio = project.audio.firstOrNull { it.id == hit.audioId }
                        if (audio != null) {
                            audioDrag = hit.audioId to audio
                            audioTrimSide = 0
                            audioFadeSide = 0
                            lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_VOLUME_KEYFRAME
                            selectedLayerId = hit.audioId
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.TextTrimTarget -> {
                        val text = project.texts.firstOrNull { it.id == hit.textId }
                        if (text != null) {
                            textDrag = hit.textId to text
                            textTrimSide = hit.side
                            textInitialStartUs = text.startUs
                            textInitialEndUs = text.endUs
                            textDraftStartUs = textInitialStartUs
                            textDraftEndUs = textInitialEndUs
                            lockedTarget = if (hit.side < 0) com.termex.replay15.editor.ui.TimelineGestureLock.TEXT_TRIM_START else com.termex.replay15.editor.ui.TimelineGestureLock.TEXT_TRIM_END
                            selectedLayerId = hit.textId
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.TextBodyTarget -> {
                        textDrag = hit.textId to hit.textClip
                        textTrimSide = 0
                        textInitialStartUs = hit.textClip.startUs
                        textInitialEndUs = hit.textClip.endUs
                        textDraftStartUs = textInitialStartUs
                        textDraftEndUs = textInitialEndUs
                        lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.TEXT_DRAG
                        selectedLayerId = hit.textId
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.KeyframeTarget -> {
                        keyframeDrag = hit.clipIndex to hit.keyIndex
                        keyframeDraftSourceUs = hit.sourceUs
                        lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.KEYFRAME_DRAG
                        if (hit.clipIndex != selected) onSelect(hit.clipIndex)
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.TrimTarget -> {
                        trimSide = hit.side
                        lockedTarget = if (hit.side < 0) com.termex.replay15.editor.ui.TimelineGestureLock.TRIM_START else com.termex.replay15.editor.ui.TimelineGestureLock.TRIM_END
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.TransitionTarget -> {
                        pendingTransition = hit.leftClipId to hit.rightClipId
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.PlayheadTarget -> {
                        isPlayheadGrabbed = true
                        lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.PLAYHEAD_GRAB
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.MarkerTarget -> {
                        pendingMarkerId = hit.markerId
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.LayerTrimTarget -> {
                        val track = project.videoTracks.firstOrNull { it.id == hit.trackId }
                        val item = track?.clips?.firstOrNull { it.clip.id == hit.clipId }
                        if (item != null) {
                            layerDrag = hit.trackId to item
                            layerTrim = hit.side
                            lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.LAYER_TRIM
                        }
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.LayerBodyTarget -> {
                        layerDrag = hit.trackId to hit.item
                        layerTrim = 0
                        lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.LAYER_DRAG
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.TrackHeaderTarget -> {
                        pendingTrackHeader = hit.trackId
                    }
                    is com.termex.replay15.editor.ui.TimelineHitTarget.ClipBodyTarget -> {
                        pendingClipSelect = hit.clipIndex
                    }
                    null -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> if (!scale.isInProgress && !scaled) {
                val distanceMoved = hypot(e.x - downX, e.y - downY)
                if (!isDragging && distanceMoved > dp(8f)) {
                    isDragging = true
                }

                if (gesture == 0 && isDragging) {
                    val selectedStart = if (selected in project.videos.indices) visualStart(selected) else -1L
                    val selectedEnd = if (selected in project.videos.indices) visualEnd(selected) else -1L
                    val pressedSelected = downY in dp(48f)..dp(120f) && time(downX) in selectedStart..selectedEnd

                    gesture = when {
                        lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.KEYFRAME_DRAG -> 5
                        lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.TRIM_START || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.TRIM_END -> 1
                        lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.PLAYHEAD_GRAB -> 1
                        lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.LAYER_DRAG || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.LAYER_TRIM -> 4
                        lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.TEXT_TRIM_START || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.TEXT_TRIM_END || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.TEXT_DRAG -> 6
                        lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.STICKER_TRIM_START || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.STICKER_TRIM_END || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.STICKER_DRAG -> 7
                        lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_TRIM_START || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_TRIM_END || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_DRAG || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_FADE_IN || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_FADE_OUT || lockedTarget == com.termex.replay15.editor.ui.TimelineGestureLock.AUDIO_VOLUME_KEYFRAME -> 8
                        abs(e.y - downY) > abs(e.x - downX) && downY >= dp(130f) -> 2
                        pressedSelected && trimSide == 0 && selected >= 0 -> 3
                        else -> 1
                    }
                }

                val rawDelta = e.x - downX
                delta = precisionController.scaleDelta(rawDelta)

                if (gesture == 2) {
                    vertical = (vertical - (e.y - lastY)).coerceIn(0f, maxScroll())
                    invalidate()
                } else if (gesture == 6) {
                    selectedLayerId = textDrag?.first
                    textDrag?.let { (textId, _) ->
                        val shiftUs = (delta / pixelsPerSecond * SECOND).toLong()
                        when (textTrimSide) {
                            -1 -> {
                                val targetStart = (textInitialStartUs + shiftUs).coerceIn(0L, textInitialEndUs - MIN_TEXT_DURATION_US)
                                val snappedStart = snap(targetStart).coerceIn(0L, textInitialEndUs - MIN_TEXT_DURATION_US)
                                textDraftStartUs = snappedStart
                                textDraftEndUs = textInitialEndUs
                            }
                            1 -> {
                                val targetEnd = (textInitialEndUs + shiftUs).coerceIn(textInitialStartUs + MIN_TEXT_DURATION_US, project.durationUs)
                                val snappedEnd = snap(targetEnd).coerceIn(textInitialStartUs + MIN_TEXT_DURATION_US, project.durationUs)
                                textDraftStartUs = textInitialStartUs
                                textDraftEndUs = snappedEnd
                            }
                            else -> {
                                val duration = textInitialEndUs - textInitialStartUs
                                val maxStart = (project.durationUs - duration).coerceAtLeast(0L)
                                val targetStart = (textInitialStartUs + shiftUs).coerceIn(0L, maxStart)
                                val snappedStart = snap(targetStart).coerceIn(0L, maxStart)
                                textDraftStartUs = snappedStart
                                textDraftEndUs = snappedStart + duration
                            }
                        }
                        onTextPreviewChange(textId, textDraftStartUs!!, textDraftEndUs!!)
                        invalidate()
                    }
                } else if (gesture == 7) {
                    selectedLayerId = stickerDrag?.first
                    stickerDrag?.let { (stickerId, _) ->
                        val shiftUs = (delta / pixelsPerSecond * SECOND).toLong()
                        when (stickerTrimSide) {
                            -1 -> {
                                val targetStart = (stickerInitialStartUs + shiftUs).coerceIn(0L, stickerInitialEndUs - MIN_STICKER_DURATION_US)
                                val snappedStart = snap(targetStart).coerceIn(0L, stickerInitialEndUs - MIN_STICKER_DURATION_US)
                                stickerDraftStartUs = snappedStart
                                stickerDraftEndUs = stickerInitialEndUs
                            }
                            1 -> {
                                val targetEnd = (stickerInitialEndUs + shiftUs).coerceIn(stickerInitialStartUs + MIN_STICKER_DURATION_US, project.durationUs)
                                val snappedEnd = snap(targetEnd).coerceIn(stickerInitialStartUs + MIN_STICKER_DURATION_US, project.durationUs)
                                stickerDraftStartUs = stickerInitialStartUs
                                stickerDraftEndUs = snappedEnd
                            }
                            else -> {
                                val duration = stickerInitialEndUs - stickerInitialStartUs
                                val maxStart = (project.durationUs - duration).coerceAtLeast(0L)
                                val targetStart = (stickerInitialStartUs + shiftUs).coerceIn(0L, maxStart)
                                val snappedStart = snap(targetStart).coerceIn(0L, maxStart)
                                stickerDraftStartUs = snappedStart
                                stickerDraftEndUs = snappedStart + duration
                            }
                        }
                        onStickerPreviewChange(stickerId, stickerDraftStartUs!!, stickerDraftEndUs!!)
                        invalidate()
                    }
                } else if (gesture == 8) {
                    selectedLayerId = audioDrag?.first
                    audioDrag?.let { (audioId, clip) ->
                        val shiftUs = (delta / pixelsPerSecond * SECOND).toLong()
                        if (audioFadeSide != 0) {
                            val clipDurationUs = clip.outUs - clip.inUs
                            if (audioFadeSide < 0) {
                                val maxFade = (clipDurationUs - clip.fadeOutUs).coerceAtLeast(0L)
                                val newFadeIn = (audioInitialFadeInUs + shiftUs).coerceIn(0L, maxFade)
                                audioDraftFadeInUs = newFadeIn
                                audioDraftFadeOutUs = audioInitialFadeOutUs
                                onAudioFadePreviewChange(audioId, newFadeIn, audioInitialFadeOutUs)
                            } else {
                                val maxFade = (clipDurationUs - clip.fadeInUs).coerceAtLeast(0L)
                                val newFadeOut = (audioInitialFadeOutUs - shiftUs).coerceIn(0L, maxFade)
                                audioDraftFadeOutUs = newFadeOut
                                audioDraftFadeInUs = audioInitialFadeInUs
                                onAudioFadePreviewChange(audioId, audioInitialFadeInUs, newFadeOut)
                            }
                        } else if (audioTrimSide != 0) {
                            when (audioTrimSide) {
                                -1 -> {
                                    val maxShift = (audioInitialOutUs - audioInitialInUs) - MIN_AUDIO_DURATION_US
                                    val targetIn = (audioInitialInUs + shiftUs).coerceIn(0L, audioInitialInUs + maxShift)
                                    val actualShift = targetIn - audioInitialInUs
                                    val targetStart = (audioInitialStartUs + actualShift).coerceAtLeast(0L)
                                    val snappedStart = snap(targetStart).coerceAtLeast(0L)
                                    val snapShift = snappedStart - audioInitialStartUs
                                    val finalIn = (audioInitialInUs + snapShift).coerceIn(0L, audioInitialOutUs - MIN_AUDIO_DURATION_US)
                                    audioDraftStartUs = audioInitialStartUs + (finalIn - audioInitialInUs)
                                    audioDraftInUs = finalIn
                                    audioDraftOutUs = audioInitialOutUs
                                }
                                1 -> {
                                    val maxOut = if (clip.sourceUs > 0) clip.sourceUs else Long.MAX_VALUE
                                    val targetOut = (audioInitialOutUs + shiftUs).coerceIn(audioInitialInUs + MIN_AUDIO_DURATION_US, maxOut)
                                    val snappedOut = (snap(audioInitialStartUs + (targetOut - audioInitialInUs)) - audioInitialStartUs + audioInitialInUs).coerceIn(audioInitialInUs + MIN_AUDIO_DURATION_US, maxOut)
                                    audioDraftStartUs = audioInitialStartUs
                                    audioDraftInUs = audioInitialInUs
                                    audioDraftOutUs = snappedOut
                                }
                            }
                            onAudioPreviewChange(audioId, audioDraftStartUs!!, audioDraftInUs!!, audioDraftOutUs!!)
                        } else {
                            val duration = audioInitialOutUs - audioInitialInUs
                            val maxStart = (project.durationUs - duration).coerceAtLeast(0L)
                            val targetStart = (audioInitialStartUs + shiftUs).coerceIn(0L, maxStart)
                            val snappedStart = snap(targetStart).coerceIn(0L, maxStart)
                            audioDraftStartUs = snappedStart
                            audioDraftInUs = audioInitialInUs
                            audioDraftOutUs = audioInitialOutUs
                            onAudioPreviewChange(audioId, snappedStart, audioInitialInUs, audioInitialOutUs)
                        }
                        invalidate()
                    }
                } else if (gesture == 4) {
                    selectedLayerId = layerDrag?.second?.clip?.id
                    invalidate()
                } else if (gesture == 3) {
                    moveTarget = visualIndexAt(time(e.x))
                    invalidate()
                } else if (gesture == 5) {
                    beginScrubSession()
                    keyframeDrag?.let { (clipIndex, _) ->
                        val clip = project.videos[clipIndex]
                        val timelineUs = snap(time(e.x))
                        keyframeDraftSourceUs = sourceAtVisualPosition(clipIndex, clip, x(timelineUs))
                            .coerceIn(clip.inUs, clip.outUs)
                        onScrub(visualTime(clipIndex, clip, keyframeDraftSourceUs!!))
                        invalidate()
                    }
                } else if (gesture == 1) {
                    if (trimSide != 0) {
                        invalidate()
                    } else if (isPlayheadGrabbed) {
                        beginScrubSession()
                        val scrubTarget = snap(time(e.x))
                        onScrub(scrubTarget)
                        invalidate()
                    } else {
                        beginScrubSession()
                        val scaledDelta = precisionController.scaleDelta(e.x - downX)
                        val targetTime = (downPosition - (scaledDelta / pixelsPerSecond * SECOND).toLong()).coerceIn(0, project.durationUs)
                        onScrub(snap(targetTime))
                    }
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val rawDelta = delta
                if (!scaled && isDragging) {
                    if (gesture == 7) {
                        stickerDrag?.let { (stickerId, _) ->
                            val finalStart = stickerDraftStartUs ?: stickerInitialStartUs
                            val finalEnd = stickerDraftEndUs ?: stickerInitialEndUs
                            if (stickerTrimSide != 0) {
                                onStickerTrim(stickerId, finalStart, finalEnd)
                            } else {
                                onStickerMove(stickerId, finalStart, finalEnd)
                            }
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    } else if (gesture == 8) {
                        audioDrag?.let { (audioId, _) ->
                            if (audioFadeSide != 0) {
                                val finalFadeIn = audioDraftFadeInUs ?: audioInitialFadeInUs
                                val finalFadeOut = audioDraftFadeOutUs ?: audioInitialFadeOutUs
                                onAudioFade(audioId, finalFadeIn, finalFadeOut)
                            } else if (audioTrimSide != 0) {
                                val finalStart = audioDraftStartUs ?: audioInitialStartUs
                                val finalIn = audioDraftInUs ?: audioInitialInUs
                                val finalOut = audioDraftOutUs ?: audioInitialOutUs
                                onAudioTrim(audioId, finalStart, finalIn, finalOut)
                            } else {
                                val finalStart = audioDraftStartUs ?: audioInitialStartUs
                                onAudioMove(audioId, finalStart)
                            }
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    } else if (gesture == 6) {
                        textDrag?.let { (textId, _) ->
                            val finalStart = textDraftStartUs ?: textInitialStartUs
                            val finalEnd = textDraftEndUs ?: textInitialEndUs
                            if (textTrimSide != 0) {
                                onTextTrim(textId, finalStart, finalEnd)
                            } else {
                                onTextMove(textId, finalStart, finalEnd)
                            }
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    } else if (gesture == 4) {
                        layerDrag?.let { (track, item) ->
                            val shift = (rawDelta / pixelsPerSecond * SECOND).toLong()
                            if (layerTrim == 0) onLayerMove(track, item.clip.id, snap((item.startUs + shift).coerceAtLeast(0)))
                            else {
                                val clip = item.clip
                                val left = clip.timeMap.extendedSourceAt(shift.coerceAtMost((clip.durationUs - MIN_CLIP).coerceAtLeast(0)))
                                val right = clip.timeMap.extendedSourceAt((clip.durationUs + shift).coerceAtLeast(minOf(MIN_CLIP, clip.durationUs)))
                                onLayerTrim(track, clip, if (layerTrim < 0) left.coerceAtMost(clip.outUs - 1) else clip.inUs,
                                    if (layerTrim > 0) right.coerceAtLeast(clip.inUs + 1) else clip.outUs)
                            }
                        }
                    } else if (gesture == 5) {
                        keyframeDrag?.let { (clipIndex, keyIndex) ->
                            keyframeDraftSourceUs?.let { onKeyframeMove(clipIndex, keyIndex, it) }
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    } else if (gesture == 3 && moveTarget in project.videos.indices) {
                        onMove(selected, moveTarget)
                    } else if (trimSide != 0 && gesture == 1) {
                        project.videos.getOrNull(selected)?.let { clip ->
                            val shift = (rawDelta / pixelsPerSecond * SECOND).toLong()
                            val left = clip.timeMap.extendedSourceAt(shift.coerceAtMost((clip.durationUs - MIN_CLIP).coerceAtLeast(0)))
                            val right = clip.timeMap.extendedSourceAt((clip.durationUs + shift).coerceAtLeast(minOf(MIN_CLIP, clip.durationUs)))
                            onTrim(selected, if (trimSide < 0) left.coerceAtMost(clip.outUs - 1) else clip.inUs,
                                if (trimSide > 0) right.coerceAtLeast(clip.inUs + 1) else clip.outUs)
                        }
                    } else if (gesture == 1) {
                        if (isPlayheadGrabbed) {
                            onSeek(snap(time(e.x)))
                        } else {
                            val scaledDelta = precisionController.scaleDelta(e.x - downX)
                            onSeek(snap((downPosition - (scaledDelta / pixelsPerSecond * SECOND).toLong()).coerceIn(0, project.durationUs)))
                        }
                    }
                } else if (!scaled && !isDragging) {
                    var handledTap = false
                    val point = time(e.x)

                    if (pendingTransition != null) {
                        handledTap = true
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTransitionClick(pendingTransition!!.first, pendingTransition!!.second)
                    } else if (pendingMarkerId != null) {
                        handledTap = true
                        onMarker(pendingMarkerId!!)
                    } else if (pendingTrackHeader != null) {
                        handledTap = true
                        onTrack(pendingTrackHeader!!)
                    } else if (e.x < dp(40f) && downY >= dp(130f)) {
                        lanes.getOrNull(((downY - dp(132f) + vertical) / dp(39f)).toInt())?.let {
                            handledTap = true
                            if (it.type == 4) it.segments.firstOrNull()?.let { segment -> onAdjustment(segment.id) } else onTrack(it.trackId)
                        }
                    } else if (downY < dp(48f)) {
                        handledTap = true
                        onSeek(snap(point))
                    } else if (downY in dp(48f)..dp(120f)) {
                        handledTap = true
                        if (pendingClipSelect in project.videos.indices) {
                            onSelect(pendingClipSelect)
                        }
                        onSeek(snap(point))
                    } else if (downY >= dp(130f)) {
                        val lane = lanes.getOrNull(((downY - dp(132f) + vertical) / dp(39f)).toInt())
                        lane?.segments?.firstOrNull { point in it.start until it.end }?.let { s ->
                            handledTap = true
                            when (lane.type) {
                                0 -> {
                                    val audioClip = project.audio.firstOrNull { it.id == s.id }
                                    val keyHit = audioClip?.volumeKeyframes?.firstOrNull { abs(x(audioClip.startUs + it.timeUs) - downX) <= dp(20f) }
                                    if (keyHit != null) {
                                        onSeek(audioClip.startUs + keyHit.timeUs)
                                        onAudioVolumeKeyframeClick(s.id, keyHit)
                                    } else {
                                        selectedLayerId = s.id
                                        onAudio(s.id)
                                    }
                                }
                                1 -> {
                                    val textClip = project.texts.firstOrNull { it.id == s.id }
                                    val keyHit = textClip?.transformKeyframes?.firstOrNull { abs(x(it.timeUs) - downX) <= dp(20f) }
                                    if (keyHit != null) {
                                        onSeek(keyHit.timeUs)
                                        onTextKeyframeClick(s.id, keyHit)
                                    } else {
                                        selectedLayerId = s.id
                                        onText(s.id)
                                    }
                                }
                                2 -> {
                                    val stickerClip = project.stickers.firstOrNull { it.id == s.id }
                                    val keyHit = stickerClip?.transformKeyframes?.firstOrNull { abs(x(it.timeUs) - downX) <= dp(20f) }
                                    if (keyHit != null) {
                                        onSeek(keyHit.timeUs)
                                        onStickerKeyframeClick(s.id, keyHit)
                                    } else {
                                        selectedLayerId = s.id
                                        onSticker(s.id)
                                    }
                                }
                                3 -> { selectedLayerId = s.id; onVideoLayer(lane.trackId, s.id) }
                                4 -> onAdjustment(s.id)
                            }
                        }
                    }
                    if (!handledTap) onClearSelection()
                    performClick()
                }

                trimSide = 0; delta = 0f; keyframeDrag = null; keyframeDraftSourceUs = null
                textDrag = null; textTrimSide = 0; textDraftStartUs = null; textDraftEndUs = null
                stickerDrag = null; stickerTrimSide = 0; stickerDraftStartUs = null; stickerDraftEndUs = null
                audioDrag = null; audioTrimSide = 0; audioFadeSide = 0; audioDraftStartUs = null; audioDraftInUs = null; audioDraftOutUs = null; audioDraftFadeInUs = null; audioDraftFadeOutUs = null
                audioKeyframeDrag = null; audioKeyframeDraftTimeUs = null
                isPlayheadGrabbed = false
                isDragging = false
                pendingTransition = null
                pendingMarkerId = null
                pendingTrackHeader = null
                pendingClipSelect = -1
                lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.NONE
                snapper.reset()
                invalidate(); parent.requestDisallowInterceptTouchEvent(false)
                lastSnapTarget = null
                endScrubSession()
            }
            MotionEvent.ACTION_CANCEL -> {
                trimSide = 0
                delta = 0f
                moveTarget = -1
                layerDrag = null
                layerTrim = 0
                textDrag = null
                textTrimSide = 0
                textDraftStartUs = null
                textDraftEndUs = null
                stickerDrag = null
                stickerTrimSide = 0
                stickerDraftStartUs = null
                stickerDraftEndUs = null
                audioDrag = null
                audioTrimSide = 0
                audioFadeSide = 0
                audioDraftStartUs = null
                audioDraftInUs = null
                audioDraftOutUs = null
                audioDraftFadeInUs = null
                audioDraftFadeOutUs = null
                audioKeyframeDrag = null
                audioKeyframeDraftTimeUs = null
                gesture = 0
                scaled = false
                isDragging = false
                isPlayheadGrabbed = false
                pendingTransition = null
                pendingMarkerId = null
                pendingTrackHeader = null
                pendingClipSelect = -1
                lockedTarget = com.termex.replay15.editor.ui.TimelineGestureLock.NONE
                keyframeDrag = null
                keyframeDraftSourceUs = null
                lastSnapTarget = null
                snapper.reset()
                invalidate()
                parent.requestDisallowInterceptTouchEvent(false)
                endScrubSession()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun clearThumbnails() { thumbs.clear() }
    fun release() { thumbs.close(); waves.close() }
}
