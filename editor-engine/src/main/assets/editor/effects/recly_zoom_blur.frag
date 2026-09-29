vec4 reclyEffect(vec2 uv) {
    vec2 center = vec2(p_center_x, p_center_y);
    vec2 ray = (center - uv) * p_strength;
    vec4 color = texture2D(uTexSampler, uv) * 0.22;
    color += texture2D(uTexSampler, clamp(uv + ray * 0.2, 0.0, 1.0)) * 0.19;
    color += texture2D(uTexSampler, clamp(uv + ray * 0.4, 0.0, 1.0)) * 0.17;
    color += texture2D(uTexSampler, clamp(uv + ray * 0.6, 0.0, 1.0)) * 0.15;
    color += texture2D(uTexSampler, clamp(uv + ray * 0.8, 0.0, 1.0)) * 0.14;
    color += texture2D(uTexSampler, clamp(uv + ray, 0.0, 1.0)) * 0.13;
    return color;
}
