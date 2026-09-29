float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec4 reclyTransition(vec2 uv, float progress) {
    vec4 ca = texture2D(uTexA, uv);
    vec4 cb = texture2D(uTexB, uv);
    if (progress <= 0.0) return ca;
    if (progress >= 1.0) return cb;

    float softness = max(p_softness, 0.01);
    float t = clamp(progress, 0.0, 1.0);
    float threshold = mix(-softness, 1.0 + softness, t);

    float maskVal = 0.0;
    if (p_mode < 0.5) {
        // Luma Fade: uses luminance of Clip A as gradient threshold
        float lum = luma(ca.rgb);
        if (p_invert > 0.5) lum = 1.0 - lum;
        maskVal = smoothstep(threshold - softness, threshold + softness, lum);
    } else {
        // Luma Wipe: organic diagonal gradient combined with image luminance
        float grad = (uv.x * 0.7 + uv.y * 0.3) * 0.6 + luma(ca.rgb) * 0.4;
        if (p_invert > 0.5) grad = 1.0 - grad;
        maskVal = smoothstep(threshold - softness, threshold + softness, grad);
    }

    return mix(cb, ca, clamp(maskVal, 0.0, 1.0));
}
