// Simple Zoom — adaptado de gl-transitions (SimpleZoom.glsl)
// Autor: 0gust1 · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: 0gust1
// License: MIT
// Description: Zooms into the outgoing image, then fades to the incoming one
// Tags: zoom, fade

// @range(0.2, 1) Part of the transition spent zooming

vec2 zoom(vec2 uv, float amount) {
  return 0.5 + ((uv - 0.5) * (1.0-amount));	
}

vec4 transition (vec2 uv) {
  float nQuick = clamp(p_zoom_quickness,0.2,1.0);
  return mix(
    getFromColor(zoom(uv, smoothstep(0.0, nQuick, progress))),
    getToColor(uv),
   smoothstep(nQuick-0.2, 1.0, progress)
  );
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
