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
2. Existing timed SRT/manual caption blocks are reused as word evidence. When the clip has no timed
   text, the panel transcribes it with the same caption service as "Legendas automáticas"
   (`GeminiCaptionGenerator`). If that service is unavailable (no key/backend, no internet) Auto
   Edit continues without captions and B-roll and says so in the preview; it never fabricates a
   transcript.
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

## Podcast reference style

Two panel options reproduce the look of podcast cuts (reference: short vertical clips with yellow
karaoke captions and stock photos over concrete mentions). Both default to off in
`AutoEditOptions`, so Reference Style and older callers keep their behaviour; the Auto Edit panel
turns them on.

- **Legendas estilo podcast** (`podcastCaptions`). `PodcastCaptions.chunk` cuts measured word
  timing into lines of at most 3 words / 16 characters, closing a line at a pause over 280 ms or at
  sentence punctuation. Edge punctuation is dropped from the display. Lines never overlap and every
  word cue stays inside its line. The executor replaces the clip's captions with these lines,
  styled by the new `podcast` animated caption style (Montserrat SemiBold bold, white with black
  outline, CAIXA ALTA, spoken word in yellow with a small pop). They are ordinary caption layers:
  the caption panel can restyle or edit them, and a cut inside a line is remapped like any text.
- **Imagens da internet (B-roll)** (`broll`).
  1. *Where*: `BrollAiSuggester` sends only the transcript text with word times to Gemini, which
     names the spoken word to illustrate and an English stock query. `BrollSuggestionParser` snaps
     each answer to the measured spoken word (±1.5 s) and drops unknown words, invalid queries and
     times outside the clip. Without a key or on any failure, `BrollLexicon` (concrete pt-BR words
     such as dinheiro, trabalho/bico/trampo, carro, casa, torneira, ralo; ambiguous words like
     "real", "nota", "conta", "tempo" are excluded) supplies the moments instead.
  2. *Timing*: `BrollPlanner` keeps the first 3 s (the hook) on the speaker, uses 0.8-2.5 s per image
     (1.5 s default), at least 2.5 s of speaker between images, at most one image per ~6.5 s
     (max 12), never the same keyword or search twice within 12 s, nothing in the last 0.3 s, and never an
     image across a removed pause.
  3. *Images*: `BrollImageFetcher` searches Pexels when `PEXELS_API_KEY` is set in the untracked
     `local.properties` (debug builds only, like the Gemini key), then Openverse without a key,
     restricted to CC0/public-domain, non-mature, non-SVG/GIF results so exports need no credit
     line. Downloads are HTTPS-only, size-limited (15 MB), cancellable, center-cropped to the final
     canvas aspect (9:16 after Auto Reframe) and saved as JPEG (long side <= 1920 px) in
     `filesDir/auto-broll`. A moment without a usable image is skipped and reported.
  4. *Placement*: the executor adds one new top video layer named "B-roll" with an image clip per
     moment (muted, slow 1.0 -> 1.08 push-in), mapped through the cuts; captions stay above it
     because texts are drawn after every video layer. If all seven overlay layers are taken, no
     B-roll is added. Images of a preview that is cancelled or regenerated are deleted; applied
     ones stay with the project like a freeze-frame still.

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

Speech recognition comes from the caption service and B-roll from public stock libraries (see
above). The project has no face detector inside Auto Edit, semantic gameplay event model, beat
detector or music/SFX library, so Auto Edit does not expose fake controls for those features. It uses real decoded energy, frame change and imported timing evidence, and reports partial
analysis failures while retaining the decisions that can still be made safely.

JVM coverage includes determinism, silence margins, caption segmentation, effect density, editable
execution, one-step undo, `.r15` round-trip, and 5/15/30/60/90 second clips at 24/30/60 fps.
`AutoEditPodcastBrollTest` covers podcast lines, the B-roll planner, AI answer parsing, stock
library parsing, image crop math, the B-roll layer, layer limits and persistence. Codec,
visual quality, A/V sync and hardware export still require the Android device matrix described in
`docs/editor-v3-status.md`; a desktop unit build cannot honestly replace those checks.
