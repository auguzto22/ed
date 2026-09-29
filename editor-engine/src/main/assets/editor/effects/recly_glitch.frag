vec4 reclyEffect(vec2 uv) {
    float frame = floor(uTime * p_speed);
    float band = floor(uv.y * p_blocks);
    float randomValue = fract(sin(dot(vec2(band, frame), vec2(12.9898, 78.233))) * 43758.5453);
    float tear = step(1.0 - p_amount, randomValue) * (randomValue - 0.5) * p_distance / uResolution.x;
    vec2 warped = clamp(uv + vec2(tear, 0.0), 0.0, 1.0);
    vec2 channel = vec2(p_distance / uResolution.x * p_amount, 0.0);
    vec4 source = texture2D(uTexSampler, warped);
    return vec4(texture2D(uTexSampler, clamp(warped + channel, 0.0, 1.0)).r, source.g,
        texture2D(uTexSampler, clamp(warped - channel, 0.0, 1.0)).b, source.a);
}
