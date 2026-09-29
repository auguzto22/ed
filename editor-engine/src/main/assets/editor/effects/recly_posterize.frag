vec4 reclyEffect(vec2 uv) {
    vec4 c = texture2D(uTexSampler, uv);
    return vec4(floor(c.rgb * p_levels + 0.5) / p_levels, c.a);
}
