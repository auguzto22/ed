vec4 reclyEffect(vec2 uv) {
    vec2 d = vec2(1.0) / uResolution;
    vec4 center = texture2D(uTexSampler, uv);
    vec4 neighbors = texture2D(uTexSampler, uv + vec2(d.x, 0.0)) + texture2D(uTexSampler, uv - vec2(d.x, 0.0))
        + texture2D(uTexSampler, uv + vec2(0.0, d.y)) + texture2D(uTexSampler, uv - vec2(0.0, d.y));
    return vec4(clamp(center.rgb + (center.rgb * 4.0 - neighbors.rgb) * p_amount, 0.0, 1.0), center.a);
}
