float lumaAt(vec2 u) {
    return dot(texture2D(uTexSampler, u).rgb, vec3(0.2126, 0.7152, 0.0722));
}
vec4 reclyEffect(vec2 uv) {
    vec2 px = 1.0 / uResolution;
    float tl = lumaAt(uv + vec2(-px.x, -px.y));
    float t = lumaAt(uv + vec2(0.0, -px.y));
    float tr = lumaAt(uv + vec2(px.x, -px.y));
    float l = lumaAt(uv + vec2(-px.x, 0.0));
    float r = lumaAt(uv + vec2(px.x, 0.0));
    float bl = lumaAt(uv + vec2(-px.x, px.y));
    float b = lumaAt(uv + vec2(0.0, px.y));
    float br = lumaAt(uv + vec2(px.x, px.y));
    float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
    float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    float e = clamp(length(vec2(gx, gy)) * p_strength, 0.0, 1.0);
    vec4 s = texture2D(uTexSampler, uv);
    return vec4(clamp(s.rgb * p_base + vec3(p_r, p_g, p_b) * e, 0.0, 1.0), s.a);
}
