vec4 reclyEffect(vec2 uv) {
    vec2 d = vec2(p_radius) / uResolution;
    vec3 c = texture2D(uTexSampler, uv).rgb;
    vec3 gx = texture2D(uTexSampler, uv + vec2(d.x, 0.0)).rgb - texture2D(uTexSampler, uv - vec2(d.x, 0.0)).rgb;
    vec3 gy = texture2D(uTexSampler, uv + vec2(0.0, d.y)).rgb - texture2D(uTexSampler, uv - vec2(0.0, d.y)).rgb;
    float edge = length(gx) + length(gy);
    vec3 neon = vec3(edge * 0.25, edge * 0.8, edge) * p_gain;
    return vec4(clamp(c * 0.35 + neon, 0.0, 1.0), texture2D(uTexSampler, uv).a);
}
