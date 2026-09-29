# Current transitions and extension boundary

The working transition format remains the legacy VideoClip fields:
`transitionIn`, `transitionOut`, and `transitionDurationUs`. Values are NONE,
FADE_BLACK and FADE_WHITE. Duration is 100 ms to 2 seconds, clamped during rendering
to half the edited clip duration. It is stored in the project and shares the same
TransitionMatrix for preview/export, now using clip-local timeline time.

These are fades to a solid color, NOT cross-dissolves or overlapping A/B transitions.
No downloadable transition package is accepted by engine 1. AssetType.TRANSITION
is a reserved type, not an enabled feature. Existing choices remain functional.

Future A/B composition must explicitly allocate overlap, map both source clocks,
mix both audio clips, validate available source handles, and preserve duration
through save/undo/export. It needs pixel and A/V-sync integration tests before new
transition names appear in the UI. Do not route a two-source transition through
the single-source effect package API and label it a dissolve.
