// Parametric Glitch — adaptado de gl-transitions (parametric_glitch.glsl)
// Autor: Yoni Maltsman @friendlyspinach · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: Yoni Maltsman @friendlyspinach
// License: MIT
// Description: A crossfade with glitchy horizontal bands and color shifts
// Tags: glitch, blend



// @param Horizontal amplitude of the glitch

// @param Vertical amplitude of the glitch

vec4 transition (vec2 uv) {
  vec4 from = getFromColor(uv);
  vec4 to = getToColor(uv);
  float r = from.r;
  float g = from.g;
  float b = from.b;
  float sphere = r*r + g*g + b*b - 1.0; //3 to 1
  float spiralX = cos(sphere - uv.x/(progress + .01));
  float spiralY = sin(sphere - uv.y/(progress+.01));
  vec2 st = uv;
  st.x = fract(p_ampx*st.x*spiralX); //1 to 2
  st.y = fract(p_ampy*st.y*spiralY);
  vec2 diff = uv - st;
  from = getFromColor(uv + progress*diff);
  return mix(from, to, progress);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
