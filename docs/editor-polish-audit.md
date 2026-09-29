# Recly editor polish audit

Audit date: 2026-09-16. Source version at baseline: `3.7.0-capture-performance`
(`versionCode 60`). This document distinguishes source-confirmed behavior from device validation.

## Repository and resolved platform

- Confirmed in source: one Android application module (`:app`), Gradle wrapper 9.7.1, Android
  Gradle Plugin 9.3.2, compile/target SDK 37, min SDK 29, Java 17 and Media3 1.11.0
  (`transformer`, `exoplayer`, `effect`). Kotlin is supplied by the Android plugin; no independent
  Kotlin upgrade is part of this work.
- No Git metadata is present at this checkout root, so branch/commit/diff history is unavailable.
  Existing files and unrelated subsystems are treated as user-owned.
- Baseline already executed immediately before this editor pass:
  `./gradlew --no-daemon testDebugUnitTest` passed 153 tests and
  `./gradlew --no-daemon lintDebug assembleDebug` succeeded. This is a correctness baseline, not a
  GPU or editor-performance measurement.
- Device status: a Xiaomi 2201117TG is connected and the application launches. No controlled test
  media, Media3 GPU capture or consent-driven editor scenario has yet been executed, so visual
  parity, export latency, PSS, GPU memory and frame latency remain unmeasured.

## Responsibility map

Status values: **confirmed** means inspected in source; **tested** means a JVM test exists and has
passed; **hypothesis** needs runtime profiling; **absent** means no such implementation was found.

| Responsibility | Real class/file | Input state | Output/consumers | Thread/owner | Status and risk |
| --- | --- | --- | --- | --- | --- |
| Editor/lifecycle | `ui/EditorActivity.kt` | Intent, retained session, project store | Views, preview, autosave, export service | Main + one serialized I/O executor | Confirmed. Large but responsibilities are already delegated. Autosave snapshot is captured before background write. |
| Project source of truth | `domain/Project.kt`, `Tracks.kt`, `Studio.kt` | Immutable-style data classes | History, render plan, codecs, UI | Shared by value | Confirmed/tested. Collections are typed read-only but callers can still pass mutable implementations; history does not currently freeze them. |
| Commands/locks | `core/EditorSessionController.kt`, `TrackEditing` | Candidate `Project` | History current state | Main | Confirmed/tested. Locked tracks are rejected in state layer. |
| History | `history/ProjectHistory.kt` | Whole-project snapshots | undo/redo | Main | Confirmed/tested for basic undo. Limit 50 and no media payloads, but no formal begin/update/commit/cancel gesture contract and no defensive deep snapshot. |
| Persistence | `project/ProjectStore.kt`, codecs | Project snapshot | Atomic `.r15`, legacy backup | Serialized Activity I/O | Confirmed/tested. Atomic move/fsync, schema 1–10 migration, bounded counts. Unknown future schema is rejected rather than overwritten. |
| Timeline | `timeline/TimelineView.kt` | Project, playhead, touch | seek/trim/move commands | Main | Confirmed. Draws visible clips/lanes and requests cached media. Seeks are coalesced by Activity. Zoom is bounded, but pinch is centered on playhead rather than focus; fit currently moves the playhead. |
| Preview | `preview/EditorPreview.kt` | Project snapshot/revision-like generation | CompositionPlayer Surface | Main/Media3 owners | Confirmed. Latest buffered seek wins, player calls remain on main, callbacks check generation/player identity, Surface recreation and release exist. Hypothesis: compatibility rebuild thresholds require device profiling. |
| Change classification | `core/EditorChangeClassifier.kt` | before/after projects | runtime update or Composition rebuild | Main | Confirmed/tested. Selection/panels are outside Project. Existing transform/studio/effect values update atomically; graph-shape changes rebuild. |
| Render plan | `render/RenderPlan.kt`, `ProjectComposition.kt` | Project snapshot | Media3 Composition | Main build; Media3 render | Confirmed/tested. Adjacent untouched splits coalesce and multitrack order is explicit. |
| Realtime render state | `render/RealtimeProjectState.kt` | immutable-style Project | effects/overlays | AtomicReference, render callbacks | Confirmed. Snapshot collection mutability is the remaining ownership risk. |
| Time/speed | `domain/TimeMapping.kt`, `render/ClipSpeedProvider.kt` | source microseconds, speed points | project/source mapping, Media3 speed | Pure | Confirmed/tested. Persistent time is microseconds; speed points live on source axis; output integrates `1/v(s)` using a bounded piecewise schedule shared with Media3. |
| Keyframes/easing | `Studio.kt`, `CubicBezier.kt`, keyframe UI | source microseconds | transform/effect values | Pure + main UI | Confirmed/tested. Bézier solves x before y, overshoot easings are preserved, final physical properties clamp. Lists are validated sorted/unique. |
| Transform handles | three `preview/*HandlesView.kt` files | viewport touch + project values | one committed edit on UP | Main | Confirmed. Text rotation-aware hit-test exists. Sticker hit-test ignores rotation and both hitboxes use raw px instead of a device-independent minimum. |
| Effects | `assets/*`, `render/PackageEffect.kt` | definition + base/keyframed values | custom GL shader | Media3 GL context | Confirmed/tested for package validation. Program belongs to and is released with its GL owner. Custom Gaussian weights sum to 1.101083; ripple distance is anisotropic; RGB relies on sampler edge behavior; glow has a hard threshold. |
| Color/LUT/masks | `StudioGrade`, `CubeLut`, `StudioEffect`, studio shaders | grade snapshot/LUT | GL output | I/O parsing + Media3 GL | Confirmed/tested. LUT parser is bounded and finite, parsing occurs off main in Activity. Shader documents Media3 linear input then display-space controls; preview/export share it. Runtime HDR/color validation remains pending. |
| Audio/waveform | `audio/WaveformCache.kt`, `WaveformSamples.kt` | URI/source duration | reduced peak envelope | One bounded worker + main callback | Confirmed/tested for envelope logic. Cache is byte/count bounded and PCM is not retained wholesale. Retime/waveform visual parity needs device scenario. |
| Thumbnail cache | `timeline/ThumbnailCache.kt` | clip URI/time/size | small Bitmap cache | One worker + main callback | Confirmed. Requests/cache are bounded and close releases worker/bitmaps. Cache hit/miss performance is unmeasured. |
| Text/stickers | domain + handle views + render overlays | timed immutable-style clips | preview/export overlay | Main + Media3 render | Confirmed. Preview/export use shared project snapshot. Rotated sticker selection is a confirmed geometry defect. |
| Transitions | `ClipTransition`, `ProjectComposition.TransitionMatrix` | clip transition fields | video transform/alpha | Media3 render | Confirmed. Existing NONE/black/white transitions are preserved; no new transition system is justified. |
| Export | `export/EditorExportService.kt`, `EncoderSupport.kt` | persisted export snapshot | cache MP4 then pending MediaStore item | Service main + one I/O executor + Media3 | Confirmed. Job is isolated from later edits, cancellation is atomic/idempotent, incomplete MediaStore item is deleted. Preflight checks codec/space/assets but reports generic source failure later; device export remains unvalidated. |

