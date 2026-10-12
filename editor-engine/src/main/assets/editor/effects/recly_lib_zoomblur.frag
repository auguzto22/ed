vec4 reclyEffect(vec2 uv) {
    vec2 d = (uv - vec2(p_cx, p_cy)) * p_amount;
    vec4 c = texture2D(uTexSampler, uv) * 0.25;
    c += texture2D(uTexSampler, uv - d * 0.25) * 0.2;
    c += texture2D(uTexSampler, uv - d * 0.5) * 0.17;
    c += texture2D(uTexSampler, uv - d * 0.75) * 0.14;
    c += texture2D(uTexSampler, uv - d) * 0.12;
    c += texture2D(uTexSampler, uv - d * 1.25) * 0.08;
    c += texture2D(uTexSampler, uv - d * 1.5) * 0.04;
    return c;
}
