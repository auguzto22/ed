vec4 reclyTransition(vec2 uv, float progress) {
    vec4 ca = texture2D(uTexA, uv);
    vec4 cb = texture2D(uTexB, uv);
    if (progress <= 0.0) return ca;
    if (progress >= 1.0) return cb;

    float t = clamp(progress, 0.0, 1.0);

    if (p_mode < 0.5) {
        // Swipe Up: smooth vertical stories swipe with parallax
        float yA = uv.y - t;
        float yB = uv.y + (1.0 - t);
        if (yA >= 0.0 && yA <= 1.0) {
            return texture2D(uTexA, vec2(uv.x, yA));
        } else {
            return texture2D(uTexB, vec2(uv.x, yB));
        }
    } else if (p_mode < 1.5) {
        // Stories Flip: 3D-like vertical card flip
        float angle = t * 3.14159265;
        if (t < 0.5) {
            float scaleY = cos(angle);
            vec2 newUv = vec2(uv.x, (uv.y - 0.5) / max(scaleY, 0.001) + 0.5);
            if (newUv.y < 0.0 || newUv.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);
            return texture2D(uTexA, newUv);
        } else {
            float scaleY = -cos(angle);
            vec2 newUv = vec2(uv.x, (uv.y - 0.5) / max(scaleY, 0.001) + 0.5);
            if (newUv.y < 0.0 || newUv.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);
            return texture2D(uTexB, newUv);
        }
    } else if (p_mode < 2.5) {
        // Heart Iris: expanding heart mask
        vec2 p = (uv - vec2(0.5, 0.45)) * 2.0;
        // Standard 2D heart formula: (x^2 + y^2 - 1)^3 - x^2 * y^3 <= 0
        p.y = -p.y;
        float a = atan(p.x, p.y) / 3.14159265;
        float r = length(p);
        float h = abs(a);
        float heartR = (1.0 - h) * 0.7 + h * 0.35 + 0.35 * sqrt(max(0.0, 1.0 - h * h));
        float radius = t * 2.0;
        if (r < radius * heartR) {
            return cb;
        } else {
            return ca;
        }
    } else if (p_mode < 3.5) {
        // Bubble Pop Wipe: circular expanding bubble
        vec2 center = vec2(0.5, 0.5);
        float d = distance(uv, center);
        float maxD = 0.7071; // sqrt(0.5^2 + 0.5^2)
        float edge = t * maxD * 1.1;
        float mask = smoothstep(edge - 0.02, edge, d);
        return mix(cb, ca, mask);
    } else {
        // Notification Slide: slide from top with dim
        float slide = smoothstep(0.0, 1.0, t);
        if (uv.y < slide) {
            vec2 newUv = vec2(uv.x, uv.y + (1.0 - slide));
            return texture2D(uTexB, newUv);
        } else {
            vec4 col = texture2D(uTexA, uv);
            col.rgb *= mix(1.0, 0.6, slide);
            return col;
        }
    }
}

