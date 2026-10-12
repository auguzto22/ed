// Vertical Close — adaptado de gl-transitions (VerticalClose.glsl)
// Autor: martiniti · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: martiniti
// License: MIT
// Description: The incoming image closes in from the left and right edges toward the middle
// Tags: wipe, split

vec4 transition (vec2 uv) {

  float s = 2.0 - abs((uv.x - 0.5) / (progress - 1.0)) - 2.0 * progress;
  
  return mix(
    getFromColor(uv),
    getToColor(uv),
    smoothstep(0.5, 0.0, s)
  );
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
