// X Axis Translation — adaptado de gl-transitions (x_axis_translation.glsl)
// Autor: lizhongjian · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: lizhongjian
// License: MIT
// Description: The outgoing image slides to the right, uncovering a crossfade to the incoming one
// Tags: slide, horizontal, blend

vec4 transition (vec2 uv) {
  vec2 newUV = uv;
  newUV.x -= progress;
  if(uv.x >= progress)
  {
    return getFromColor(newUV);
  }

  
  return mix(
    getFromColor(uv),
    getToColor(uv),
    progress
  );
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
