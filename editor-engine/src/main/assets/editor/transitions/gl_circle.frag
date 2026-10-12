// Circle — adaptado de gl-transitions (circle.glsl)
// Autor: Fernando Kuteken · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec2 center = vec2(0.5, 0.5);
const vec3 backColor = vec3(0.1, 0.1, 0.1);
// Author: Fernando Kuteken
// License: MIT
// Description: The outgoing image closes into a circle over a background color, then the incoming one opens from it
// Tags: circle, iris, shape

// @param Center of the circle

// @color Background color

vec4 transition (vec2 uv) {
  
  float distance = length(uv - center);
  float radius = sqrt(8.0) * abs(progress - 0.5);
  
  if (distance > radius) {
    return vec4(backColor, 1.0);
  }
  else {
    if (progress < 0.5) return getFromColor(uv);
    else return getToColor(uv);
  }
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
