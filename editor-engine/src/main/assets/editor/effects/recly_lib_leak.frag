vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float a = radians(p_angle);
    float t = dot(uv - 0.5, vec2(cos(a), sin(a))) + 0.5 + sin(uTime * p_drift) * 0.18;
    float leak = smoothstep(0.0, p_width, 1.0 - abs(t - 0.5) * 2.0);
    leak = leak * leak;
    vec3 col = vec3(p_r, p_g, p_b);
    vec3 c = 1.0 - (1.0 - s.rgb) * (1.0 - col * leak * p_amount);
    return vec4(c, s.a);
}
