vec4 reclyEffect(vec2 uv) {
    vec4 c = texture2D(uTexSampler, uv);
    float d = length((uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0));
    float edge = smoothstep(0.5 - p_softness, 0.72, d) * p_amount;
    return vec4(c.rgb * (1.0 - edge), c.a);
}
