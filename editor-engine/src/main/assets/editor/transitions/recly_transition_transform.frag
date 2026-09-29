vec2 rotateUv(vec2 uv, float angle) {
    float s = sin(angle);
    float c = cos(angle);
    vec2 p = uv - 0.5;
    return vec2(p.x * c - p.y * s, p.x * s + p.y * c) + 0.5;
}

vec4 reclyTransition(vec2 uv, float progress) {
    if (progress <= 0.0) return texture2D(uTexA, uv);
    if (progress >= 1.0) return texture2D(uTexB, uv);

    float mode = floor(p_mode + 0.5);
    float t = clamp(progress, 0.0, 1.0);

    // --- SLIDE (0..3): B slides over A ---
    if (mode < 3.5) {
        vec2 offset = vec2(0.0);
        if (mode < 0.5) offset = vec2(1.0 - t, 0.0);       // Slide Left
        else if (mode < 1.5) offset = vec2(t - 1.0, 0.0);  // Slide Right
        else if (mode < 2.5) offset = vec2(0.0, 1.0 - t);  // Slide Up
        else offset = vec2(0.0, t - 1.0);                  // Slide Down

        vec2 uvB = uv - offset;
        if (uvB.x >= 0.0 && uvB.x <= 1.0 && uvB.y >= 0.0 && uvB.y <= 1.0) {
            vec4 colB = texture2D(uTexB, uvB);
            return colB;
        }
        return texture2D(uTexA, uv);
    }

    // --- PUSH (4..7): B pushes A out ---
    if (mode < 7.5) {
        vec2 dir = vec2(0.0);
        if (mode < 4.5) dir = vec2(-1.0, 0.0);       // Push Left
        else if (mode < 5.5) dir = vec2(1.0, 0.0);   // Push Right
        else if (mode < 6.5) dir = vec2(0.0, -1.0);  // Push Up
        else dir = vec2(0.0, 1.0);                   // Push Down

        vec2 uvA = uv - dir * t;
        vec2 uvB = uv + dir * (1.0 - t);

        if (uvB.x >= 0.0 && uvB.x <= 1.0 && uvB.y >= 0.0 && uvB.y <= 1.0) {
            return texture2D(uTexB, uvB);
        }
        if (uvA.x >= 0.0 && uvA.x <= 1.0 && uvA.y >= 0.0 && uvA.y <= 1.0) {
            return texture2D(uTexA, uvA);
        }
        return vec4(0.0, 0.0, 0.0, 1.0);
    }

    // --- ZOOM IN (8) ---
    if (mode < 8.5) {
        float scaleA = 1.0 + t * 2.0;
        float scaleB = 0.2 + t * 0.8;
        vec2 uvA = (uv - 0.5) / scaleA + 0.5;
        vec2 uvB = (uv - 0.5) / scaleB + 0.5;
        vec4 colA = texture2D(uTexA, clamp(uvA, 0.0, 1.0));
        vec4 colB = texture2D(uTexB, clamp(uvB, 0.0, 1.0));
        return mix(colA, colB, smoothstep(0.3, 0.7, t));
    }

    // --- ZOOM OUT (9) ---
    if (mode < 9.5) {
        float scaleA = 1.0 - t * 0.7;
        float scaleB = 2.5 - t * 1.5;
        vec2 uvA = (uv - 0.5) / max(scaleA, 0.05) + 0.5;
        vec2 uvB = (uv - 0.5) / max(scaleB, 0.05) + 0.5;
        vec4 colA = texture2D(uTexA, clamp(uvA, 0.0, 1.0));
        vec4 colB = texture2D(uTexB, clamp(uvB, 0.0, 1.0));
        return mix(colA, colB, smoothstep(0.3, 0.7, t));
    }

    // --- ZOOM THROUGH / PUNCH / SMOOTH (10..12) ---
    if (mode < 12.5) {
        float factor = (mode > 10.5 && mode < 11.5) ? 1.5 : 2.5; // punch vs through
        float scale = mix(1.0, factor, t);
        vec2 uvA = (uv - 0.5) / scale + 0.5;
        float scaleIn = mix(0.1, 1.0, t);
        vec2 uvB = (uv - 0.5) / max(scaleIn, 0.01) + 0.5;
        vec4 colA = texture2D(uTexA, clamp(uvA, 0.0, 1.0));
        vec4 colB = texture2D(uTexB, clamp(uvB, 0.0, 1.0));
        return mix(colA, colB, smoothstep(0.4, 0.6, t));
    }

    // --- ROTATE PUSH (16) ---
    if (mode > 15.5) {
        float tilt = sin(t * 3.14159) * 0.35;
        vec2 uvA = rotateUv(uv + vec2(t, 0.0), -tilt);
        vec2 uvB = rotateUv(uv - vec2(1.0 - t, 0.0), tilt);
        if (uvB.x >= 0.0 && uvB.x <= 1.0 && uvB.y >= 0.0 && uvB.y <= 1.0) {
            return texture2D(uTexB, uvB);
        }
        if (uvA.x >= 0.0 && uvA.x <= 1.0 && uvA.y >= 0.0 && uvA.y <= 1.0) {
            return texture2D(uTexA, uvA);
        }
        return vec4(0.0, 0.0, 0.0, 1.0);
    }

    // --- ZOOM ROTATE / SPIN / SPIN ZOOM (13..15) ---
    float maxAngle = (mode > 13.5) ? 6.28318 : 1.5708; // 360 deg spin vs 90 deg
    float intensity = (p_intensity > 0.05 ? p_intensity : 1.0);
    float angle = t * maxAngle * intensity;
    float scale = (mode > 14.5) ? mix(1.0, 0.3, sin(t * 3.14159)) : 1.0;
    vec2 p = (uv - 0.5) / max(scale, 0.05) + 0.5;
    vec2 rotUv = rotateUv(p, angle);
    vec4 colA = texture2D(uTexA, clamp(rotUv, 0.0, 1.0));
    vec4 colB = texture2D(uTexB, clamp(rotateUv(p, angle - maxAngle), 0.0, 1.0));
    return mix(colA, colB, smoothstep(0.45, 0.55, t));
}
