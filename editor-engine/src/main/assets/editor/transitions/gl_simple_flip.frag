// Simple Flip — adaptado de gl-transitions (SimpleFlip.glsl)
// Autor: nwoeanhinnogaehr · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: nwoeanhinnogaehr
// License: MIT
// Description: The outgoing image flips around the vertical axis into the incoming one
// Tags: flip, horizontal
// Ported from https://gist.github.com/nwoeanhinnogaehr/408045772d255df97520

vec4 transition(vec2 uv) {
  vec2 q = uv;
  uv.x = (uv.x - 0.5) / abs(progress - 0.5) * 0.5 + 0.5;
  vec4 a = getFromColor(uv);
  vec4 b = getToColor(uv);
  return vec4(mix(a, b, step(0.5, progress)).rgb * step(abs(q.x - 0.5), abs(progress - 0.5)), 1.0);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
