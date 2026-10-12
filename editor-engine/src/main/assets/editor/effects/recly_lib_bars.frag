vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float e = mix(abs(uv.y - 0.5), abs(uv.x - 0.5), p_side);
    float m = smoothstep(0.5 - p_size - p_soft, 0.5 - p_size, e);
    return vec4(mix(s.rgb, vec3(p_r, p_g, p_b), m), s.a);
}
