// Wind — adaptado de gl-transitions (wind.glsl)
// Autor: gre · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const bool reversed = false;
// Author: gre
// License: MIT
// Description: A ragged left-to-right wipe, as if the image was blown away by the wind
// Tags: wipe, horizontal, noise

// @range(0, 1) Width of the ragged edge

// @param Wipe from right to left instead

float rand (vec2 co) {
  return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
}

vec4 transition (vec2 uv) {
  float x = reversed ? 1. - uv.x : uv.x;
  float r = rand(vec2(0, uv.y));
  float m = smoothstep(0.0, -p_size, x*(1.0-p_size) + p_size*r - (progress * (1.0 + p_size)));
  return mix(
    getFromColor(uv),
    getToColor(uv),
    m
  );
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
