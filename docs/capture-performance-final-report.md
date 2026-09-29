# Capture performance implementation report

Date: 2026-09-16. Target version: `3.7.0-capture-performance` (`versionCode 60`).

## Scope and source audit

The existing capture engine was changed in place. The audit covered
`ReplayCaptureService`, `ScreenCaptureController`, `HardwareAvcSelector`, `AvcEncoder`,
`InternalAudioEncoder`, `EncodedOutputAssembler`, both encoded ring buffers, encoded samples and
snapshots, `Mp4ClipWriter`, `ContinuousMp4Recorder`, `AdaptiveQualityGovernor`,
`VideoCapturePolicy`, orientation policy, stability settings and `CaptureMode`. No parallel copy of
the engine was introduced.

Files modified: `README.md`, `app/build.gradle.kts`, `AdaptiveQualityGovernor.kt`,
`AvcEncoder.kt`, `HardwareAvcSelector.kt`, `ScreenCaptureController.kt`,
`VideoCapturePolicy.kt`, `EncodedOutputAssembler.kt`, `ReplayCaptureService.kt`,
`MainActivity.kt`, `SettingsActivity.kt`, `activity_main.xml`, `activity_settings.xml`,
`AdaptiveQualityGovernorTest.kt` and `VideoCapturePolicyTest.kt`.

Files added: `CapturePerformanceMode.kt`, `CaptureSessionConfig.kt`,
`CaptureGenerationGuard.kt`, `CaptureResourcePolicy.kt`, `CaptureFeatureFlags.kt`,
`CaptureDevTelemetry.kt`, `CaptureGenerationGuardTest.kt`, `CaptureResourcePolicyTest.kt`, this
report and `capture-performance-baseline.md`.

Before and after the change, video remains:

```text
MediaProjection -> VirtualDisplay -> MediaCodec Surface -> asynchronous AVC callback
-> owned compressed access unit -> compressed ring/normal recorder -> MediaMuxer
```

There is still no Bitmap, ImageReader, raw pixel conversion, OpenGL readback, raw-frame ring,
continuous Replay file or Replay re-encoding. AAC remains compressed in its own bounded ring.

## Implemented engine changes

- `CapturePerformanceMode` and its preference store add independent `ECONOMY` and `QUALITY`
  policies. Both are valid for Replay and normal recording; `CaptureMode` still means only
  `REPLAY` or `NORMAL`.
- `CaptureSessionConfig` is an immutable record of the final mode, dimensions, FPS, bitrate,
  codec, audio decision, Replay duration, thermal policy, session generation and encoder
  generation. Callbacks use this decision instead of rereading preferences.
- `VideoCapturePolicy` now computes bitrate from resolution, FPS and performance profile.
  Economy is lower than Quality while retaining a bitrate floor; codec bitrate ranges are applied
  by `HardwareAvcSelector`.
- `HardwareAvcSelector` remains AVC hardware-only. It filters encoders/aliases/software codecs,
  prefers VBR, centralizes alignment/aspect-ratio sizing and produces a finite fallback list that
  never increases workload. A reasonable requested/aligned configuration is attempted even when
  an OEM capability table under-reports it; actual `configure()` failure determines fallback.
- `AvcEncoder` still uses asynchronous `MediaCodec`, `COLOR_FormatSurface` and
  `createInputSurface()`. Configure retries are bounded and progressively remove optional frame
  controls. B-frames start at zero. Old-generation callbacks cannot append, report errors or
  mutate output after an encoder/session replacement.
- `EncodedOutputAssembler` keeps the necessary single compressed-payload ownership copy. Its rare
  split-access-unit path no longer creates a temporary byte array for every fragment through
  `ByteArrayOutputStream`; it uses one bounded accumulator. The common path still performs one
  allocation/copy before releasing the codec buffer.
- `CaptureGenerationGuard` supplies monotonic session and encoder generations. MediaProjection,
  audio and video callbacks verify their generation. Adaptive encoder replacement updates the
  immutable session record and the existing ring format boundary logic prevents incompatible AVC
  epochs from being saved together.
- With audio disabled, `ScreenCaptureController` does not construct `InternalAudioEncoder`; thus it
  creates no `AudioRecord`, AAC codec, audio worker or audio callback. Android 16/SDK 37 consent is
  also requested without audio. Enabled audio keeps the existing monotonic clock/AAC path.
- `AdaptiveQualityGovernor` now distinguishes Economy from Quality, consumes thermal headroom at a
  two-second sampling interval, validates invalid/NaN vendor values, retains hysteresis/cooldown
  and slow recovery, and continues evaluating escalating pressure while already protected.
  Economy responds earlier; Quality ignores mild pressure and needs sustained strong pressure,
  while severe thermal signals protect either profile.
