vec4 reclyEffect(vec2 uv) {
    float t = uTime * p_speed;
    vec2 off = vec2(sin(t * 1.7) + sin(t * 3.1) * 0.5, cos(t * 2.3) + cos(t * 4.7) * 0.5) * p_amount * 0.5;
    float a = sin(t * 1.9) * p_rot;
    vec2 p = (uv - 0.5) * (1.0 - p_amount * 1.6);
    p = vec2(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a));
    return texture2D(uTexSampler, clamp(p + 0.5 + off, 0.0, 1.0));
}
