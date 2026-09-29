# Animation package contract

Video keyframes are stored in original source microseconds and support zoom, x, y,
rotation, opacity and easing. The choices are LINEAR, SMOOTH, EASE_IN, EASE_OUT,
HOLD, EASE_IN_OUT, BEZIER, SPRING, BOUNCE, ELASTIC and BACK. A cubic Bezier stores
x1/y1/x2/y2 and solves x before calculating y. The shared transform evaluator is
used by preview and export. Trim and split preserve source coordinates.

Video animation presets are JSON files under `assets/editor/animations`; imported
files live in the private `editor-animations` directory. Each package is bounded to
64 KiB and 32 points, has engineVersion 1, an explicit license, category IN, OUT or
LOOP, and contains no executable code. Applying a preset compiles it to ordinary
editable TransformKeyframes. The project therefore remains renderable if the asset
is later removed. Twenty-three original presets ship offline.

The UI provides a graph for every easing, draggable Bezier handles, numeric Bezier
fields and previous/next keyframe navigation. Speed ramps have an editable graph,
presets, preserve-pitch and a bounded piecewise Media3 SpeedProvider shared with
timeline/source conversion. Effect-parameter keyframes and generic property tracks
beyond video transform are still not implemented.
