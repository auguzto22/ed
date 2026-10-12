float rnd(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float flake(vec2 p, float density, float seed) {
    vec2 g = vec2(p.x * density, p.y * density * mix(1.0, 0.22, p_stretch));
    vec2 id = floor(g);
    vec2 f = fract(g) - 0.5;
    float h = rnd(id + seed);
    f.x += (h - 0.5) * 0.7;
    float on = step(0.55, rnd(id + seed + 11.0));
    float shape = 1.0 - smoothstep(p_size * 0.5, p_size, length(f * vec2(1.0, mix(1.0, 0.18, p_stretch))));
    return shape * on;
}
vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    vec2 p = vec2(uv.x * uResolution.x / uResolution.y, uv.y);
    p.x += p.y * p_slant;
    float f = flake(p + vec2(0.0, uTime * p_speed), p_density, 0.0);
    f += flake(p * 1.7 + vec2(0.3, uTime * p_speed * 1.4), p_density, 5.0) * 0.7;
    f += flake(p * 2.6 + vec2(0.6, uTime * p_speed * 1.9), p_density, 9.0) * 0.5;
    vec3 c = s.rgb + vec3(p_r, p_g, p_b) * f * p_amount;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}
