// Static Fade — adaptado de gl-transitions (StaticFade.glsl)
// Autor: Ben Lucas · Licença: MIT
// https://github.com/gl-transitions/gl-transitions
vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }
vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }
float progress;
float ratio;
// Author: Ben Lucas
// License: MIT
// Description: Cuts to the incoming image halfway, hidden behind a burst of TV static
// Tags: static, noise, retro


float rnd (vec2 st) {
    return fract(sin(dot(st.xy,
                         vec2(10.5302340293,70.23492931)))*
        12345.5453123);
}

vec4 staticNoise (vec2 st, float offset, float luminosity) {
  float staticR = luminosity * rnd(st * vec2(offset * 2.0, offset * 3.0));
  float staticG = luminosity * rnd(st * vec2(offset * 3.0, offset * 5.0));
  float staticB = luminosity * rnd(st * vec2(offset * 5.0, offset * 7.0));
  return vec4(staticR, staticG, staticB, 1.0);
}

float staticIntensity(float t)
{
  float transitionProgress = abs(2.0*(t-0.5));
  float transformedThreshold =1.2*(1.0 - transitionProgress)-0.1;
  return min(1.0, transformedThreshold);
}
  
vec4 transition (vec2 uv) {

  float baseMix = step(0.5, progress);
  vec4 transitionMix = mix(
    getFromColor(uv),
    getToColor(uv),
    baseMix
  );
  
  vec2 uvStatic = floor(uv * p_n_noise_pixels)/p_n_noise_pixels;
  
  vec4 staticColor = staticNoise(uvStatic, progress, p_static_luminosity);

  float staticThresh = staticIntensity(progress);
  float staticMix = step(rnd(uvStatic), staticThresh);

  return mix(transitionMix, staticColor, staticMix);
}

vec4 reclyTransition(vec2 uv, float reclyProgress) {
    progress = clamp(reclyProgress, 0.0, 1.0);
    ratio = uResolution.x / max(uResolution.y, 1.0);
    return transition(uv);
}
