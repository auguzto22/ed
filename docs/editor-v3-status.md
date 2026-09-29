# V3 delivery scope, 2026-09-08

Application remains com.termex.replay15. Version 46 / 3.0.0-multitrack is an
incremental editor foundation, not completion of the entire Professional V3 brief.
There is no new app, Compose migration, FFmpeg dependency or artificial APK padding.

## Implemented in this increment

- Source/Gradle/service/permission audit and a pre-change source archive.
- EditorSessionController owns project history and lock checks; VideoTrackTools
  and EffectTools own the new panels. Existing Activity tools remain available.
- Project schemas 7/8, legacy read compatibility, bounded video tracks, track
  controls, timestamps, effect stacks, and existing atomic autosave/history.
- Explicitly placed video layers, PIP gestures, trim/split/move, duplicate/delete,
  order, visibility, mute, solo and lock. Up to seven overlays plus main video;
  hardware decoder limits can be lower, especially on a Galaxy S9.
- Shared multisequence CompositionPlayer/Transformer composition and alpha/chroma
  compositing. Preview retry no longer removes effects behind the user's back.
- Timeline overlay lanes with horizontal move/trim, existing zoom/snapping/markers,
  and real decoded PCM waveforms for imported audio lanes.
- Six adjustable GPU effects, ordered stacks, bounded effect-package installation,
  HTTPS catalog/downloader, checksum, favorites, search and pinned asset versions.
- Legacy recorder/replay source left unchanged. Existing tests remain enabled.

## Not yet implemented from the full request

Real A/B transition engine, speed curves/reverse, generic property keyframes and
Bezier graphs, JSON animation packages, proxy generation, audio EQ/ducking/denoise,
all-track waveforms, downloadable fonts/stickers/templates, a unified asset/cache
settings screen, advanced blend modes, additional masks, tracking, stabilization,
automatic captions, segmentation, AI enhancement and optional local models.

The preserved legacy features are not all upgraded to the proposed V2 interfaces.
There are no active buttons claiming the above unimplemented tools. The editor
is not certified against the 38-step professional MVP or the advanced video matrix.

## Verification and remaining device checks

Build/test commands use the bundled JDK17 and Android SDK. Unit tests cover existing
recorder/replay buffer timing plus project editing/persistence and the new track,
render-time, package security, effect serialization and waveform sample logic.
Host Mesa GLES tests compile the actual shipped GLSL and check pixel results,
alpha, chroma, LUT, and zero/nonzero intensity for the six included package effects.

No Android device or emulator is connected. Real decoder playback, three concurrent
videos, thermal/load behavior on S9, MediaStore export, A/V sync and visual equality
between preview/output MUST still be checked on hardware. Host GPU tests do not
prove Android Media3 integration. A failed codec or shader must show an error,
never export an intentionally simplified project without user approval.

## How to exercise the new UI

Open a recording in the existing editor. Use Video/PIP to add an overlay, select
its timeline lane, then move/trim it or open its controls. Drag/pinch/rotate the
selected preview outline and release to apply. Use Pilha de efeitos on the clip
or Efeitos in the toolbar to add the six built-ins, adjust intensity, and reorder.
Save, undo/redo, close/reopen, then export a short sample before editing a large
project. Keep originals and old project copies when testing the schema upgrade.
