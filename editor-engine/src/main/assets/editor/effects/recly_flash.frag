vec4 reclyEffect(vec2 uv) {
    vec4 c = texture2D(uTexSampler, uv);
    float flash = pow(max(sin(uTime * p_frequency * 6.28318), 0.0), 8.0) * p_amount;
    return vec4(mix(c.rgb, vec3(1.0), flash), c.a);
}
