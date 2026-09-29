# Recly editor polish — implementation report

Date: 2026-09-16. Version: `3.8.0-editor-polish` (`versionCode 61`). The detailed source map and
baseline are in `editor-polish-audit.md`.

## Outcome by requirement area

| Area | Status | Evidence/outcome |
| --- | --- | --- |
| Existing architecture and features | Already existing and verified | Media3 1.11 preview/export, XML/Views UI, multitrack, speed curves, keyframes, effects, color, LUT, audio, text, stickers and transitions remain in their existing components. Capture/login/navigation were not refactored. |
| History and gesture contract | Implemented and tested | `ProjectHistory` now has begin/update/commit/cancel, one entry per effective gesture, rollback, redo invalidation and bounded retention. Existing UI gestures already commit only on UP/Apply; `ACTION_CANCEL` keeps rollback behavior. |
| Snapshot independence | Implemented and tested | History defensively detaches nested project collections, tracks, grades, effect values/keyframes, transform keyframes and speed curves. Caller-owned mutable lists/maps cannot mutate prior actions. |
| Time mapping/easing | Already existing and verified; optimized | Source/project microsecond contracts, source-anchored speed curves, Bézier x-solving and overshoot semantics were preserved. Per-frame transform/effect keyframe lookup is now binary rather than linear. Existing timing tests remain authoritative. |
| Preview and concurrency | Implemented and tested statically | Existing latest-only seek coalescing and generation/player identity checks were preserved. Paused preview no longer runs its 40 ms boundary polling loop. Detailed traces and shader compile logs are debug-only through the application debuggable flag. |
| Hot-path allocations | Implemented and tested statically | `RealtimeProjectState.clip()` no longer constructs `allVideos` on every effect frame. Animated/moved text reuses a bounded 64-entry layout cache instead of rebuilding `StaticLayout` for position-only frames. Overlay state no longer relies on a hash collision-prone integer. Runtime allocation counts remain unmeasured. |
| Transform ergonomics | Implemented and tested | Text and sticker hit tests use inverse rotation and a minimum 48 dp target. Touch interception is released on UP/CANCEL. Timeline cancel now clears every drag/trim state. |
| Gaussian effect | Implemented and tested mathematically | Custom nine-tap weights changed from total 1.101083 to approximately 1.0, preserving constant RGBA energy. Radius 0 is accepted and returns identity. The separate clip blur continues to use Media3 `GaussianBlur`. |
| Ripple/RGB/Glow | Implemented and tested statically | Ripple radius is aspect-corrected; RGB split clamps both displaced samples explicitly; Glow uses a soft threshold and reduced fixed gain. Effect IDs, parameter IDs and animation paths remain stable. |
| LUT/color | Implemented and tested | LUT strength was previously baked once into the uploaded texture despite being classified realtime. The full LUT is now immutable in the texture and strength is a live shader uniform shared by preview/export. Cube shape, finite values and strength are validated. |
| Export | Implemented without device export validation | Preflight now rejects inaccessible media/LUT sources and zero-duration projects before Transformer. AVC encoder selection excludes aliases/software encoders. Gallery publication verifies nonempty complete copy and retains pending-item cleanup on failure/cancel. |
| Persistence/migrations | Already existing and verified | Schema 1–10 reading, schema-6 fixture, atomic fsync/move, failed-save preservation, legacy backup and path traversal tests remain passing. No schema change was needed. |
| Audio/waveform/caches | Already existing and verified | Reduced waveform envelopes, bounded workers/caches and lifecycle close paths were preserved. No PCM retention or new audio processing was introduced. Device retime/audio parity is pending. |
| Accessibility/visual identity | Implemented partially | Minimum transform targets improved while the existing black/glass Recly appearance remains. Full TalkBack/custom timeline action review is pending device interaction. |

## Main files changed

