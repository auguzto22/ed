# Render pipelines

Interactive preview and offline export are separate pipelines over the same immutable project model.

Preview resolves only active and near-boundary clips. Each active video source owns a persistent hardware `MediaCodec` session whose output Surface feeds a `SurfaceTexture` OES texture. The Android EGL adapter caches accepted frames in 2D textures, keeps a bounded image texture cache and presents the result to the editor Surface. The Rust library `editor-preview-native` composes video layers, transforms, color, chroma, masks, package effects, transitions, overlays and camera using cached shaders and reusable FBO targets. Rust also decides frame pacing and mixes PCM. Paused rendering is event-driven. Android adapters rasterize text, Lottie and sticker bitmaps, prepare background-removal textures, and provide audio decode and `AudioTrack` output.

Seek generations enforce latest-result-wins. Precise seeks go to the previous sync sample and decode forward. Fast scrub may present an earlier sync result. Decoder demand is bounded, active transition inputs have priority, and the next source can be prewarmed without loading the project.

Audio is an independent audio-only pipeline. It follows the preview clock, seeks when drift exceeds tolerance, applies speed/pitch and gain, and is muted during scrub. Video readiness never waits for audio.

The JNI frame packet is an immutable JSON snapshot. Rust selects active video and audio sources, chooses prewarm candidates, maps video source time through speed segments and transition handles, and composites separate sticker and text textures. Kotlin adapts the shared project model, evaluates transform keyframes and controls Android decoder sessions. `NativePreviewRenderer` owns the EGL thread; all Rust GL handle calls stay on that thread. The Rust library builds for `arm64-v8a` and `x86_64` through `:editor-engine:buildRustPreview`. A connected Android device or emulator is required to check runtime visual and audio parity; compiling the APK does not establish it.

Export alone uses `ProjectComposition` and Media3 Transformer. It reads original source URIs and applies crop, transforms, color/LUT/mask/chroma, package effects, transitions, overlays, canvas, audio mix, output cadence, and HDR-to-SDR policy. Preview quality and proxy choices do not change export settings or source selection.
