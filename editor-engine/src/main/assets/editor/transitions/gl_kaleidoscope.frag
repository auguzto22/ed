// Kaleidoscope — adaptado de gl-transitions (kaleidoscope.glsl)
// Autor: nwoeanhinnogaehr · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: nwoeanhinnogaehr
// License: MIT
// Description: A crossfade through a spinning kaleidoscope
// Tags: kaleidoscope, distort, blend



vec4 transition(vec2 uv) {
  vec2 p = uv.xy / vec2(1.0).xy;
  vec2 q = p;
  float t = pow(progress, p_power)*p_speed;
  p = p -0.5;
  for (int i = 0; i < 7; i++) {
    p = vec2(sin(t)*p.x + cos(t)*p.y, sin(t)*p.y - cos(t)*p.x);
    t += p_angle;
    p = abs(mod(p, 2.0) - 1.0);
  }
  abs(mod(p, 1.0));
  return mix(
    mix(getFromColor(q), getToColor(q), progress),
    mix(getFromColor(p), getToColor(p), progress), 1.0 - 2.0*abs(progress - 0.5));
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