- `editor/history/ProjectHistory.kt`: transactional edits and detached snapshots.
- `editor/domain/TimeSearch.kt`, `Studio.kt`, `assets/AssetModels.kt`: shared binary temporal lookup.
- `editor/render/RealtimeProjectState.kt`: allocation-free active clip lookup.
- `editor/preview/EditorPreview.kt`, `core/EditorDiagnostics.kt`: paused polling removal and debug-only traces.
- `editor/preview/TransformHitTest.kt`, text/sticker handle views, `TimelineView.kt`: rotated/minimum hit targets and complete cancellation cleanup.
- `editor/render/TextCanvasOverlay.kt`: bounded text layout reuse and exact overlay state.
- `editor/render/StudioEffect.kt`, `studio_fragment.glsl`, `CubeLut.kt`: realtime LUT strength and validation.
- Gaussian, Ripple, RGB and Glow fragment assets: targeted numerical/edge corrections.
- `EditorExportService.kt`, `EncoderSupport.kt`: source/output preflight and hardware encoder filtering.
- New regression tests: history, shader contracts, LUT behavior and transform hit geometry; existing classifier/render tests were extended.

No Kotlin, AGP, Gradle or Media3 dependency was upgraded. No new framework, ViewModel, repository,
cloud feature, AI feature, FFmpeg path or Compose migration was introduced.

## Intentional visual changes and compatibility

Effect serialization is unchanged (`assetId`, version and parameter IDs remain the same), so old
projects reopen without migration. The following output changes are intentional bug fixes:

- Gaussian no longer brightens a flat RGB/alpha input by roughly 10.1%.
- Ripple rings remain circular in visual space across aspect ratios.
- RGB split repeats the edge pixel instead of depending on unspecified sampler wrapping.
- Glow threshold transitions over 0.12 rather than switching abruptly and its fixed energy gain is
  1.35 instead of 2.0.
- LUT intensity changes now affect the current Composition immediately; strength zero is identity.

## Tests and build evidence

Incremental checks completed during implementation:

- History/state/editing group: successful.
- Shader/assets/effect group: successful.
- Transform/LUT/classifier group: successful.
- Entire `com.termex.replay15.editor.*` JVM suite: 93 tests, zero failures/errors.

Final full JVM suite:

```text
./gradlew --no-daemon testDebugUnitTest
BUILD SUCCESSFUL — 168 tests, 0 failures, 0 errors
```

```text
./gradlew --no-daemon lintDebug assembleDebug
BUILD SUCCESSFUL — debug APK assembled, 0 blocking lint errors
```

The lint report retains 501 non-blocking warnings across the full existing application, primarily
UI internationalization/layout recommendations. They were neither converted into fake editor
performance work nor suppressed.

## Measurements and limitations

No CPU, Java/native/GPU memory, allocation rate, p50/p95 seek latency, slow-frame rate, shader
compile time or export duration is claimed. The attached task arrived without controlled media or
visual reference frames, and static code inspection/unit tests cannot supply those measurements.

The connected Xiaomi device permits install/launch checks, but the following still need a manual,
repeatable media matrix: preview versus decoded export frames, 1080p30/60 and 4K, VFR/HDR, cold/warm
caches, long undo/redo sessions, process death after autosave, Surface recreation, audio retime and
cancel/retry export. No instrumented test source set or Macrobenchmark module exists; neither was
invented merely to report a number.

The `3.8.0-editor-polish` APK was copied successfully to the device at
`/sdcard/Download/Recly-3.8.0-editor-polish-debug.apk`. The subsequent ADB replacement install was
cancelled by the device/user policy with `INSTALL_FAILED_USER_RESTRICTED`; no bypass was attempted.
The phone therefore remained on `3.7.0-capture-performance` during this run, and a 3.8 device smoke
test is classified as blocked rather than reported as successful.

## Deliberately pending

- A true separable custom Gaussian needs two actual render passes and an intermediate texture. The
  current package-effect contract supplies one `GlShaderProgram`; pretending two functions in one
  fragment are two passes would be incorrect. Static clip blur already uses Media3's separable
  implementation. Replacing the animated/intensity-mixed package effect requires a measured,
  compatibility-preserving multipass design and remains pending.
- Timeline pinch focus anchoring requires separating viewport scroll from the playhead; the current
  view intentionally centers time on the playhead. That architectural change was not smuggled into
  a polish pass.
- HDR/color transfer and premultiplied-alpha parity across vendor GPUs remain device-test items.
- Preview/export frame comparison, shader compilation on multiple GPUs and audiovisual drift are
  not validated by JVM tests.
