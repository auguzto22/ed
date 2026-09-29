vec4 reclyEffect(vec2 uv) {
    float pulse = 1.0 + p_amount * (0.5 + 0.5 * sin(uTime * p_frequency * 6.28318));
    vec2 warped = (uv - 0.5) / pulse + 0.5;
    return texture2D(uTexSampler, clamp(warped, 0.0, 1.0));
}
