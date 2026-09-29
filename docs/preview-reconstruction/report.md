# Preview engine reconstruction

The editor preview now uses `EditorPreviewEngine`. Video follows this path:

`ActiveClipResolver -> MediaExtractor -> MediaCodec -> Surface -> SurfaceTexture -> OES texture -> persistent EGL renderer -> editor SurfaceView`.

Playback uses a monotonic preview clock. Pause retains decoder sessions and cached 2D frame textures. Render-only edits update immutable render state and redraw without seeking. Seek requests carry monotonically increasing generations; the renderer discards stale results. Active sources have priority under a bounded decoder budget, and the next source is prewarmed near a boundary.

The GPU renderer owns transforms, crop, opacity, color/filter uniforms, package effect shaders, effect masks, adjustment effects, two-input transition shaders, layer composition, and cached text/sticker overlays. Programs and FBO textures remain resident across frames. Audio uses a separate audio-only ExoPlayer path and never controls video readiness.

`ProjectComposition` is export-only. It continues to build the Media3 Transformer composition from original source URIs. All preview parameters and realtime state branches were removed from the export graph.

## Old preview deletion

Deleted production files:

- `preview/EditorPreview.kt`
- `preview/EditorPreviewSession.kt`
- `preview/PreviewDecoder.kt`
- `preview/PreviewRenderer.kt`
- `preview/PreviewSurface.kt`
- `preview/SeekCoordinator.kt`
- `preview/StructuralGraphController.kt`
- `preview/ProjectRenderState.kt`
- `preview/GpuResourceCache.kt`
- `preview/PreviewDiagnostics.kt`
- `render/RealtimeProjectState.kt`
- `core/LatestSeekWinsQueue.kt`
- `core/EditorPerformanceMetrics.kt`

Tests tied to the deleted implementations were removed and replaced by engine clock, resolver, decoder-budget, generation/coalescing, change-classification, and hardware decoder/OES tests.

The preview source and `EditorActivity` contain zero occurrences of the removed player APIs and recovery workarounds listed in the reconstruction request.

## Validation

- `testDebugUnitTest`: host suite.
- `assembleDebug`: debug APK.
- `:editor-engine:compileDebugAndroidTestKotlin`: hardware decoder/EGL test compilation.
- Runtime instrumentation requires an attached Android device. This environment has no ADB device and no installed emulator image, so runtime latency values were not fabricated.

Debug builds log and expose `firstFrameMs`, `playResumeLatencyMs`, `seekLatencyMs`, decoder create/release/active counts, surface and EGL counts, shader compilation count, average/max render time, and stale-seek drops through `PreviewDiagnostics`.
