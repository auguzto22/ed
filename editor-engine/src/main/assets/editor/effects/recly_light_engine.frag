vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    vec3 tint = vec3(p_color_r, p_color_g, p_color_b);
    
    if (p_mode < 0.5) {
        // Edge Glow: Sobel filter for edge detection + luminous neon glow tint
        vec2 stepSize = 1.5 / uResolution;
        vec3 h = -texture2D(uTexSampler, uv + vec2(-stepSize.x, -stepSize.y)).rgb
                 + texture2D(uTexSampler, uv + vec2( stepSize.x, -stepSize.y)).rgb
                 - 2.0 * texture2D(uTexSampler, uv + vec2(-stepSize.x, 0.0)).rgb
                 + 2.0 * texture2D(uTexSampler, uv + vec2( stepSize.x, 0.0)).rgb
                 - texture2D(uTexSampler, uv + vec2(-stepSize.x,  stepSize.y)).rgb
                 + texture2D(uTexSampler, uv + vec2( stepSize.x,  stepSize.y)).rgb;
        vec3 v = -texture2D(uTexSampler, uv + vec2(-stepSize.x, -stepSize.y)).rgb
                 - 2.0 * texture2D(uTexSampler, uv + vec2(0.0, -stepSize.y)).rgb
                 - texture2D(uTexSampler, uv + vec2( stepSize.x, -stepSize.y)).rgb
                 + texture2D(uTexSampler, uv + vec2(-stepSize.x,  stepSize.y)).rgb
                 + 2.0 * texture2D(uTexSampler, uv + vec2(0.0,  stepSize.y)).rgb
                 + texture2D(uTexSampler, uv + vec2( stepSize.x,  stepSize.y)).rgb;
        float edge = length(h) + length(v);
        vec3 glow = edge * tint * p_amount * 2.0;
        return vec4(source.rgb + glow, source.a);
    } else if (p_mode < 1.5) {
        // Light Leak: organic procedural warm amber/orange light leak on edges
        vec2 pos = uv - vec2(0.1, 0.2);
        float d = length(pos * vec2(1.0, 1.4));
        float flicker = 0.8 + 0.2 * sin(uTime * p_frequency * 3.0) + 0.1 * cos(uTime * 5.2);
        float leakMask = smoothstep(0.75, 0.1, d) * flicker * p_amount;
        vec3 leakColor = tint * leakMask;
        return vec4(source.rgb + leakColor * 1.5, source.a);
    } else if (p_mode < 2.5) {
        // Lens Flare: anamorphic horizontal optical streak + circular halo
        vec2 flareCenter = vec2(0.5 + 0.2 * sin(uTime * 0.4), 0.5 + 0.1 * cos(uTime * 0.3));
        vec2 d = uv - flareCenter;
        d.x *= uResolution.x / uResolution.y;
        float streak = exp(-abs(d.y) * 45.0) * exp(-abs(d.x) * 1.5) * p_amount * 1.8;
        float halo = smoothstep(0.28, 0.25, abs(length(d) - 0.22)) * p_amount * 0.6;
        float core = exp(-length(d) * 12.0) * p_amount * 1.2;
        vec3 flare = (streak + halo + core) * tint;
        return vec4(source.rgb + flare, source.a);
    } else if (p_mode < 3.5) {
        // Color Flash: tinted rhythmic explosive flash with decay
        float cycle = fract(uTime * p_frequency);
        float flash = exp(-cycle * 5.0) * p_amount;
        vec3 burst = mix(source.rgb, tint, flash * 0.85) + tint * flash * 0.5;
        return vec4(clamp(burst, 0.0, 1.0), source.a);
    } else {
        // Strobe: crisp shutter on-off cycle
        float strobe = step(0.5, fract(uTime * p_frequency)) * p_amount;
        return vec4(mix(source.rgb, tint, strobe * 0.75), source.a);
    }
}
