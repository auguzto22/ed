float rnd(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    vec2 g = vec2(p_density, p_density * uResolution.y / uResolution.x);
    vec2 id = floor(uv * g);
    vec2 f = fract(uv * g) - 0.5;
    float h = rnd(id);
    vec2 pos = vec2(rnd(id + 3.1), rnd(id + 7.7)) - 0.5;
    pos *= 0.6;
    float tw = max(0.0, sin(uTime * p_speed + h * 6.2831853));
    tw = tw * tw * step(0.55, h);
    vec2 d = abs(f - pos);
    float star = max(0.0, 1.0 - (d.x / (p_size * 0.35) + d.y / (p_size * 0.35)) ) * 0.4;
    star += max(0.0, 1.0 - d.x / (p_size * 0.07 + 0.001) - d.y / p_size) + max(0.0, 1.0 - d.y / (p_size * 0.07 + 0.001) - d.x / p_size);
    vec3 c = s.rgb + vec3(p_r, p_g, p_b) * star * tw * p_amount;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}
