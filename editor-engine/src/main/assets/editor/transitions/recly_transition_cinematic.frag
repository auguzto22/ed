vec4 reclyTransition(vec2 uv, float progress) {
    vec4 ca = texture2D(uTexA, uv);
    vec4 cb = texture2D(uTexB, uv);
    if (progress <= 0.0) return ca;
    if (progress >= 1.0) return cb;

    float t = clamp(progress, 0.0, 1.0);
    float amount = sin(t * 3.14159265);

    if (p_mode < 0.5) {
        // Anamorphic Streak: horizontal streak flare sweep
        vec4 base = mix(ca, cb, smoothstep(0.3, 0.7, t));
        float flarePos = t;
        float flareDist = abs(uv.x - flarePos);
        float streak = exp(-flareDist * 18.0) * exp(-abs(uv.y - 0.5) * 6.0);
        vec3 anamorphicColor = vec3(0.2, 0.6, 1.0); // blue anamorphic streak
        base.rgb += anamorphicColor * streak * amount * 2.5 * p_intensity;
        return clamp(base, 0.0, 1.0);
    } else if (p_mode < 1.5) {
        // Letterbox Reveal: top and bottom curtain wipe
        float barHeight = amount * 0.5;
        if (uv.y < barHeight || uv.y > (1.0 - barHeight)) {
            return vec4(0.0, 0.0, 0.0, 1.0);
        } else {
            return mix(ca, cb, t);
        }
    } else if (p_mode < 2.5) {
        // Flare Sweep: moving warm cinematic lens flare
        vec2 flareCenter = vec2(t * 1.4 - 0.2, 0.5);
        float d = distance(uv, flareCenter);
        float ring = smoothstep(0.25, 0.0, abs(d - 0.2));
        float glow = exp(-d * 4.0);
        vec3 flare = vec3(1.0, 0.85, 0.5) * glow * 1.5 + vec3(1.0, 0.4, 0.2) * ring * 0.8;
        vec4 blended = mix(ca, cb, smoothstep(0.4, 0.6, t));
        blended.rgb += flare * amount * p_intensity;
        return clamp(blended, 0.0, 1.0);
    } else {
        // Cine Shutter: 180-degree diagonal cinematic shutter wipe
        float diagonal = uv.x * 0.7 + uv.y * 0.3;
        float edge = t * 1.4 - 0.2;
        float blur = 0.06;
        float mask = smoothstep(edge - blur, edge + blur, diagonal);
        vec4 col = mix(cb, ca, mask);
        float shutterFringe = (1.0 - abs(mask - 0.5) * 2.0) * amount * 0.2;
        col.rgb += vec3(shutterFringe);
        return clamp(col, 0.0, 1.0);
    }
}

