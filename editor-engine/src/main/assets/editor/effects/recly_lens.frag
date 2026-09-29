vec4 reclyEffect(vec2 uv) {
    vec2 p = (uv - 0.5) / p_zoom;
    float r2 = dot(p, p);
    vec2 warped = 0.5 + p * (1.0 + p_amount * r2);
    return texture2D(uTexSampler, clamp(warped, 0.0, 1.0));
}
