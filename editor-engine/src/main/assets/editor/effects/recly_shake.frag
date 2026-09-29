vec4 reclyEffect(vec2 uv) {
    float envelope = mix(1.0, 1.0 - uProgress, p_decay);
    vec2 offset = vec2(sin(uTime*p_frequency*6.28318),cos(uTime*p_frequency*7.53982))
        * p_distance * envelope / uResolution;
    float angle = sin(uTime * p_frequency * 5.173) * p_rotation * envelope * 0.0174533;
    float c = cos(angle); float s = sin(angle);
    vec2 centered = mat2(c, -s, s, c) * (uv - 0.5) / 1.04;
    return texture2D(uTexSampler, clamp(centered + 0.5 + offset,0.0,1.0));
}
