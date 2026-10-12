// Polar Function — adaptado de gl-transitions (polar_function.glsl)
// Autor: Fernando Kuteken · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const int segments = 5;
// Author: Fernando Kuteken
// License: MIT
// Description: A flower shape with several petals grows to reveal the incoming image
// Tags: shape, wipe, polar

#define PI 3.14159265359

// @param Number of petals

vec4 transition (vec2 uv) {
  
  float angle = atan(uv.y - 0.5, uv.x - 0.5) - 0.5 * PI;
  float normalized = (angle + 1.5 * PI) * (2.0 * PI);
  
  float radius = (cos(float(segments) * angle) + 4.0) / 4.0;
  float difference = length(uv - vec2(0.5, 0.5));
  
  if (difference > radius * progress)
    return getFromColor(uv);
  else
    return getToColor(uv);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
