vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float l = dot(s.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 c = mix(vec3(l), s.rgb, p_sat);
    vec3 q = floor(c * p_levels + 0.5) / p_levels;
    return vec4(mix(s.rgb, clamp(q, 0.0, 1.0), p_amount), s.a);
}
