vec4 reclyEffect(vec2 uv) {
    vec2 u = uv;
    u.x += sin(uv.y * p_freq + uTime * p_speed) * p_amp * (1.0 - p_dir);
    u.y += sin(uv.x * p_freq + uTime * p_speed) * p_amp * p_dir;
    return texture2D(uTexSampler, clamp(u, 0.0, 1.0));
}
