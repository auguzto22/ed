// Revolve Left — adaptado de gl-transitions (Revolve_Left.glsl)
// Autor: bread · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec2 center = vec2(0.46, 0.52);
// Author: bread
// License: MIT
// Description: The outgoing image swirls and zooms with motion blur into the incoming one
// Tags: p_swirl, zoom, rotate, blur
// gl-transitions v1 compatible

// @param Center of the p_swirl










float sat(float x) {
  return clamp(x, 0.0, 1.0);
}

float ease(float x) {
  x = sat(x);
  return x * x * (3.0 - 2.0 * x);
}

float revolveEnvelope(float t) {
  float rise = ease((t - 0.10) / 0.33);
  float fall = 1.0 - ease((t - 0.43) / 0.29);
  return rise * fall;
}

vec2 rotate2(vec2 p, float a) {
  float s = sin(a);
  float c = cos(a);
  return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

vec2 warpUv(vec2 uv, float t) {
  float e = revolveEnvelope(t);

  vec2 p = uv - center;
  p.x *= ratio;

  float r = length(p);
  float edgeSpin = p_max_rotation * e;
  float coreSpin = p_swirl * e * pow(1.0 - sat(r / 0.96), 1.55);
  float visibleAngle = p_direction * (edgeSpin + coreSpin);

  p = rotate2(p, -visibleAngle);

  float sc = 1.0 + (p_peak_zoom - 1.0) * pow(e, 0.85);
  p /= sc;

  float rr = length(p);
  p *= 1.0 + p_barrel * e * rr * rr * 2.8;

  p.x /= ratio;
  return clamp(p + center, vec2(0.001), vec2(0.999));
}

vec4 sampleRevolve(vec2 uv, float t) {
  vec2 p = warpUv(uv, t);
  float reveal = smoothstep(p_switch_start, p_switch_end, t);
  return mix(getFromColor(p), getToColor(p), reveal);
}

vec4 transition(vec2 uv) {
  if (progress <= 0.0) return getFromColor(uv);
  if (progress >= 1.0) return getToColor(uv);

  float e = revolveEnvelope(progress);
  float span = 0.060 * p_motion_blur * e;

  vec4 color = vec4(0.0);
  float total = 0.0;

  for (int i = -8; i <= 8; i++) {
    float x = float(i) / 8.0;
    float w = 1.0 - abs(x);
    w = w * w + 0.01;

    float t = sat(progress + x * span);
    color += sampleRevolve(uv, t) * w;
    total += w;
  }

  color /= total;

  vec2 q = uv - vec2(0.5);
  q.x *= ratio;
  float vignette = 1.0 - p_shadow * e * smoothstep(0.35, 0.95, length(q));
  color.rgb *= vignette;

  return color;
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
