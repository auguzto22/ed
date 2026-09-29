vec4 reclyEffect(vec2 uv) {
    vec2 radial = uv - 0.5;
    vec2 offset = radial * dot(radial, radial) * p_amount / max(uResolution.x, uResolution.y) * 12.0;
    vec4 source = texture2D(uTexSampler, uv);
    return vec4(texture2D(uTexSampler, clamp(uv + offset, 0.0, 1.0)).r, source.g,
        texture2D(uTexSampler, clamp(uv - offset, 0.0, 1.0)).b, source.a);
}
