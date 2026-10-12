vec4 reclyEffect(vec2 uv) {
    float n = sin(uv.y * p_scale + uTime * p_speed) * sin(uv.x * p_scale * 0.7 - uTime * p_speed * 0.8);
    vec2 u = uv + vec2(n, cos(uv.x * p_scale + uTime * p_speed)) * p_amount;
    vec4 s = texture2D(uTexSampler, clamp(u, 0.0, 1.0));
    s.rgb += vec3(0.08, 0.03, -0.04) * p_warm;
    return s;
}
