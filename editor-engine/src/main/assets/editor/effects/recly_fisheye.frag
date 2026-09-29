vec4 reclyEffect(vec2 uv) {
    vec2 p = uv - 0.5;
    float radius = length(p);
    float mapped = radius * (1.0 + p_amount * radius * radius);
    vec2 warped = 0.5 + p * mapped / max(radius, 0.0001) / p_zoom;
    return texture2D(uTexSampler, clamp(warped, 0.0, 1.0));
}
