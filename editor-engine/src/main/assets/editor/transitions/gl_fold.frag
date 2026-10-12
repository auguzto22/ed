// Fold — adaptado de gl-transitions (Fold.glsl)
// Autor: nwoeanhinnogaehr · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: nwoeanhinnogaehr
// License: MIT
// Description: The incoming image unfolds from the left while the outgoing one folds away to the right
// Tags: fold, slide, horizontal
// Ported from https://gist.github.com/nwoeanhinnogaehr/f6fc39f4cfcbb97f96a6

vec4 transition(vec2 uv) {
  // Only sample the visible side: the other one divides by zero at progress 0 and 1.
  if (progress == 0.0 || uv.x > progress) return getFromColor((uv - vec2(progress, 0.0)) / vec2(1.0 - progress, 1.0));
  return getToColor(uv / vec2(progress, 1.0));
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
