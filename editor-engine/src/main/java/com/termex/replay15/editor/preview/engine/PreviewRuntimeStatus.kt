package com.termex.replay15.editor.preview.engine

/**
 * Independent runtime states for the preview pipeline. Each subsystem tracks its own
 * readiness without coupling to others — decoder availability does not depend on
 * display surface presence, and vice versa.
 */
data class PreviewRuntimeStatus(
    val decoderReady: Boolean = false,
    val decoderSurfaceReady: Boolean = false,
    val displaySurfaceAttached: Boolean = false,
    val glReady: Boolean = false,
    val frameAvailable: Boolean = false,
    val playing: Boolean = false,
) {
    /** True when the pipeline can present frames to the screen. */
    val canRender: Boolean get() = glReady && displaySurfaceAttached

    /** True when the pipeline can decode and present. */
    val canPlay: Boolean get() = canRender && decoderReady && decoderSurfaceReady

    /** True when decoder can work even without display (offscreen caching). */
    val canDecode: Boolean get() = glReady && decoderSurfaceReady && decoderReady
}
