// Randomsquares — adaptado de gl-transitions (randomsquares.glsl)
// Autor: gre · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const ivec2 size = ivec2(10, 10);
// Author: gre
// License: MIT
// Description: The incoming image appears in random squares
// Tags: squares, grid, dissolve

// @param Number of squares (columns, rows)

// @param Softness of each square's switch

float rand (vec2 co) {
  return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
}

vec4 transition(vec2 p) {
  float r = rand(floor(vec2(size) * p));
  float m = smoothstep(0.0, -p_smoothness, r - (progress * (1.0 + p_smoothness)));
  return mix(getFromColor(p), getToColor(p), m);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
