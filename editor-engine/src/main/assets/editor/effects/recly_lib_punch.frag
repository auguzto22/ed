vec4 reclyEffect(vec2 uv) {
    float p = pow(abs(sin(uTime * p_rate * 3.14159265)), p_sharp);
    vec2 u = 0.5 + (uv - 0.5) / (1.0 + p_amount * p);
    return texture2D(uTexSampler, u);
}
