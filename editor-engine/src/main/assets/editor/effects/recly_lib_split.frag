vec4 reclyEffect(vec2 uv) {
    float a = radians(p_angle);
    vec2 d = vec2(cos(a), sin(a) * uResolution.x / uResolution.y) * p_amount * (1.0 + p_wobble * sin(uTime * 8.0));
    float r = texture2D(uTexSampler, uv + d).r;
    vec4 g = texture2D(uTexSampler, uv);
    float b = texture2D(uTexSampler, uv - d).b;
    return vec4(r, g.g, b, g.a);
}
