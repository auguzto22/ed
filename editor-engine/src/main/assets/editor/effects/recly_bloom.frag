vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    vec2 d = vec2(p_radius) / uResolution;
    vec3 blur = (texture2D(uTexSampler, uv + vec2(d.x, 0.0)).rgb + texture2D(uTexSampler, uv - vec2(d.x, 0.0)).rgb
        + texture2D(uTexSampler, uv + vec2(0.0, d.y)).rgb + texture2D(uTexSampler, uv - vec2(0.0, d.y)).rgb) * 0.25;
    float luminance = dot(blur, vec3(0.2126, 0.7152, 0.0722));
    vec3 highlights = blur * smoothstep(p_threshold, p_threshold + 0.15, luminance);
    return vec4(source.rgb + highlights * p_gain, source.a);
}
