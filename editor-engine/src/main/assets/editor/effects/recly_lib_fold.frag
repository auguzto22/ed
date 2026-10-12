vec4 reclyEffect(vec2 uv) {
    vec2 u = uv;
    float m = floor(p_mode + 0.5);
    if (m < 0.5) u.x = mix(uv.x, 2.0 * p_pos - uv.x, step(p_pos, uv.x));
    else if (m < 1.5) u.x = mix(uv.x, 2.0 * p_pos - uv.x, step(uv.x, p_pos));
    else if (m < 2.5) u.y = mix(uv.y, 2.0 * p_pos - uv.y, step(p_pos, uv.y));
    else u.y = mix(uv.y, 2.0 * p_pos - uv.y, step(uv.y, p_pos));
    vec4 s = texture2D(uTexSampler, uv);
    vec4 f = texture2D(uTexSampler, clamp(u, 0.0, 1.0));
    return vec4(mix(s.rgb, f.rgb, p_blend), s.a);
}
