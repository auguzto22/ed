# Render pipelines

Interactive preview and offline export are separate pipelines over the same immutable project model.

Preview resolves only active and near-boundary clips. Each active video source owns a persistent hardware `MediaCodec` session whose output Surface feeds a `SurfaceTexture` OES texture. The EGL renderer caches accepted frames in 2D textures, evaluates transforms/keyframes and color state, executes cached package-effect and transition shaders through reusable FBO targets, composites layers, and swaps to the editor Surface. Paused rendering is event-driven. Text and sticker raster sources are cached and updated only when state or animation time changes.

Seek generations enforce latest-result-wins. Precise seeks go to the previous sync sample and decode forward. Fast scrub may present an earlier sync result. Decoder demand is bounded, active transition inputs have priority, and the next source can be prewarmed without loading the project.

Audio is an independent audio-only pipeline. It follows the preview clock, seeks when drift exceeds tolerance, applies speed/pitch and gain, and is muted during scrub. Video readiness never waits for audio.

Export alone uses `ProjectComposition` and Media3 Transformer. It reads original source URIs and applies crop, transforms, color/LUT/mask/chroma, package effects, transitions, overlays, canvas, audio mix, output cadence, and HDR-to-SDR policy. Preview quality and proxy choices do not change export settings or source selection.
