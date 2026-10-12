vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    vec3 c = s.rgb;
    c = pow(max(c, vec3(0.0)), vec3(1.0 / p_gamma));
    c = c * p_gain + vec3(p_lift);
    c = (c - 0.5) * p_contrast + 0.5;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, p_sat);
    c.r += p_temp * 0.08;
    c.b -= p_temp * 0.08;
    c.g += p_tint * 0.05;
    c.r -= p_tint * 0.02;
    c.b -= p_tint * 0.02;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}
