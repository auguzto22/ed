// Windowslice — adaptado de gl-transitions (windowslice.glsl)
// Autor: gre · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: gre
// License: MIT
// Description: The incoming image appears through vertical slices that widen
// Tags: slices, stripes, wipe

// @param Number of slices

// @param Softness of the slices' edges

vec4 transition (vec2 p) {
  float pr = smoothstep(-p_smoothness, 0.0, p.x - progress * (1.0 + p_smoothness));
  float s = step(pr, fract(p_count * p.x));
  return mix(getFromColor(p), getToColor(p), s);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
