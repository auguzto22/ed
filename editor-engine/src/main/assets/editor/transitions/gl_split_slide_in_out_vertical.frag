// Split Slide In Out Vertical — adaptado de gl-transitions (splitSlideInOutVertical.glsl)
// Autor: OllyOllyOlly · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const bool reverse = false;
// Author: OllyOllyOlly
// License: MIT
// Description: The halves of both images slide vertically in opposite directions, out and in at once
// Tags: slide, split, vertical

// @param Swap the directions of the halves

const vec2 boundMin = vec2(0.0, 0.0);
const vec2 boundMax = vec2(1.0, 1.0);

bool inBounds (vec2 p) {
  return all(lessThan(boundMin, p)) && all(lessThan(p, boundMax));
}

vec4 transition (vec2 uv) {
  float modifier = (uv.x > 0.5 ? 1.0 : -1.0) * (reverse ? -1.0 : 1.0) ;
  vec2 fromP = vec2(uv.x, uv.y + (progress * modifier));
  vec2 toP = vec2(uv.x, (uv.y + (progress * modifier)) - modifier);

  return inBounds(fromP) ? getFromColor(fromP) : getToColor(toP);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
