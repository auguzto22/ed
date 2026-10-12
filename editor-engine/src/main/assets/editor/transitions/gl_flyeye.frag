// Flyeye — adaptado de gl-transitions (flyeye.glsl)
// Autor: gre · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: gre
// License: MIT
// Description: The image breaks into a fly's-eye grid of lenses with color separation
// Tags: lens, distort, chromatic



vec4 transition(vec2 p) {
  float inv = 1. - progress;
  vec2 disp = p_size*vec2(cos(p_zoom*p.x), sin(p_zoom*p.y));
  vec4 texTo = getToColor(p + inv*disp);
  vec4 texFrom = vec4(
    getFromColor(p + progress*disp*(1.0 - p_color_separation)).r,
    getFromColor(p + progress*disp).g,
    getFromColor(p + progress*disp*(1.0 + p_color_separation)).b,
    1.0);
  return texTo*progress + texFrom*inv;
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
