// Overexposure — adaptado de gl-transitions (Overexposure.glsl)
// Autor: Ben Zhang · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: Ben Zhang
// License: MIT
// Description: A crossfade through a washed-out, overexposed white
// Tags: flash, light, fade

// @param Amount of overexposure

const float PI = 3.141592653589793;

vec4 transition (vec2 uv) {
  vec4 from = getFromColor(uv);
  vec4 to = getToColor(uv);

  // Multipliers
  float from_m = 1.0 - progress + sin(PI * progress) * p_strength;
  float to_m = progress + sin(PI * progress) * p_strength;
  
  return vec4(
    from.r * from.a * from_m + to.r * to.a * to_m,
    from.g * from.a * from_m + to.g * to.a * to_m,
    from.b * from.a * from_m + to.b * to.a * to_m,
    mix(from.a, to.a, progress)
  );
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
