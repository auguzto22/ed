vec4 reclyEffect(vec2 uv) {
    vec2 d = vec2(p_radius) / uResolution;
    vec4 c = texture2D(uTexSampler, uv);
    vec3 bloom = (texture2D(uTexSampler, uv + d).rgb + texture2D(uTexSampler, uv - d).rgb
        + texture2D(uTexSampler, uv + vec2(d.x,-d.y)).rgb + texture2D(uTexSampler, uv + vec2(-d.x,d.y)).rgb) * .25;
    vec3 gate = smoothstep(vec3(p_threshold), vec3(p_threshold + 0.12), bloom);
    vec3 highlight = max(bloom - vec3(p_threshold), vec3(0.0)) * gate;
    return vec4(c.rgb + highlight * 1.35, c.a);
}
