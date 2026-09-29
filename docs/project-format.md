# Project format

The .r15 binary header is 0x52313545 followed by schema 10. Schemas 1-9 are still
readable with their original field order and limits. Their migration adds empty
video tracks, default track states and unknown (zero) timestamps. On edit, the
session records createdAt/modifiedAt in epoch milliseconds. Media time is microseconds.

Schema 7 appends timestamps, a bounded track-state map, and up to seven overlay
video tracks to the schema 6 payload. Each track contains a stable ID, ordered
start times and one length-delimited video-only project envelope. Envelopes reuse
the existing clip serializer, including filters, LUTs and keyframes. Nesting is
limited to one level, payload to 4 MiB and clips to 200 per track. Overlaps within
a track, duplicate IDs and invalid durations are rejected. Original media is
referenced by URI, never embedded or modified.

Schema 8 appends a bounded effect stack for each main video: instance ID, asset ID,
pinned asset version, enabled flag, intensity and up to 16 float parameters.
Each video has at most 12 effects. Layer envelopes reuse schema 8, so layer effects
also round-trip. Reading schemas 1-7 adds empty effect stacks. Unknown future
schemas are rejected; old APKs cannot open newly saved schema 8 files. Before the
first overwrite of a legacy file, ProjectStore preserves its bytes in
`<id>.r15.pre-v3.bak`, using fsync and atomic rename. Subsequent saves never replace
that backup. It is project data, not cache; explicit project deletion removes it.

Schema 9 appends source-anchored speed-curve points, preserve-pitch selection,
Bezier handles for each transform keyframe, advanced mask/chroma controls, eight
HSL bands and independent RGB curves. Main videos and nested overlay envelopes use
the same extension. Schema 8 projects receive constant speed, default Bezier and
neutral compositing values. Curve points are bounded to 32 and stay in original
source coordinates, so trim and split do not rewrite the ramp.

Schema 10 appends up to 200 source-anchored value keyframes across each effect
instance. Intensity and every manifest float parameter can use the same easing and
cubic Bezier contract as transform keyframes. Schema 9 projects receive empty
effect-animation maps. Values are resolved per frame in the shared GPU effect used
by preview and export; static parameters remain the fallback.

Main video remains `Project.videos`. Added video tracks have explicit starts;
list order is bottom to top. Duration is the maximum end over the main sequence
and overlays. Auxiliary text/image/audio timing remains absolute and does not
silently move when the main sequence is trimmed. Group/ripple editing is separate.

Track state IDs: `main`, `texts`, `stickers`, `audio:<clipId>` and video-track IDs.
Visibility controls visual output; mute controls audio; solo operates within each
media group. Lock blocks content changes, not unlocking. ProjectHistory retains
complete immutable metadata snapshots. Atomic fsync/rename saves remain in use.
