// Directional-easing — adaptado de gl-transitions (directional-easing.glsl)
// Autor: Max Plotnikov · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec2 direction = vec2(0.0, 1.0);
// Author: Max Plotnikov
// License: MIT
// Description: Like Directional, with an eased movement
// Tags: slide, push, directional

// @param Direction of the movement

vec4 transition (vec2 uv) {
  float easing = sqrt((2.0 - progress) * progress);
  vec2 p = uv + easing * sign(direction);
  vec2 f = fract(p);
  return mix(
    getToColor(f),
    getFromColor(f),
    step(0.0, p.y) * step(p.y, 1.0) * step(0.0, p.x) * step(p.x, 1.0)
  );
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
