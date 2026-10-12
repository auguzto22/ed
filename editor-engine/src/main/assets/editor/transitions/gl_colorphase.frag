// Colorphase — adaptado de gl-transitions (colorphase.glsl)
// Autor: gre · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec4 fromStep = vec4(0.0, 0.2, 0.4, 0.0);
const vec4 toStep = vec4(0.6, 0.8, 1.0, 1.0);
// Author: gre
// License: MIT
// Description: A crossfade in which each color channel changes at a different time
// Tags: fade, color

// Usage: fromStep and toStep must be in [0.0, 1.0] range 
// and all(fromStep) must be < all(toStep)

// @range(0, 1) When each channel (r, g, b, a) starts changing

// @range(0, 1) When each channel (r, g, b, a) finishes changing

vec4 transition (vec2 uv) {
  vec4 a = getFromColor(uv);
  vec4 b = getToColor(uv);
  return mix(a, b, smoothstep(fromStep, toStep, vec4(progress)));
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
