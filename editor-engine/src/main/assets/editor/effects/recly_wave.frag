vec4 reclyEffect(vec2 uv) {
    vec2 warped = uv;
    warped.x += sin(uv.y * p_frequency + uTime * p_speed) * p_amount / uResolution.x;
    return texture2D(uTexSampler, clamp(warped, 0.0, 1.0));
}
