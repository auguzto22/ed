# Recly editor: source audit and evolution

## Baseline inspected (2026-09-08)

- One Gradle module, `:app`; application ID `com.termex.replay15`.
- Version 45 / `2.1.1-editor-fix`; AGP 9.3.2, built-in Kotlin plugin 2.2.10,
  Gradle 9.7.1, Java 17, compile/target SDK 37, minimum SDK 29.
- Media3 Transformer, ExoPlayer and Effect 1.11.0; no FFmpeg or custom JNI.
  APK native files are DataStore shared counters for four ABIs.
- Activities: OnboardingActivity, SignupActivity, MainActivity,
  EditorLibraryActivity and EditorActivity. All editor UI uses Android Views.
- Services: ReplayCaptureService, ReplayTileService,
  VolumeReplayAccessibilityService and EditorExportService.
- No ViewModel in the existing source. ReplayRepository holds capture state;
  ProjectStore owns binary project persistence; ProjectHistory owns snapshots.
- Capture: ScreenCaptureController / AvcEncoder / HardwareAvcSelector feed
  ContinuousMp4Recorder or EncodedRingBuffer. InternalAudioEncoder captures
  playback audio into EncodedAudioRingBuffer. It explicitly does NOT open the
  microphone; microphone recording exists in the editor's voiceover tool.
- Replay triggers: notification, floating side control, volume accessibility,
  and Quick Settings tile. Replay durations and sample windows are timestamp based.
- Editor: Project -> ProjectHistory -> ProjectStore. EditorActivity builds UI,
  imports media, owns selection, opens tools, manages preview and saves snapshots.
  This is the principal responsibility bottleneck (about 1,600 lines).
- Preview: EditorPreviewEngine / MediaExtractor / hardware MediaCodec /
  persistent EGL renderer / SurfaceView. Export: EditorExportService /
  Transformer / hardware H.264 and AAC / MediaStore.
- Shared rendering: ProjectComposition, StudioEffect GLSL, TextCanvasOverlay,
  TextLayout, EditorFonts. 32 filters, bounded CubeLut parser, five-point curves,
  masks, chroma and transform keyframes exist. Only one main video sequence exists.
- TimelineView renders video, text, stickers and audio intervals. ThumbnailCache
  has a 6 MiB LRU and a bounded background queue. Audio has no waveform decoder.
- ProjectCodec schemas 1-6; bounded collections; atomic temporary-file + fsync +
  rename saves. SAF retains grants; MediaStore uses IS_PENDING for export.
- Permissions: foreground service/projection/processing/dataSync, notifications,
  RECORD_AUDIO, overlay and internet. Accessibility and tile use binding permissions.
- Preview and export share domain time mapping and effect parameters while using
  independent runtime pipelines.

## Preservation boundary

Capture, service, media buffer and recorder settings are out of the editor refactor.
Their regression tests remain part of the build. A pre-change source archive is
`/home/jn/Downloads/Replay15-baseline-2.1.1-before-v3.tar.gz`.
No connected Android device was found during audit: hardware playback, capture,
multi-decoder limits and visual export comparison require device validation.

## Migration approach

Keep legacy `videos`, `audio`, `texts`, `stickers` fields as the authoritative
collections for existing tools. Add explicitly timed video tracks and track state;
derive render sequences from one shared plan. Migrate on read and save atomically.
New tools are separate controllers/panels, using the existing ProjectHistory.

## Primary API references

- https://developer.android.com/media/media3/transformer/composition
- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/media/media3/transformer/videocompositorsettings

The export compositor remains Media3-based. The interactive preview does not build
or prepare a Media3 Composition.
