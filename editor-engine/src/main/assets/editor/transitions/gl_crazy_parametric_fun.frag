// Crazy Parametric Fun — adaptado de gl-transitions (CrazyParametricFun.glsl)
// Autor: mandubian · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: mandubian
// License: MIT
// Description: The outgoing image is pulled along p_a parametric curve into p_a psychedelic swirl
// Tags: distort, psychedelic




vec4 transition(vec2 uv) {
  vec2 p = uv.xy / vec2(1.0).xy;
  vec2 dir = p - vec2(.5);
  float dist = length(dir);
  float x = (p_a - p_b) * cos(progress) + p_b * cos(progress * ((p_a / p_b) - 1.) );
  float y = (p_a - p_b) * sin(progress) - p_b * sin(progress * ((p_a / p_b) - 1.));
  vec2 offset = dir * vec2(sin(progress  * dist * p_amplitude * x), sin(progress * dist * p_amplitude * y)) / p_smoothness;
  return mix(getFromColor(p + offset), getToColor(p), smoothstep(0.2, 1.0, progress));
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
