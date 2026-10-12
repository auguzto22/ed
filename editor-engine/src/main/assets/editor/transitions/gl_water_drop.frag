// Water Drop — adaptado de gl-transitions (WaterDrop.glsl)
// Autor: Paweł Płóciennik · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: Paweł Płóciennik
// License: MIT
// Description: Ripples spread from the center like a drop falling into water, crossfading to the incoming image
// Tags: ripple, water, blend
// @param Frequency of the ripples

// @param Speed of the ripples

vec4 transition(vec2 p) {
  vec2 dir = p - vec2(.5);
  float dist = length(dir);

  if (dist > progress) {
    return mix(getFromColor( p), getToColor( p), progress);
  } else {
    vec2 offset = dir * sin(dist * p_amplitude - progress * p_speed);
    return mix(getFromColor( p + offset), getToColor( p), progress);
  }
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
