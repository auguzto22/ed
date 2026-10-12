vec4 reclyEffect(vec2 uv) {
    vec2 c = vec2(p_cx, p_cy);
    vec2 d = (uv - c) * vec2(uResolution.x / uResolution.y, 1.0);
    float r = length(d);
    float k = smoothstep(p_radius, 0.0, r);
    float a = k * k * p_angle + uTime * p_spin * k;
    float cs = cos(a);
    float sn = sin(a);
    d = vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
    vec2 u = c + d * vec2(uResolution.y / uResolution.x, 1.0);
    return texture2D(uTexSampler, clamp(u, 0.0, 1.0));
}
