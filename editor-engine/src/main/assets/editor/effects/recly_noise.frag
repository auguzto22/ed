vec4 reclyEffect(vec2 uv) {
    vec4 c = texture2D(uTexSampler, uv);
    float n = fract(sin(dot(uv * uResolution + floor(uTime * p_speed), vec2(12.9898, 78.233))) * 43758.5453);
    return vec4(clamp(c.rgb + (n - 0.5) * p_amount, 0.0, 1.0), c.a);
}
