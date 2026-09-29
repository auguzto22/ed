package com.termex.replay15.editor.render

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import com.termex.replay15.editor.domain.*

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class TrackCompositor(
    private val project: Project,
    private val layers: List<RenderLayer>,
    private val width: Int,
    private val height: Int,
    private val transitionBridgeKeys: Map<String, String> = emptyMap(),
    private val traceFrames: Boolean = false,
) : VideoCompositorSettings {
    private class FixedAlpha(private val alpha: Float) : OverlaySettings {
        override fun getAlphaScale() = alpha
    }

    private val visible = FixedAlpha(1f)
    private val hidden = FixedAlpha(0f)

    override fun getOutputSize(inputSizes: List<Size>) = Size(width, height)
    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        val layer = layers.getOrNull(inputId)
        if (inputId == layers.size) return visible
        val snapshot = project
        if (layer == null || !snapshot.visualEnabled(layer.id)) return hidden
        val active = layer.activeAt(presentationTimeUs)
        if (traceFrames && presentationTimeUs in 3_900_000L..6_200_000L) {
            android.util.Log.d("ReclyExportFrame", "projectTimeUs=$presentationTimeUs input=$inputId layer=${layer.id} " +
                "clip=${active?.clip?.id} startUs=${active?.startUs} endUs=${active?.endUs} " +
                "sourceTimeUs=${active?.let { RenderPlan.sourceTime(it.clip, it.startUs, presentationTimeUs) }}")
        }
        if (active == null) return hidden
        val transition = snapshot.transitions.firstOrNull { trans ->
            (trans.leftClipId == active.clip.id || trans.rightClipId == active.clip.id) &&
            presentationTimeUs >= trans.startUs(snapshot) &&
            presentationTimeUs < trans.endUs(snapshot)
        }
        val alpha = when {
            transition == null -> 1f
            transitionBridgeKeys[transition.id]?.let(TransitionBridge::isReady) == true ->
                if (transition.leftClipId == active.clip.id) 0f else 1f
            else -> {
                // Fallback when transition is not ready (missing capture or shader error):
                // Clean midpoint cut so the preview NEVER goes black.
                val midUs = transition.startUs(snapshot) + transition.durationUs / 2
                if (presentationTimeUs < midUs) {
                    if (transition.leftClipId == active.clip.id) 1f else 0f
                } else {
                    if (transition.rightClipId == active.clip.id) 1f else 0f
                }
            }
        }
        return if (alpha == 0f) hidden else visible
    }
}
