// Rectangle — adaptado de gl-transitions (Rectangle.glsl)
// Autor: martiniti · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec4 bgcolor = vec4(0.0, 0.0, 0.0, 1.0);
// Author: martiniti
// License: MIT
// Description: A rectangle grows from the center to cover the outgoing image, then shrinks to reveal the incoming one
// Tags: box, shape, iris

// @color Color of the rectangle

vec4 transition(vec2 p) {
  float s = pow(2.0 * abs(progress - 0.5), 3.0);
  
   vec2 sq = p.xy / vec2(1.0).xy;
   
    // bottom-left
    vec2 bl = step(vec2(abs(1. - 2.*progress)), sq + .25);
    float dist = bl.x * bl.y;

    // top-right
    vec2 tr = step(vec2(abs(1. - 2.*progress)), 1.25-sq);
    dist *= 1. * tr.x * tr.y;
  
  return mix(
    progress < 0.5 ? getFromColor(p) : getToColor(p),
    bgcolor,
    step(s, dist)
  );
  
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