## Temporal contract confirmed

- Persistence and domain calculations use signed 64-bit microseconds.
- Clip source interval and all timed overlays follow `[start, end)` in active checks.
- `VideoClip.inUs/outUs` are source timestamps. `SpeedPoint.sourceUs`, transform keyframes and
  effect keyframes are also source-anchored.
- `ClipTimeMap` maps trimmed source time to edited local time; `TimedVideoClip.startUs` places that
  local time on the composition axis. `PackageEffect` receives composition presentation time,
  derives local time, then source time for parameter keyframes. `uTime` remains local edited time
  for deterministic animation.
- Export and preview call the same `ProjectComposition` builder and time-mapping classes. Actual
  decoded-frame parity and VFR seek precision are pending device/GPU validation.

## Shader inventory

All 18 custom assets from the prompt are present: directional blur, flash, gaussian, glow, lens,
mirror, neon edge, noise, pixel, posterize, RGB split, ripple, scanlines, shake, VHS, vignette, wave
and zoom pulse. `studio_fragment.glsl`, `studio_vertex.glsl`, and shared effect header/footer are also
present. Effects sample one input texture and the footer mixes the result with the original using
bounded intensity. Programs are compiled per Media3 effect instance/context and deleted on release.

Confirmed correction targets:

- Gaussian: nine-tap cross/diagonal approximation has total weight 1.101083, increasing constant
  RGB and alpha. A safe normalization correction is required. A true two-pass separable blur is not
  equivalent to two functions in one shader and must only be introduced through a real multipass
  effect contract.
- Ripple: radial distance is calculated directly in UV, so portrait/landscape rings are elliptical.
- RGB split: `uv ± d` is not locally clamped; effective border behavior depends on sampler state.
- Glow: four diagonal samples are thresholded after averaging with a hard edge and fixed gain.
- Studio: straight/premultiplied behavior is explicit at the final compositor boundary, but HDR and
  vendor GPU behavior remain runtime validation items.

## Initial staged plan

1. Formalize transactional history and defensive project snapshots; extend state/history tests.
2. Correct confirmed shader math without changing effect IDs/parameters; add static contract tests
   and document visual compatibility.
3. Fix transform hit geometry and minimum touch targets; refine timeline behavior that can be
   verified without inventing a new scrolling architecture.
4. Re-run editor tests, full unit suite, lint and APK build. Use the connected device for install,
   launch and non-destructive UI checks. GPU/export/visual tests requiring media interaction will be
   marked pending unless a reproducible fixture can be exercised automatically.
