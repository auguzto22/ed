vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    float slow = sin(uTime * p_speed * 6.28318) * 0.55;
    float organic = sin(uTime * p_speed * 2.731 + 1.37) * 0.3 + sin(uTime * 0.73 + 2.1) * 0.15;
    float exposure = 1.0 + (slow + organic) * p_amount;
    return vec4(clamp(source.rgb * exposure, 0.0, 1.0), source.a);
}
