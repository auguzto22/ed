// Dreamy — adaptado de gl-transitions (Dreamy.glsl)
// Autor: mikolalysenko · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: mikolalysenko
// License: MIT
// Description: A crossfade with a gentle wavy distortion
// Tags: wave, blend

vec2 offset(float progress, float x, float theta) {
  float phase = progress*progress + progress + theta;
  float shifty = 0.03*progress*cos(10.0*(progress+x));
  return vec2(0, shifty);
}
vec4 transition(vec2 p) {
  return mix(getFromColor(p + offset(progress, p.x, 0.0)), getToColor(p + offset(1.0-progress, p.x, 3.14)), progress);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
