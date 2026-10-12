vec4 reclyEffect(vec2 uv) {
    vec2 p = uv * 2.0 - 1.0;
    p *= 1.0 + dot(p, p) * p_curve * 0.25;
    vec2 u = p * 0.5 + 0.5;
    float inside = step(0.0, u.x) * step(u.x, 1.0) * step(0.0, u.y) * step(u.y, 1.0);
    vec4 s = texture2D(uTexSampler, clamp(u, 0.0, 1.0));
    float line = 0.5 + 0.5 * sin(u.y * uResolution.y * 1.5);
    float tri = mod(floor(uv.x * uResolution.x), 3.0);
    vec3 m = vec3(step(tri, 0.5), step(0.5, tri) * step(tri, 1.5), step(1.5, tri));
    vec3 c = s.rgb * (1.0 - p_scan * 0.4 * (1.0 - line));
    c = mix(c, c * (0.6 + 0.8 * m), p_mask * 0.5);
    c *= 1.0 - p_vig * 0.6 * dot(p, p) * 0.5;
    return vec4(c * inside, s.a);
}
