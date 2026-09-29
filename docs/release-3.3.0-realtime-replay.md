# Recly 3.3.0 - Realtime editor and configurable Replay

## Architecture before

The project is a single Android Views application module. Capture uses MediaProjection, hardware
MediaCodec AVC/AAC encoders and bounded encoded ring buffers. The editor uses Media3
CompositionPlayer for preview, Transformer for export, a shared ProjectComposition render graph,
OpenGL GlEffect nodes, schema-versioned projects, multitrack video/audio/text/stickers and an asset
package repository.

## Root causes

- EditorActivity reloaded EditorPreview after every ProjectHistory transaction.
- EditorPreview released and recreated CompositionPlayer on every load.
- Tool sheets paused playback merely by opening.
- Shader programs held construction-time effect, transform and grade values.
- Text overlay invalidation only hashed IDs, so property changes could retain a stale frame.
- Replay duration and UI progress were coupled to a hardcoded 15 second window.
- Replay still depended on a TYPE_APPLICATION_OVERLAY floating control.

## Implemented

- RealtimeProjectState supplies immutable project snapshots to the GL/render thread.
- EditorChangeClassifier separates hot render changes from structural composition changes.
- Structural changes call CompositionPlayer.setComposition on the existing player.
- Effect parameters, grade, masks, transforms, keyframes and text/sticker properties support hot
  preview updates. Draft edits do not pollute undo history.
- Split, undo and redo preserve playback intent and timeline position.
- Player and shader lifecycle events are available under ReclyPreview and ReclyEffects log tags.
- The data-driven effect library now contains 18 GPU effects.
- Six versioned composite presets build real sequential multi-pass effect stacks.
- Replay supports real 5, 10, 15, 30, 45 and 60 second encoded windows.
- Video and audio byte budgets scale with the configured retained duration.
- Replay notification displays the encoder's actual dimensions, FPS and bitrate.
- Save is sent directly to ReplayCaptureService and remains guarded against concurrent saves.
- ReplayFloatingButton and SYSTEM_ALERT_WINDOW were removed. Old SIDE_BAR preferences migrate to
  notification control. Quick Settings and optional double-volume-down remain supported.

## Preview and export

Both paths continue to use ProjectComposition. PackageEffect, StudioEffect and timed overlays read
the same project and effect definitions used by Transformer. Structural native Media3 effects are
replaced on the existing CompositionPlayer; custom parameter changes do not rebuild the graph.

## Verification

- Kotlin compilation: passed.
- JVM unit tests: 129 tests passed before the final release verification; new tests cover realtime
  change classification, preset model validation and duration-aware buffer limits.
- JSON effect catalog: parsed with jq, 18 definitions.
- Device instrumentation: pending because no ADB device is connected.

## Known limitations

- Temporal echo with previous-frame history is not enabled. Media3's one-input GlEffect contract
  needs a dedicated bounded frame-history node before this can be implemented without fake output.
- Native Media3 filter selection updates the existing player composition but is not a uniform-only
  hot update.
- Real device tests for MediaProjection, notification actions, OEM background behavior and GLSL
  driver compatibility are required before store distribution.
- Pause/Resume Replay is intentionally not shown because the current capture controller has no
  distinct true pause state; Off releases resources and Save snapshots while capture continues.
