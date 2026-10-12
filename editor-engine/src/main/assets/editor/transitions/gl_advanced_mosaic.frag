// Advanced Mosaic — adaptado de gl-transitions (AdvancedMosaic.glsl)
// Autor: Sergey Kosarevsky · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: Sergey Kosarevsky
// License: MIT
// Description: Both images break into large blocks that blend, then sharpen into the incoming image
// Tags: pixelate, mosaic, blend
// Ported from https://gist.github.com/corporateshark/21d2fdd24c706952dc8c

vec4 transition(vec2 uv) {
  float T = progress;
  float half_ = 0.5;
  float size = (T < half_) ? mix(1.0, p_pixel_size, T / half_) : mix(p_pixel_size, 1.0, (T - half_) / half_);
  float D = size * 0.005;
  // Remap UV to center the mosaic pattern
  vec2 UV = (uv - 0.5) / D;
  vec2 coord = clamp(D * (ceil(UV - 0.5)) + 0.5, 0.0, 1.0);
  vec4 C0 = getFromColor(coord);
  vec4 C1 = getToColor(coord);
  return mix(C0, C1, T);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
