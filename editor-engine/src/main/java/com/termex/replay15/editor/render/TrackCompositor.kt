package com.termex.replay15.editor.render

import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import com.termex.replay15.editor.domain.*

/**
 * Decides which input is visible for every exported frame. Main-track clips that take part in a
 * transition are both decoded through the window; the incoming one carries the composited picture
 * once its shader has drawn, otherwise the edit stays a plain cut at its real position.
 */
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
    private val windows = TransitionTiming.windows(project)

    override fun getOutputSize(inputSizes: List<Size>) = Size(width, height)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        if (inputId == layers.size) return visible
        val layer = layers.getOrNull(inputId) ?: return hidden
        if (!project.visualEnabled(layer.id)) return hidden
        val piece = layer.pieceAt(presentationTimeUs) ?: return hidden
        val main = layer.id == MAIN_TRACK_A || layer.id == MAIN_TRACK_B
        val window = (if (main) windows.firstOrNull {
            it.contains(presentationTimeUs) &&
                (it.transition.leftClipId == piece.clip.id || it.transition.rightClipId == piece.clip.id)
        } else null) ?: return visible
        val shaderSinceUs = transitionBridgeKeys[window.transition.id]?.let(TransitionBridge::shaderSinceUs) ?: Long.MAX_VALUE
        val shown = TransitionVisibility.visible(window, piece.clip.id, presentationTimeUs, shaderSinceUs)
        if (traceFrames) {
            android.util.Log.d("ReclyExportFrame", "projectTimeUs=$presentationTimeUs input=$inputId layer=${layer.id} " +
                "clip=${piece.clip.id} hold=${piece.hold} piece=${piece.startUs}..${piece.endUs} " +
                "window=${window.startUs}..${window.endUs} cut=${window.cutUs} shaderSince=$shaderSinceUs shown=$shown")
        }
        return if (shown) visible else hidden
    }
}
