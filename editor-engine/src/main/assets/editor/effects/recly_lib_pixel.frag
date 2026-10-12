vec4 reclyEffect(vec2 uv) {
    vec2 g = vec2(p_cells, p_cells * uResolution.y / uResolution.x);
    vec2 q = (floor(uv * g) + 0.5) / g;
    vec4 s = texture2D(uTexSampler, uv);
    vec4 p = texture2D(uTexSampler, q);
    return vec4(mix(s.rgb, p.rgb, p_mixv), s.a);
}
