# Recly effect packages, engine 1

A `.reclyfx` file is a ZIP with `manifest.json`, `shader.frag`, and optional
`preview.webp` at its root. No subdirectories, textures, binaries or scripts are
accepted by this first engine version. The current library displays names and
categories, not preview images. The package preview is reserved for a future grid.

Example manifest for an original effect authored by the catalog owner:

```json
{
  "id": "soft_tint",
  "version": 1,
  "engineVersion": 1,
  "type": "EFFECT",
  "name": "Soft tint",
  "category": "Color",
  "shader": "shader.frag",
  "license": "SPDX: MIT",
  "premium": false,
  "parameters": [
    {"id": "warmth", "name": "Warmth", "type": "float", "min": 0, "max": 1, "default": 0.2}
  ]
}
```

```glsl
vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    return vec4(source.rgb * vec3(1.0, 1.0 - p_warmth * .05, 1.0 - p_warmth * .15), source.a);
}
```

The host provides `uTexSampler`, `uResolution`, clip-local seconds `uTime`,
`uProgress` in [0,1], and `p_<parameterId>` floats. Do not declare these uniforms
or `main()`. GLSL ES 1.00 is used; the host owns precision, varying and entry point.
No preprocessor directives, loops, discard, custom uniforms, attributes or varyings
are accepted. Each manifest has at most 16 finite bounded float parameters.

The host mixes original and effect with intensity in [0,1], guaranteeing zero
intensity bypass. Preserve straight alpha in the returned color. Colors arrive
through Media3's SDR GPU pipeline; this format does not promise HDR preservation.
Compilation happens in the render context; syntactically invalid shaders are
reported as preview/export errors, not silently skipped.

Instances store immutable asset ID/version plus intensity, parameters and enabled
state in the project. Stack order is render order; reordering is undoable. Missing
enabled assets stop export with an installation message rather than changing the
result. Installed versions cannot overwrite one another; publish a new version.

Built-in definitions live in `app/src/main/assets/editor/effects.json`, with GLSL
bodies in `editor/effects/<id>.frag`. Adding a supported package does not require
editing EditorActivity. IDs prefixed `recly_` are reserved for built-in content.
Only use licenses you are entitled to grant for the shader and any package artwork.

## Built-in effect presets

`editor/effect_presets.json` holds the 300 presets shown in the effect preset gallery. The first 20 are
hand-written and must keep their ids and order (the atlas preview mapping depends on them); the rest are
produced by `python3 tools/library/gen_effect_presets.py`, which also validates every preset: known effects,
parameters inside their ranges, at most 5 nodes (the clip stack takes 12), at most 3 MEDIUM and 2 temporal
effects, and that every effect in `effects.json` appears in at least one preset. Re-run it after adding effects.
Presets in the "Gaming" category are applied as ~350 ms impacts, so keep sustained looks in other categories.
