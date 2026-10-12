float rnd(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
vec4 reclyEffect(vec2 uv) {
    float row = floor(uv.y * p_rows);
    float t = floor(uTime * p_rate);
    float on = step(0.72, rnd(vec2(row, t)));
    float shift = (rnd(vec2(row, t + 7.0)) - 0.5) * p_amount * on;
    vec2 u = vec2(fract(uv.x + shift), uv.y);
    float r = texture2D(uTexSampler, u + vec2(p_rgb * on, 0.0)).r;
    vec4 g = texture2D(uTexSampler, u);
    float b = texture2D(uTexSampler, u - vec2(p_rgb * on, 0.0)).b;
    return vec4(r, g.g, b, g.a);
}
