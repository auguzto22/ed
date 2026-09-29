# Capture performance baseline

Date: 2026-09-15. Baseline source version: `3.6.1-editor-keyframe-fix` (`versionCode 59`).

## Pipeline confirmed from source

Video follows `MediaProjection -> VirtualDisplay -> MediaCodec input Surface -> AvcEncoder -> EncodedOutputAssembler -> EncodedRingBuffer/ContinuousMp4Recorder`. Replay snapshots share immutable compressed payloads and `Mp4ClipWriter` writes them with `MediaMuxer`; there is no decode or re-encode during save. The normal recorder writes compressed samples to a bounded single-writer queue.

Audio follows `AudioPlaybackCapture -> AudioRecord -> AAC MediaCodec -> EncodedOutputAssembler -> EncodedAudioRingBuffer/ContinuousMp4Recorder`. Replay retains AAC, never PCM. The microphone is not opened by the capture engine.

The video hot path is Surface based (`COLOR_FormatSurface` and `createInputSurface`). It contains no Bitmap, ImageReader, CPU YUV/RGBA conversion, Canvas, OpenGL readback, or raw-frame ring buffer.

## Session concurrency and ownership

- Main thread: service commands, repository/UI state, notifications, MediaProjection callback.
- `Replay15-CaptureControl`: serialized start/stop control.
- `Replay15-CodecCallback`: asynchronous AVC MediaCodec callback and compressed output ownership transfer.
- `Replay15-InternalAudio`: blocking AudioRecord input plus AAC drain.
- `Replay15-AdaptiveQuality`: quality-health poll every 2 seconds.
- `Replay15-Mp4Writer`: serialized Replay snapshot muxing.
- `Replay15-ContinuousWriter`: bounded normal-recording muxer queue.

The codec output buffer is copied once into an owned `ByteArray` before `releaseOutputBuffer`. The common complete-access-unit path does one payload allocation/copy. Split access units currently allocate each fragment and `ByteArrayOutputStream.toByteArray()` creates a final copy. A snapshot allocates wrappers/list metadata but rebased `EncodedSample` objects share the immutable payload with the ring. Muxing uses one reusable direct buffer.

No encoded-buffer pool is enabled in the baseline. A safe pool would require leases/reference counts across ring eviction and concurrent muxing. It is not justified without allocation profiling showing split access units or payload churn as a material bottleneck.

## Existing safety behavior

- Hardware AVC is mandatory; software AVC is rejected.
- Candidate fallback is finite and lowers FPS/resolution through the candidate list.
- The ring is bounded by duration and bytes, trims to a keyframe, and clears on real AVC format/CSD changes.
- Only one Replay save is accepted at a time through an atomic guard and a single writer executor.
- Projection, VirtualDisplay, Surface, codecs, audio recorders, callbacks, handlers, and worker threads have explicit stop paths.
- Rotation content is scaled into the fixed session Surface; format changes are not mixed into one buffered epoch.
- Audio failure disables the audio track while video capture continues.

## Measurements actually executed

`./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` completed successfully before this baseline. The JVM suite contained 143 passing tests, with no failures or ignored tests. Android Lint completed successfully. These are correctness/build results, not performance measurements.

An ADB measurement attempt was made after the attached task was received. No device was connected at that time (`adb: no devices/emulators found`), so no CPU, PSS, heap, allocation, GC, delivered-FPS, temperature, bitrate, or save-latency number is reported here.

## Device scenarios not yet measured

- Replay 1080p30 static and Replay 1080p60 moving.
- Economy versus Quality, with internal audio enabled and disabled.
- Three repeated saves; 15, 30, and 60 second windows.
- Portrait/landscape transitions and prolonged 5/15/30/60 minute runs.
- 90/120 Hz source displays.
- CPU, total/Java/native PSS, allocations/second, GC, encoder output FPS/bytes, thermal status/headroom, and save latency.

Required device commands include `adb shell dumpsys meminfo com.termex.replay15`, `adb shell dumpsys activity service com.termex.replay15/.service.ReplayCaptureService`, `adb shell dumpsys thermalservice`, process/thread inspection, and sampled logcat DEV telemetry. MediaProjection consent and a repeatable moving workload must be provided on the device for comparable results.

## Profiling hypotheses, not measured facts

- Split access units may cause extra allocation through `ByteArrayOutputStream`; prevalence must be measured before replacing the simple ownership model.
- Encoder restarts during adaptive profile changes are higher risk than the two-second health polling itself.
- OEM codec capability reports and advanced MediaFormat keys may differ from actual configure behavior; fallbacks must be based on bounded real configure attempts.
- Audio PCM input-buffer use is already codec-owned; allocation pressure is more likely in compressed output ownership than in the blocking read loop.
