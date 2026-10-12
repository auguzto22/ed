// Split Slide In Vertical — adaptado de gl-transitions (splitSlideInVertical.glsl)
// Autor: OllyOllyOlly · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const bool reverse = false;
// Author: OllyOllyOlly
// License: MIT
// Description: The incoming image slides in as two halves moving vertically in opposite directions
// Tags: slide, split, vertical

// @param Swap the directions of the halves

const vec2 boundMin = vec2(0.0, 0.0);
const vec2 boundMax = vec2(1.0, 1.0);

bool inBounds (vec2 p) {
  return all(lessThan(boundMin, p)) && all(lessThan(p, boundMax));
}

vec4 transition (vec2 uv) {
  float modifier = reverse ? -1.0 : 1.0;
  vec2 p = (uv.x > 0.5) ?
    vec2(uv.x, (uv.y - (progress * modifier)) + modifier) :
    vec2(uv.x, (uv.y + (progress * modifier)) - modifier);

  return inBounds(p) ? getToColor(p) : getFromColor(uv);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
