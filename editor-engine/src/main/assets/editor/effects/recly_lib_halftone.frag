vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    vec2 g = vec2(p_cells, p_cells * uResolution.y / uResolution.x);
    vec2 id = floor(uv * g);
    vec2 f = fract(uv * g) - 0.5;
    vec4 c = texture2D(uTexSampler, (id + 0.5) / g);
    float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    float radius = sqrt(l) * 0.72;
    float m = smoothstep(radius, radius - 0.25 * p_soft - 0.02, length(f));
    return vec4(mix(s.rgb, c.rgb * m, p_amount), s.a);
}
