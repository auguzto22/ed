vec4 reclyEffect(vec2 uv) {
    vec2 p = uv - 0.5;
    vec2 warped = 0.5 + p * (1.0 + p_curvature * dot(p, p));
    warped = clamp(warped, 0.0, 1.0);
    vec2 channel = vec2(p_chromatic / uResolution.x, 0.0);
    vec4 source = texture2D(uTexSampler, warped);
    vec3 color = vec3(texture2D(uTexSampler, clamp(warped + channel, 0.0, 1.0)).r, source.g,
        texture2D(uTexSampler, clamp(warped - channel, 0.0, 1.0)).b);
    float scan = 1.0 - p_scanlines * (0.5 + 0.5 * sin(uv.y * uResolution.y * 3.14159));
    float vignette = smoothstep(0.78, 0.25, length(p));
    return vec4(clamp(color * scan * vignette, 0.0, 1.0), source.a);
}
