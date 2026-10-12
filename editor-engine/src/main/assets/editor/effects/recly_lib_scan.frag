float rnd(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
vec4 reclyEffect(vec2 uv) {
    float y = uv.y + p_roll * 0.05 * sin(uTime * 1.3 + uv.y * 4.0);
    vec4 s = texture2D(uTexSampler, vec2(uv.x, fract(y)));
    float line = 0.5 + 0.5 * sin(uv.y * p_lines * 3.14159);
    float band = smoothstep(0.0, 1.0, fract(uv.y - uTime * 0.12 * p_roll));
    float f = 1.0 + (rnd(vec2(floor(uTime * 30.0), 1.0)) - 0.5) * 0.12 * p_flick;
    vec3 c = s.rgb * (1.0 - p_strength * 0.45 * (1.0 - line)) * mix(1.0, 0.85 + 0.3 * band, p_roll) * f;
    return vec4(c, s.a);
}
