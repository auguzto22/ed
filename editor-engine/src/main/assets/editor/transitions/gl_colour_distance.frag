// Colour Distance — adaptado de gl-transitions (ColourDistance.glsl)
// Autor: P-Seebauer · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// License: MIT
// Author: P-Seebauer
// Description: Pixels switch to the incoming image in order of how close their colors are
// Tags: blend, color
// ported by gre from https://gist.github.com/P-Seebauer/2a5fa2f77c883dd661f9

vec4 transition(vec2 p) {
  vec4 fTex = getFromColor(p);
  vec4 tTex = getToColor(p);
  float m = step(distance(fTex, tTex), progress);
  return mix(
    mix(fTex, tTex, m),
    tTex,
    pow(progress, p_power)
  );
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
