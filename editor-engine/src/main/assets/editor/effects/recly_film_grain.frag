vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    vec2 cell = floor(uv * uResolution / max(p_size, 1.0));
    float mono = fract(sin(dot(cell + floor(uTime * 24.0), vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
    vec3 colorNoise = vec3(mono, fract(mono * 17.17) - 0.5, fract(mono * 31.73) - 0.5);
    vec3 grain = mix(colorNoise, vec3(mono), p_monochrome);
    return vec4(clamp(source.rgb + grain * p_amount, 0.0, 1.0), source.a);
}
