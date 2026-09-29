vec4 reclyEffect(vec2 uv) {
    float line = floor(uv.y * uResolution.y);
    float shift = sin(line * 0.071 + floor(uTime * p_speed) * 1.7) * p_jitter / uResolution.x;
    float n = fract(sin(dot(vec2(line, floor(uTime * p_speed)), vec2(12.9898, 78.233))) * 43758.5453);
    vec2 warped = clamp(uv + vec2(shift * step(0.94, n), 0.0), 0.0, 1.0);
    vec4 c = texture2D(uTexSampler, warped);
    float scan = 0.82 + 0.18 * sin(line * 3.14159);
    return vec4(clamp(c.rgb * scan + (n - 0.5) * p_noise, 0.0, 1.0), c.a);
}
