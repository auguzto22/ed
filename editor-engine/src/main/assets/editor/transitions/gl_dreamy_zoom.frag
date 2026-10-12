// Dreamy Zoom — adaptado de gl-transitions (DreamyZoom.glsl)
// Autor: Zeh Fernando · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: Zeh Fernando
// License: MIT
// Description: Zooms and rotates into a bright flash, then out to the incoming image
// Tags: zoom, rotate, flash

// Definitions --------
#define DEG2RAD 0.03926990816987241548078304229099 // 1/180*PI


// Transition parameters --------

// In degrees

// Multiplier

// The code proper --------

vec4 transition(vec2 uv) {
  // Massage parameters
  float phase = progress < 0.5 ? progress * 2.0 : (progress - 0.5) * 2.0;
  float angleOffset = progress < 0.5 ? mix(0.0, p_rotation * DEG2RAD, phase) : mix(-p_rotation * DEG2RAD, 0.0, phase);
  float newScale = progress < 0.5 ? mix(1.0, p_scale, phase) : mix(p_scale, 1.0, phase);
  
  vec2 center = vec2(0, 0);

  // Calculate the source point
  vec2 assumedCenter = vec2(0.5, 0.5);
  vec2 p = (uv.xy - vec2(0.5, 0.5)) / newScale * vec2(ratio, 1.0);

  // This can probably be optimized (with distance())
  float angle = atan(p.y, p.x) + angleOffset;
  float dist = distance(center, p);
  p.x = cos(angle) * dist / ratio + 0.5;
  p.y = sin(angle) * dist + 0.5;
  vec4 c = progress < 0.5 ? getFromColor(p) : getToColor(p);

  // Finally, apply the color
  return c + (progress < 0.5 ? mix(0.0, 1.0, phase) : mix(1.0, 0.0, phase));
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
