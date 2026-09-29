# Reference Style Analyzer

The feature is deliberately local and non-generative. It reads observable properties from one rendered reference video and maps only supported operations to Recly's existing project model.

## Pipeline

1. `ReferenceMediaProbe` validates the URI and reads real duration, dimensions, rotation, frame rate, tracks and a partial-content fingerprint.
2. `ReferenceFrameSampler` samples at 150 ms (bounded to 2,400 frames) and retains only compact signals. Candidate windows are refined at about 33 ms. Bitmaps are recycled immediately.
3. `ReferenceVisualDetectors` combines histogram, luminance, edges, global translation/scale and temporal return checks for cuts, transitions, flashes, zoom, shake, blur and RGB displacement.
4. `TextStyleAnalyzer` uses bundled on-device ML Kit OCR sparsely. It drops long-lived spatial text such as HUD/watermarks and stores only aggregate style—not recognized caption content.
5. `ReferenceAudioAnalyzer` decodes PCM off the main thread, produces 20 ms RMS/peak/flux bins and keeps locally adaptive, periodic onset candidates as beats.
6. `ReferenceStyleProfileBuilder` rejects low-confidence/outlier evidence, calculates robust statistics and correlates recurring effect combinations.
7. `ReferenceStylePlanner` analyzes the destination clip and selects its audio/motion events. Reference timestamps are never copied.
8. `ReferenceStyleExecutor` delegates to the existing `AutoEditExecutor`, producing real cuts, transform keyframes, `EffectInstance`s, captions and—only when global timing is safe—source-anchored speed points. One `ProjectHistory.apply` call makes the operation undoable.

Profiles are atomically stored under the app's private `reference-styles` directory. `analysisVersion`, fingerprint changes, explicit re-analysis, or a missing cache cause fresh analysis. Source media is referenced by SAF URI and is never copied or modified.

Heavy modules run sequentially on one worker. Cancellation is checked between frames/audio buffers; codecs, retrievers, OCR clients and bitmaps are released in `finally` blocks. A failed OCR/audio/effect-analysis module records a warning and does not discard other results.

## Verification boundary

Pure detector, statistics, mapping, determinism and plan validation are covered by JVM tests. `tools/generate_reference_test_videos.sh` produces controlled hard-cut, flash, punch-zoom and fast-pan-negative fixtures. Decoder behavior, OCR model execution, thermal/memory profiling, preview/export visual equivalence and process-death behavior require an Android device; they cannot be claimed from JVM tests.