- Normal recording tries the Surface-input suspend parameter during pause and safely falls back to
  the existing recorder pause/timestamp strategy if the codec rejects it. Replay never receives
  the suspend parameter.
- Debug builds have low-frequency session/health/save telemetry for codec, hardware status,
  profile, delivered FPS ratio, bitrate utilization, stalls, thermal status/headroom, ring usage
  and save duration. There is no per-frame logging. Riskier codec/pool features are behind internal
  flags and disabled by default.
- Settings and the capture screen expose the performance profile and internal-audio switch without
  redesigning the rest of the application.

## Lifecycle and bounded work preserved

MediaProjection callbacks are unregistered, VirtualDisplay and Surface are released, codecs/audio
workers are stopped, adaptive Handler callbacks are removed and generations are ended at stop.
Replay save remains a shallow immutable snapshot followed by muxing on one serialized writer; an
atomic gate permits only one save, so slow storage cannot create an unbounded save queue. Normal
recording retains its bounded sample queue. Rotation continues to be scaled into a fixed encoder
Surface; an adaptive format change uses a new encoder generation and the ring's format boundary.

## Memory decision

No encoded byte pool/arena was enabled. Snapshots already share immutable payloads with the ring,
so a pool would require leases/reference counting across eviction and concurrent muxing. There is
no device allocation profile proving that added ownership risk is warranted. The safe improvement
was limited to removing chained copies from fragmented access units. This is also controlled by the
disabled `useEncodedBufferPool` feature flag.

## Tests and validation

The JVM suite covers proportional/profile bitrate, sizing/alignment, finite non-increasing codec
fallback, audio-disabled initialization policy, normal-only codec suspension, governor healthy,
mild/strong/thermal pressure, Economy versus Quality, cooldown/slow recovery, pressure escalation,
session/encoder generations, orientation, encoded output assembly, video/audio rings, overflow,
keyframe selection, immutable shallow snapshots, concurrent append/snapshot, and PTS rebasing.
Presentation timeline tests verify non-negative and strictly increasing timestamps; audio snapshot
tests verify selection against the video source boundary.

Final command results:

```text
./gradlew --no-daemon testDebugUnitTest
BUILD SUCCESSFUL — 153 tests, 0 failures, 0 errors

./gradlew --no-daemon lintDebug assembleDebug
BUILD SUCCESSFUL — lint completed and debug APK assembled
```

Lint produced no blocking errors. Its report contains 501 non-blocking warnings across the existing
application (principally UI/i18n and layout recommendations); these are not runtime performance
measurements and were not suppressed as part of the capture-engine work.

## Device measurements

No Android device was visible during the baseline/engine implementation. A Xiaomi 2201117TG became
available only after the final build. The APK was pushed to the phone's Downloads folder, installed
successfully, its package was verified as `versionCode 60` / `3.7.0-capture-performance`, and a cold
launch completed into `MainActivity` with a live app process and no fatal exception in the sampled
logcat. The device UI hierarchy exposed the `Econômico`, `Qualidade` and `Áudio Ativo` controls.
MediaProjection consent and a controlled screen workload were not available for automated
capture profiling. Therefore no numeric claim is made for CPU, PSS, Java/native heap,
allocations/second, GC, delivered FPS, temperature, energy or save latency. The scenarios and
commands needed for those measurements are listed in `capture-performance-baseline.md`.

The following remain unmeasured on hardware: static 1080p30, moving 1080p60, Economy/Quality,
audio on/off, three consecutive saves, 15/30/60-second A/V clips, portrait/landscape transitions,
90/120 Hz displays and 5/15/30/60-minute thermal runs. These results must not be inferred from unit
tests or compilation.

## Deliberately not enabled and remaining risks

- HEVC and AV1 were not enabled: neither was requested by the setting, and AVC hardware is the
  compatibility-first engine. Software fallback remains refused to prevent an accidental thermal
  regression.
- Codec complexity, priority and latency keys remain disabled until a real-device vendor matrix is
  available. Optional FPS/operating-rate controls have bounded configure fallbacks.
- Thermal headroom and capability tables are OEM inputs and may still be inaccurate; invalid
  values fall back to thermal status and actual codec configure attempts.
- `PARAMETER_KEY_SUSPEND` has no portable query that proves vendor behavior. It is normal-recording
  only, best effort, and rejection preserves the previous pause path, but still requires device
  validation.
- Allocation/GC counters are not sampled continuously because doing so would add diagnostic load;
  they should be collected with Android Studio/Perfetto during the documented device runs.
- Capture, audio permissions, OEM codec behavior, rotation and actual A/V drift still require the
  connected-device matrix above before claiming measured performance improvement.
