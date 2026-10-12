// Rectangle Crop — adaptado de gl-transitions (RectangleCrop.glsl)
// Autor: martiniti · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec4 bgcolor = vec4(0.0, 0.0, 0.0, 1.0);
// License: MIT
// Author: martiniti
// Description: The outgoing image shrinks into a small rectangle over a background color, then the incoming one grows from it
// Tags: box, iris, zoom

// @color Background color

vec4 transition(vec2 uv) {
  
  float s = pow(2.0 * abs(progress - 0.5), 3.0);
              
  vec2 q = uv.xy / vec2(1.0).xy;
  
  // bottom-left
  vec2 bl = step(vec2(1.0 - 2.0*abs(progress - 0.5)), q + 0.25);
  
  // top-right
  vec2 tr = step(vec2(1.0 - 2.0*abs(progress - 0.5)), 1.25 - q);
  
  float dist = length(1.0 - bl.x * bl.y * tr.x * tr.y);
  
  return mix(
    progress < 0.5 ? getFromColor(uv) : getToColor(uv),
    bgcolor,
    step(s, dist)
  );
  
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
