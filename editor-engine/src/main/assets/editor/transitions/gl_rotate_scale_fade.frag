// Rotate Scale Fade — adaptado de gl-transitions (rotate_scale_fade.glsl)
// Autor: Fernando Kuteken · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec2 center = vec2(0.5, 0.5);
const vec4 backColor = vec4(0.15, 0.15, 0.15, 1.0);
// Author: Fernando Kuteken
// License: MIT
// Description: The outgoing image spins and zooms while fading to the incoming one, over a background color
// Tags: rotate, zoom, fade

#define PI 3.14159265359

// @param Center of the rotation

// @param Number of turns

// @param Maximum zoom

// @color Background color

vec4 transition (vec2 uv) {
  
  vec2 difference = uv - center;
  vec2 dir = normalize(difference);
  float dist = length(difference);
  
  float angle = 2.0 * PI * p_rotations * progress;
  
  float c = cos(angle);
  float s = sin(angle);
  
  float currentScale = mix(p_scale, 1.0, 2.0 * abs(progress - 0.5));
  
  vec2 rotatedDir = vec2(dir.x  * c - dir.y * s, dir.x * s + dir.y * c);
  vec2 rotatedUv = center + rotatedDir * dist / currentScale;
  
  if (rotatedUv.x < 0.0 || rotatedUv.x > 1.0 ||
      rotatedUv.y < 0.0 || rotatedUv.y > 1.0)
    return backColor;
    
  return mix(getFromColor(rotatedUv), getToColor(rotatedUv), progress);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
