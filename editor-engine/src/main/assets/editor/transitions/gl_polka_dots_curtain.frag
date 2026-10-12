// Polka Dots Curtain — adaptado de gl-transitions (PolkaDotsCurtain.glsl)
// Autor: bobylito · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec2 center = vec2(0.0, 0.0);
// Author: bobylito
// License: MIT
// Description: The incoming image appears through growing polka p_dots
// Tags: p_dots, pattern
const float SQRT_2 = 1.414213562373;
// @param Number of p_dots across the image


vec4 transition(vec2 uv) {
  bool nextImage = distance(fract(uv * p_dots), vec2(0.5, 0.5)) < ( progress / distance(uv, center));
  return nextImage ? getToColor(uv) : getFromColor(uv);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
