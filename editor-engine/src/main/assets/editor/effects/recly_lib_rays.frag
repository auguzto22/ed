vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    vec2 p = (uv - vec2(p_cx, p_cy)) * vec2(uResolution.x / uResolution.y, 1.0);
    float a = atan(p.y, p.x);
    float ray = pow(0.5 + 0.5 * sin(a * p_count + uTime * p_speed), 3.0);
    float fall = exp(-length(p) * 2.2);
    vec3 c = s.rgb + vec3(p_r, p_g, p_b) * ray * fall * p_amount;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}
