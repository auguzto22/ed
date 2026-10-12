vec4 reclyEffect(vec2 uv) {
    float a = radians(p_angle);
    vec2 d = vec2(cos(a), sin(a) * uResolution.x / uResolution.y) * p_amount;
    vec4 c = texture2D(uTexSampler, uv) * 0.2;
    c += texture2D(uTexSampler, uv + d * 0.33) * 0.16;
    c += texture2D(uTexSampler, uv - d * 0.33) * 0.16;
    c += texture2D(uTexSampler, uv + d * 0.66) * 0.11;
    c += texture2D(uTexSampler, uv - d * 0.66) * 0.11;
    c += texture2D(uTexSampler, uv + d) * 0.13;
    c += texture2D(uTexSampler, uv - d) * 0.13;
    return c;
}
