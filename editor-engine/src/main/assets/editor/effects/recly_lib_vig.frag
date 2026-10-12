vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float d = distance(uv, vec2(0.5)) * 1.4142;
    float v = smoothstep(p_size, p_size + p_soft, d);
    return vec4(mix(s.rgb, vec3(p_r, p_g, p_b), v * p_amount), s.a);
}
