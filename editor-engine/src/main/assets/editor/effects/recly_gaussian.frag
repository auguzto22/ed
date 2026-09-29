vec4 reclyEffect(vec2 uv) {
    vec2 x = vec2(p_radius * p_horizontal / uResolution.x, 0.0);
    vec2 y = vec2(0.0, p_radius * p_vertical / uResolution.y);
    // Normalized weights preserve a constant RGBA input (sum = 1.0).
    vec4 c = texture2D(uTexSampler, uv) * 0.206185;
    c += (texture2D(uTexSampler, uv + x) + texture2D(uTexSampler, uv - x)) * 0.110457;
    c += (texture2D(uTexSampler, uv + y) + texture2D(uTexSampler, uv - y)) * 0.110457;
    c += (texture2D(uTexSampler, uv + x + y) + texture2D(uTexSampler, uv - x - y)) * 0.087997;
    c += (texture2D(uTexSampler, uv + x - y) + texture2D(uTexSampler, uv - x + y)) * 0.087997;
    return c;
}
