# Recly Auto Edit

Auto Edit starts from the currently selected, already-created clip. It does not search the capture,
rank highlights, create another clip system or render an intermediate MP4. Its output is the same
`Project` metadata used by manual editing: trimmed/split `VideoClip`s, transform keyframes,
`EffectInstance`s and `TextClip`s. Consequently every generated decision is visible and editable,
uses the existing Media3 `ProjectComposition` in preview and export, survives `.r15` autosave, and is
reverted by one normal `ProjectHistory` undo.

## Pipeline

1. `ClipAnalyzer` decodes the audio track to 100 ms RMS/peak windows and samples scaled 160x90
   frames every 500 ms. It never scans the original capture outside the selected clip. Cancellation
   is checked between codec buffers and frames. Analysis is cached in memory by URI, trim, speed map,
   caption evidence and algorithm version.
2. Existing timed SRT/manual caption blocks are reused as word evidence. Recly does not currently
   ship an offline speech-to-text model, so Auto Edit disables caption generation when no timed text
   exists instead of fabricating a transcript.
3. `AutoEditPlanner` creates a deterministic, inspectable `AutoEditPlan`. Silence removal retains a
   natural margin, requires a measured quiet region and becomes less aggressive for moving imagery.
   Caption grouping follows pauses, punctuation, readable width and duration. Motion/audio peaks have
   minimum spacing and a small per-clip creative budget.
4. Validation rejects out-of-range or overlapping cuts, negative ranges and plans that remove the
   whole clip before any editor state changes.
5. `AutoEditExecutor` applies the plan once to a detached project value. Cuts use source-aware
   `ClipTimeMap`, zoom/reframe use existing transform keyframes, impact uses the existing
   `recly_shake` effect, captions use existing text presets, and audio uses bounded clip gain/fades.
6. The editor presents Original/Edited preview. Apply creates one history entry; cancel and closing
   the sheet restore the original composition. Autosave and export continue through existing paths.

## Moderation and conflict rules

- A quiet region keeps 170-520 ms depending on Natural/Balanced/Fast; no cut is placed in a decoded
  active-audio window. Imported word timing adds another boundary guard.
- Most edits are hard cuts. Auto Edit does not place a transition on every split.
- Zooms are 3-25%, 80-900 ms, spaced by style, and capped by clip duration.
- Automatic shake is limited to two events in Balanced or three in Strong, and is omitted for Clean,
  Cinematic and Podcast.
- Reframe uses a dead zone and smooth keyframes derived from the center of visual change. Gaming uses
  a smaller travel range to preserve gameplay/HUD context. If no reliable focus exists, the original
  aspect is kept rather than blindly center-cropping to 9:16.
- Independent timed layers are rippled after removed intervals. Overlay video clips are kept
  non-overlapping; captions inside a removed range are shortened or discarded.
- Audio gain is deliberately bounded to 0.75-1.25x and short fades are added at generated cuts to
  reduce clicks. No speech speed ramp is generated.

## Current boundaries

The project has no speech recognizer, face detector, semantic gameplay event model, beat detector,
music/SFX library or B-roll provider. Auto Edit therefore does not expose fake controls for those
features. It uses real decoded energy, frame change and imported timing evidence, and reports partial
analysis failures while retaining the decisions that can still be made safely.

JVM coverage includes determinism, silence margins, caption segmentation, effect density, editable
execution, one-step undo, `.r15` round-trip, and 5/15/30/60/90 second clips at 24/30/60 fps. Codec,
visual quality, A/V sync and hardware export still require the Android device matrix described in
`docs/editor-v3-status.md`; a desktop unit build cannot honestly replace those checks.
