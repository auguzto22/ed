# Recly 3.1 Motion

This increment adds working source-anchored speed ramps, Media3 preserve-pitch,
editable easing graphs, cubic Bezier, physical easings, 23 JSON animation presets,
independent RGB curves, HSL by eight color bands, five additional GPU masks and
expanded chroma controls. Each project mutation passes through ProjectHistory and
schema 9 persists main and overlay video values. Preview and export continue to use
ProjectComposition.

It does not complete the full V3 master brief. Missing major systems include true
A/B transitions, reverse processing, proxies, generic effect-parameter keyframes,
audio EQ/compressor/ducking/noise reduction, all requested downloadable asset
types, tracking, segmentation, automatic captions and on-device stress/export
validation. These remain roadmap items and are not exposed as working buttons.

## Verification

- `testDebugUnitTest`: 115 tests, zero failures, errors or skipped tests.
- `lintDebug`: zero errors and 135 non-blocking warnings.
- `assembleRelease` and release lint: successful.
- Host GLES: exact shipped shader compiled; identity, exposure, LUT, RGB curves,
  HSL, opacity, nine masks, mask movement/intensity, chroma, alpha blending and
  all six packaged effect shaders passed pixel checks.
- Capture, replay media, services, data and AndroidManifest match the pre-3.1
  archive byte-for-byte.
- APK: `Mauro-Recly-3.1.apk`, 17,666,372 bytes, versionCode 47,
  versionName 3.1.0-motion, minSdk 29, targetSdk 37.
- SHA-256: `c22012e17b080b4abc6a782a13b5dc96efcd2799b040c1ed0c33e47a247841dc`.
- APK Signature Scheme v3 verified; 16 KiB zip alignment verified.

No Android device was connected. Playback, recording, replay and export still
need a real-device smoke test, especially on the target Galaxy S9.

## Continuation in 3.2

Version 3.2.0-effect-keyframes (versionCode 48, project schema 10) adds real
keyframes for effect intensity and every float parameter exposed by a `.reclyfx`
manifest. The effect panel can add/update a complete parameter state at the cursor,
select an easing, view its graph, navigate to previous/next points and remove the
current points. Parameters are evaluated from source time in PackageEffect for both
preview and export and remain part of undo, autosave and project reopening.

The 3.2 release validation completed with 117 unit tests, zero failures, lint with
zero errors, release build, host GLES pixel checks and byte comparison of recorder,
replay, services, data and manifest against the protected baseline. The signed APK
is 17,682,756 bytes and its SHA-256 is
`024098e00da33a026ffc2964c7c57c482cc8ded1d4ef49dfe80397d0c6572d1d`.
