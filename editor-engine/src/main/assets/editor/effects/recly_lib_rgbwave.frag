vec4 reclyEffect(vec2 uv) {
    float o = sin(uv.y * p_freq + uTime * p_speed) * p_amount;
    float r = texture2D(uTexSampler, vec2(uv.x + o, uv.y)).r;
    vec4 g = texture2D(uTexSampler, uv);
    float b = texture2D(uTexSampler, vec2(uv.x - o, uv.y)).b;
    return vec4(r, g.g, b, g.a);
}
