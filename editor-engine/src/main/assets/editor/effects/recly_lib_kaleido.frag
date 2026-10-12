vec4 reclyEffect(vec2 uv) {
    vec2 p = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
    float a = atan(p.y, p.x) + uTime * p_spin * 0.3;
    float r = length(p) / p_zoom;
    float seg = 6.2831853 / floor(p_segs + 0.5);
    a = abs(mod(a, seg) - seg * 0.5);
    vec2 u = 0.5 + r * vec2(cos(a), sin(a)) * vec2(uResolution.y / uResolution.x, 1.0);
    return texture2D(uTexSampler, abs(mod(u, 2.0) - 1.0) * -1.0 + 1.0);
}
