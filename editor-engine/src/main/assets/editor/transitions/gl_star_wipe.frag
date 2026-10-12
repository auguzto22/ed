// Star Wipe — adaptado de gl-transitions (StarWipe.glsl)
// Autor: Ben Lucas · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
const vec4 border_color = vec4(1.0);
const vec2 star_center = vec2(0.5);
// Author: Ben Lucas
// License: MIT
// Description: A growing star reveals the incoming image
// Tags: wipe, shape, star
#define PI 3.141592653589793
#define STAR_ANGLE 1.2566370614359172

// @param Thickness of the star's border


// @color Color of the star's border

// @param Center of the star

vec2 rotate(vec2 v, float theta) {
    float cosTheta = cos(theta);
    float sinTheta = sin(theta);

    return vec2(
        cosTheta * v.x - sinTheta * v.y,
        sinTheta * v.x + cosTheta * v.y
    );
}

bool inStar(vec2 uv, vec2 center, float radius){
  vec2 uv_centered = uv - center;
  uv_centered = rotate(uv_centered, p_star_rotation * STAR_ANGLE);
  float theta = atan(uv_centered.y, uv_centered.x) + PI;

  vec2 uv_rotated = rotate(uv_centered, -STAR_ANGLE * (floor(theta / STAR_ANGLE) + 0.5));

  float slope = 0.3;
  if(uv_rotated.y > 0.0){
      return (radius + uv_rotated.x * slope > uv_rotated.y);
  } else {
     return (-radius - uv_rotated.x * slope < uv_rotated.y);
  }
}

vec4 transition (vec2 uv) {
  float progressScaled = (2.0 * p_border_thickness + 1.0) * progress - p_border_thickness;
  if(inStar(uv, star_center, progressScaled)){
    return getToColor(uv);
  } else if(inStar(uv, star_center, progressScaled+p_border_thickness)){
    return border_color;
  } else {
    return getFromColor(uv);
  }
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
