vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float l = dot(s.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 duo = mix(vec3(p_sr, p_sg, p_sb), vec3(p_hr, p_hg, p_hb), smoothstep(0.05, 0.95, l));
    return vec4(mix(s.rgb, duo, p_amount), s.a);
}
