float rnd(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float n = rnd(uv * uResolution + fract(uTime) * 91.7) - 0.5;
    float d = step(0.9992, rnd(floor(uv * vec2(180.0, 100.0)) + floor(uTime * 12.0)));
    float f = 1.0 + (rnd(vec2(floor(uTime * 24.0), 3.0)) - 0.5) * 0.25 * p_flick;
    vec3 c = s.rgb * f + n * 0.22 * p_grain + d * p_dust * 0.6;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}
