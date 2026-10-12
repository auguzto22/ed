vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float p = pow(max(0.0, sin(uTime * p_rate * 6.2831853)), p_sharp);
    vec3 tint = mix(vec3(1.0), vec3(1.0, 0.78, 0.48), p_warm);
    return vec4(clamp(s.rgb + tint * p * p_amount * 0.7, 0.0, 1.0), s.a);
}
