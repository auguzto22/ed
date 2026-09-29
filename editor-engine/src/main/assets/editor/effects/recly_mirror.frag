vec4 reclyEffect(vec2 uv) {
    vec2 mirrored = vec2(mix(uv.x, 1.0 - abs(uv.x * 2.0 - 1.0), p_horizontal), mix(uv.y, 1.0 - abs(uv.y * 2.0 - 1.0), p_vertical));
    return texture2D(uTexSampler, clamp(mirrored, 0.0, 1.0));
}
