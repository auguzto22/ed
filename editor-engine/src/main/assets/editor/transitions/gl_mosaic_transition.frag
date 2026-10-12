// Mosaic Transition — adaptado de gl-transitions (mosaic_transition.glsl)
// Autor: YueDev · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: YueDev
// License: MIT
// Description: Both images break into mosaic blocks that blend, then sharpen into the incoming image
// Tags: mosaic, pixelate, blend

// @param Number of mosaic blocks

vec2 getMosaicUV(vec2 uv) {
  float mosaicWidth = 2.0 / p_mosaic_num * min(progress, 1.0 - progress);
  float mX = floor(uv.x / mosaicWidth) + 0.5;
  float mY = floor(uv.y / mosaicWidth) + 0.5;
  return vec2(mX * mosaicWidth, mY * mosaicWidth);
}

vec4 transition (vec2 uv) {
  vec2 mosaicUV = min(progress, 1.0 - progress) == 0.0 ? uv : getMosaicUV(uv);
  return mix(getFromColor(mosaicUV), getToColor(mosaicUV), progress * progress);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
