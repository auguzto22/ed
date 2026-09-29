vec4 reclyTransition(vec2 uv, float progress) {
    vec4 ca = texture2D(uTexA, uv);
    vec4 cb = texture2D(uTexB, uv);
    if (progress <= 0.0) return ca;
    if (progress >= 1.0) return cb;

    float t = clamp(progress, 0.0, 1.0);

    if (p_mode < 0.5) {
        // Cross Dissolve (with softness support)
        float softness = clamp(p_softness, 0.0, 1.0);
        float blendT = mix(t, smoothstep(0.0, 1.0, t), softness);
        return mix(ca, cb, blendT);
    } else if (p_mode < 1.5) {
        // Dip to Black (smooth V-curve)
        vec4 black = vec4(0.0, 0.0, 0.0, 1.0);
        if (t < 0.5) {
            return mix(ca, black, t * 2.0);
        } else {
            return mix(black, cb, (t - 0.5) * 2.0);
        }
    } else if (p_mode < 2.5) {
        // Dip to White (smooth V-curve)
        vec4 white = vec4(1.0, 1.0, 1.0, 1.0);
        if (t < 0.5) {
            return mix(ca, white, t * 2.0);
        } else {
            return mix(white, cb, (t - 0.5) * 2.0);
        }
    } else if (p_mode < 3.5) {
        // Fade Through Black (with brief solid hold in middle)
        vec4 black = vec4(0.0, 0.0, 0.0, 1.0);
        if (t < 0.4) {
            return mix(ca, black, t / 0.4);
        } else if (t <= 0.6) {
            return black;
        } else {
            return mix(black, cb, (t - 0.6) / 0.4);
        }
    } else if (p_mode < 4.5) {
        // Fade Through White (with brief solid hold in middle)
        vec4 white = vec4(1.0, 1.0, 1.0, 1.0);
        if (t < 0.4) {
            return mix(ca, white, t / 0.4);
        } else if (t <= 0.6) {
            return white;
        } else {
            return mix(white, cb, (t - 0.6) / 0.4);
        }
    } else {
        // Fade: eased opacity blend
        float eased = t * t * (3.0 - 2.0 * t);
        return mix(ca, cb, eased);
    }
}
