vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    vec2 d = vec2(p_radius) / uResolution;
    vec3 blur = (texture2D(uTexSampler, uv + d).rgb + texture2D(uTexSampler, uv - d).rgb
        + texture2D(uTexSampler, uv + vec2(d.x, -d.y)).rgb + texture2D(uTexSampler, uv + vec2(-d.x, d.y)).rgb) * 0.25;
    float gate = smoothstep(p_threshold, p_threshold + 0.18, dot(blur, vec3(0.2126, 0.7152, 0.0722)));
    vec3 warm = blur * vec3(1.0, 1.0 - p_warmth * 0.45, 1.0 - p_warmth * 0.75);
    return vec4(source.rgb + warm * gate * p_amount, source.a);
}
