// Directionalwipe — adaptado de gl-transitions (directionalwipe.glsl)
// Autor: gre · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec2 direction = vec2(1.0, -1.0);
// Author: gre
// License: MIT
// Description: A soft-edged diagonal wipe
// Tags: wipe, directional

// @param Direction of the wipe

// @param Softness of the edge

const vec2 center = vec2(0.5, 0.5);
 
vec4 transition (vec2 uv) {
  vec2 v = normalize(direction);
  v /= abs(v.x)+abs(v.y);
  float d = v.x * center.x + v.y * center.y;
  float m =
    (1.0-step(progress, 0.0)) * // there is something wrong with our formula that makes m not equals 0.0 with progress is 0.0
    (1.0 - smoothstep(-p_smoothness, 0.0, v.x * uv.x + v.y * uv.y - (d-0.5+progress*(1.+p_smoothness))));
  return mix(getFromColor(uv), getToColor(uv), m);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
