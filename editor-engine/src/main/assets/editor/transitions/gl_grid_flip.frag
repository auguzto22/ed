// Grid Flip — adaptado de gl-transitions (GridFlip.glsl)
// Autor: TimDonselaar · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const ivec2 size = ivec2(4);
const vec4 bgcolor = vec4(0.0, 0.0, 0.0, 1.0);
// License: MIT
// Author: TimDonselaar
// Description: The image splits into a grid of tiles that flip one by one to the incoming image
// Tags: grid, tiles, flip
// ported by gre from https://gist.github.com/TimDonselaar/9bcd1c4b5934ba60087bdb55c2ea92e5

// @param Number of tiles (columns, rows)



// @color Color behind the tiles


float rand (vec2 co) {
  return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
}

float getDelta(vec2 p) {
  vec2 rectanglePos = floor(vec2(size) * p);
  vec2 rectangleSize = vec2(1.0 / vec2(size).x, 1.0 / vec2(size).y);
  float top = rectangleSize.y * (rectanglePos.y + 1.0);
  float bottom = rectangleSize.y * rectanglePos.y;
  float left = rectangleSize.x * rectanglePos.x;
  float right = rectangleSize.x * (rectanglePos.x + 1.0);
  float minX = min(abs(p.x - left), abs(p.x - right));
  float minY = min(abs(p.y - top), abs(p.y - bottom));
  return min(minX, minY);
}

float getDividerSize() {
  vec2 rectangleSize = vec2(1.0 / vec2(size).x, 1.0 / vec2(size).y);
  return min(rectangleSize.x, rectangleSize.y) * p_divider_width;
}

vec4 transition(vec2 p) {
  if(progress < p_pause) {
    float currentProg = progress / p_pause;
    float a = 1.0;
    if(getDelta(p) < getDividerSize()) {
      a = 1.0 - currentProg;
    }
    return mix(bgcolor, getFromColor(p), a);
  }
  else if(progress < 1.0 - p_pause){
    if(getDelta(p) < getDividerSize()) {
      return bgcolor;
    } else {
      float currentProg = (progress - p_pause) / (1.0 - p_pause * 2.0);
      vec2 q = p;
      vec2 rectanglePos = floor(vec2(size) * q);
      
      float r = rand(rectanglePos) - p_randomness;
      float cp = smoothstep(0.0, 1.0 - r, currentProg);
    
      float rectangleSize = 1.0 / vec2(size).x;
      float delta = rectanglePos.x * rectangleSize;
      float offset = rectangleSize / 2.0 + delta;
      
      p.x = (p.x - offset)/abs(cp - 0.5)*0.5 + offset;
      vec4 a = getFromColor(p);
      vec4 b = getToColor(p);
      
      float s = step(abs(vec2(size).x * (q.x - delta) - 0.5), abs(cp - 0.5));
      return mix(bgcolor, mix(b, a, step(cp, 0.5)), s);
    }
  }
  else {
    float currentProg = (progress - 1.0 + p_pause) / p_pause;
    float a = 1.0;
    if(getDelta(p) < getDividerSize()) {
      a = currentProg;
    }
    return mix(bgcolor, getToColor(p), a);
  }
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
