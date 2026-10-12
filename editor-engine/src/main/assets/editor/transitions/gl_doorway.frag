// Doorway — adaptado de gl-transitions (doorway.glsl)
// Autor: gre · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: gre
// License: MIT
// Description: The outgoing image opens like double doors as the incoming one comes forward, with a floor p_reflection
// Tags: doors, 3d, p_reflection
// @param Strength of the p_reflection

// @param Amount of p_perspective


const vec4 black = vec4(0.0, 0.0, 0.0, 1.0);
const vec2 boundMin = vec2(0.0, 0.0);
const vec2 boundMax = vec2(1.0, 1.0);

bool inBounds (vec2 p) {
  return all(lessThan(boundMin, p)) && all(lessThan(p, boundMax));
}

vec2 project (vec2 p) {
  return p * vec2(1.0, -1.2) + vec2(0.0, -0.02);
}

vec4 bgColor (vec2 p, vec2 pto) {
  vec4 c = black;
  pto = project(pto);
  if (inBounds(pto)) {
    c += mix(black, getToColor(pto), p_reflection * mix(1.0, 0.0, pto.y));
  }
  return c;
}


vec4 transition (vec2 p) {
  vec2 pfr = vec2(-1.), pto = vec2(-1.);
  float middleSlit = 2.0 * abs(p.x-0.5) - progress;
  if (middleSlit > 0.0) {
    pfr = p + (p.x > 0.5 ? -1.0 : 1.0) * vec2(0.5*progress, 0.0);
    float d = 1.0/(1.0+p_perspective*progress*(1.0-middleSlit));
    pfr.y -= d/2.;
    pfr.y *= d;
    pfr.y += d/2.;
  }
  float size = mix(1.0, p_depth, 1.-progress);
  pto = (p + vec2(-0.5, -0.5)) * vec2(size, size) + vec2(0.5, 0.5);
  if (inBounds(pfr)) {
    return getFromColor(pfr);
  }
  else if (inBounds(pto)) {
    return getToColor(pto);
  }
  else {
    return bgColor(p, pto);
  }
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
