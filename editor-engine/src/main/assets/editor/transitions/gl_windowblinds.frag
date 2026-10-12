// Windowblinds — adaptado de gl-transitions (windowblinds.glsl)
// Autor: Fabien Benetou · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: Fabien Benetou
// License: MIT
// Description: A crossfade through horizontal window blinds
// Tags: blinds, stripes, blend

vec4 transition (vec2 uv) {
  float t = progress;
  
  if (mod(floor(uv.y*100.*progress),2.)==0.)
    t*=2.-.5;
  
  return mix(
    getFromColor(uv),
    getToColor(uv),
    mix(t, progress, smoothstep(0.8, 1.0, progress))
  );
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
