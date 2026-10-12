// Squeeze — adaptado de gl-transitions (squeeze.glsl)
// Autor: gre · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: gre
// License: MIT
// Description: The outgoing image is squeezed into a line with color separation, revealing the incoming one
// Tags: squeeze, chromatic, vertical
 
// @param Amount of color separation

vec4 transition (vec2 uv) {
  float y = 0.5 + (uv.y-0.5) / (1.0-progress);
  if (y < 0.0 || y > 1.0) {
     return getToColor(uv);
  }
  else {
    vec2 fp = vec2(uv.x, y);
    vec2 off = progress * vec2(0.0, p_color_separation);
    vec4 c = getFromColor(fp);
    vec4 cn = getFromColor(fp - off);
    vec4 cp = getFromColor(fp + off);
    return vec4(cn.r, c.g, cp.b, c.a);
  }
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
