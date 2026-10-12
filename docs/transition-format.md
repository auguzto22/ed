# Native transitions

The engine follows Concat's document → pure frame plan → GPU execution boundary.
Recly keeps Android document and Media3 codec adapters; transition planning, package
validation, easing and pixel composition run in Rust.

`TransitionInstance` remains the persisted edit: adjacent clip IDs, package ID,
duration, alignment, easing, Bézier points and parameters. Existing binary projects
retain their schema and IDs. The 99 definitions and 16 GLSL bodies are embedded in
the native library. Android catalogue screens read the validated native catalogue;
favorites and recents remain UI preferences.

`editor-preview-native/src/transitions.rs` resolves integer microsecond windows.
Neighboring windows shrink to fit short clips without moving cuts or changing
project duration. `domain/TransitionPlan.kt` translates Android document values.
Preview video, audio, export and timeline editing consume the same windows. Easing
and equal-power transition gains also run in Rust. Source handles use media outside
trims where available; reverse clips and speed ramps hold boundary frames.

Saved `transitionIn`/`transitionOut` edge fades remain compatibility fields. They
use native shading in both preview and export, including fade-to-white.

`editor-preview-native/src/android/transition_gpu.rs` owns the shared A/B pass.
Each input is treated and positioned first. The pass clamps parameters, locks
internal variant selectors to the package and preserves exact endpoints. Programs
and framebuffers are cached on their owning GL thread.

Preview supplies prepared textures. Export calls the same pass through
`transitions/NativeTransitionExport.kt`, which adapts Media3 timestamps and lifecycle.
The native export queue holds at most eight outgoing frames per live transition,
reducing its capacity to fit 64 MiB at larger resolutions (with a minimum of one frame), and
rejects samples more than 100 ms from the requested time. A missing input uses the
original cut for that frame. The compositor checks per-frame results, so one success
cannot incorrectly mark later missing frames as composited.
When outgoing media ends before the window, its requested sample time stays at the
media boundary while transition progress continues, preserving the final held frame.

Native export calls restore the host framebuffer, viewport, vertex-buffer binding,
and blend, scissor and depth enable states.
The Kotlin OES adapter unbinds the native vertex buffer before using CPU arrays.
Export completion/cancellation clears session bookkeeping; GL resources are released
on their GL thread or with the Media3 context that owns them.

The old `TransitionShaderBuilder`, `TransitionBridge`, `TransitionEffect`,
`TransitionMatrix` and Kotlin window-planning algorithm are removed. GLSL artwork,
catalogue metadata, UI controls and stored IDs are retained as data/adapters.

`cargo test --offline` checks native planning, packages and parameters. Gradle host
tests load this same native library. `NativeTransitionGpuInstrumentedTest` checks
all packages on an Android GLES driver at five progress values, exact endpoints and
preview/export pixel parity with synthetic textures. `NativeTransitionExportInstrumentedTest`
covers a Media3 export of two images with a dissolve, reading the encoded frames back.
These device checks require a connected Android device.
