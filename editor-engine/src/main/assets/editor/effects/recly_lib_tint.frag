vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float l = dot(s.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 t = vec3(p_r, p_g, p_b);
    vec3 c = s.rgb * mix(vec3(1.0), t * 2.0, p_amount);
    c += t * p_amount * 0.08 * (1.0 - l);
    return vec4(clamp(c, 0.0, 1.0), s.a);
}
