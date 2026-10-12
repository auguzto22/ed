// Fadecolor — adaptado de gl-transitions (fadecolor.glsl)
// Autor: gre · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec3 color = vec3(0.0);
// Author: gre
// License: MIT
// Description: A fade through a solid color, black by default
// Tags: fade, color
// @color Color to fade through

// @range(0, 1) How long the color shows in the middle

vec4 transition (vec2 uv) {
  return mix(
    mix(vec4(color, 1.0), getFromColor(uv), smoothstep(1.0-p_color_phase, 0.0, progress)),
    mix(vec4(color, 1.0), getToColor(uv), smoothstep(    p_color_phase, 1.0, progress)),
    progress);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
