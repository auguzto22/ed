package com.termex.replay15.editor.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.effect.BitmapOverlay
import com.termex.replay15.editor.domain.Scene3DClip
import com.termex.replay15.editor.scene3d.Scene3DRenderer

/**
 * Export side of the 3D layers: renders them with Filament for every output frame and hands the
 * result to Media3 as a full-frame overlay, above the videos and below text and stickers (the
 * same z-order as the preview). Timestamps are project-global.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class Scene3DOverlay(
    private val scenes: List<Scene3DClip>,
    private val width: Int,
    private val height: Int,
) : BitmapOverlay() {
    private val renderer = Scene3DRenderer()
    /** Two buffers so every new frame is a different Bitmap for Media3's upload check. */
    private val frames = arrayOfNulls<Bitmap>(2)
    private var current = -1
    private var empty: Bitmap? = null
    private var lastActive: List<Scene3DClip> = emptyList()
    private val animationCounts = HashMap<String, Int>()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val active = scenes.filter { it.isActiveAt(presentationTimeUs) }
        if (active.isEmpty()) return emptyFrame()
        // A layer whose frames cannot change is rendered once for as long as it stays active.
        if (current >= 0 && active == lastActive && active.none(::changesOverTime)) return frames[current]!!
        val next = if (current == 0) 1 else 0
        val target = frames[next] ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frames[next] = it }
        return if (renderer.render(active, presentationTimeUs, target, Scene3DRenderer.Quality.EXPORT)) {
            current = next
            lastActive = active
            target
        } else {
            // Keep showing the previous frame of the same layers rather than flashing them off.
            if (current >= 0 && active == lastActive) frames[current]!! else emptyFrame()
        }
    }

    private fun changesOverTime(clip: Scene3DClip): Boolean {
        if (clip.poseKeyframes.size > 1 || clip.cameraKeyframes.size > 1 || clip.cameraShots.isNotEmpty() ||
            clip.nulls.any { it.poseKeyframes.size > 1 }) return true
        if (!clip.playAnimation || clip.animationSpeed <= 0f) return false
        val count = animationCounts.getOrPut(clip.modelPath) {
            runCatching { renderer.inspect(clip.modelPath).animationCount }.getOrDefault(0)
        }
        return count > 0
    }

    private fun emptyFrame(): Bitmap = empty ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.eraseColor(Color.TRANSPARENT); empty = it
    }

    override fun release() {
        try {
            super.release()
        } finally {
            renderer.close()
            frames.forEachIndexed { index, bitmap -> bitmap?.recycle(); frames[index] = null }
            empty?.recycle(); empty = null
            current = -1
        }
    }
}
