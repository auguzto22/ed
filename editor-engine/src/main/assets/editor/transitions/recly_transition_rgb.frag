vec4 reclyTransition(vec2 uv, float progress) {
    vec4 ca = texture2D(uTexA, uv);
    vec4 cb = texture2D(uTexB, uv);
    if (progress <= 0.0) return ca;
    if (progress >= 1.0) return cb;

    float t = clamp(progress, 0.0, 1.0);
    float amount = sin(t * 3.14159265);

    if (p_mode < 0.5) {
        // RGB Split: shift red left and blue right
        float offset = amount * 0.06 * p_intensity;
        vec4 colA = vec4(
            texture2D(uTexA, uv - vec2(offset, 0.0)).r,
            texture2D(uTexA, uv).g,
            texture2D(uTexA, uv + vec2(offset, 0.0)).b,
            ca.a
        );
        vec4 colB = vec4(
            texture2D(uTexB, uv + vec2(offset, 0.0)).r,
            texture2D(uTexB, uv).g,
            texture2D(uTexB, uv - vec2(offset, 0.0)).b,
            cb.a
        );
        return mix(colA, colB, t);
    } else if (p_mode < 1.5) {
        // RGB Shift: vertical chromatic drift
        float offset = amount * 0.05 * p_intensity;
        vec2 dir = vec2(offset * 0.7, offset);
        vec4 colA = vec4(
            texture2D(uTexA, uv + dir).r,
            texture2D(uTexA, uv).g,
            texture2D(uTexA, uv - dir).b,
            ca.a
        );
        vec4 colB = vec4(
            texture2D(uTexB, uv - dir).r,
            texture2D(uTexB, uv).g,
            texture2D(uTexB, uv + dir).b,
            cb.a
        );
        return mix(colA, colB, t);
    } else if (p_mode < 2.5) {
        // Chromatic Drift: radial chromatic separation
        vec2 center = vec2(0.5, 0.5);
        vec2 delta = uv - center;
        float dist = length(delta);
        vec2 dir = dist > 0.001 ? delta / dist : vec2(0.0);
        float offset = amount * 0.08 * p_intensity * dist;
        vec4 colA = vec4(
            texture2D(uTexA, uv + dir * offset).r,
            texture2D(uTexA, uv).g,
            texture2D(uTexA, uv - dir * offset).b,
            ca.a
        );
        vec4 colB = vec4(
            texture2D(uTexB, uv - dir * offset).r,
            texture2D(uTexB, uv).g,
            texture2D(uTexB, uv + dir * offset).b,
            cb.a
        );
        return mix(colA, colB, t);
    } else if (p_mode < 3.5) {
        // Channel Shear: diagonal chromatic displacement
        float angle = 0.785398; // 45 degrees
        vec2 shear = vec2(cos(angle), sin(angle)) * (amount * 0.07 * p_intensity);
        vec4 colA = vec4(
            texture2D(uTexA, uv + shear).r,
            texture2D(uTexA, uv).g,
            texture2D(uTexA, uv - shear).b,
            ca.a
        );
        vec4 colB = vec4(
            texture2D(uTexB, uv - shear).r,
            texture2D(uTexB, uv).g,
            texture2D(uTexB, uv + shear).b,
            cb.a
        );
        return mix(colA, colB, t);
    } else {
        // Prism Wipe: chromatic fringe along moving wipe edge
        float edge = t;
        float dist = uv.x - edge;
        float fringe = smoothstep(-0.08, 0.08, dist);
        float shift = (1.0 - abs(dist * 12.0)) * 0.03 * p_intensity;
        shift = max(0.0, shift);
        vec4 col = mix(
            vec4(texture2D(uTexB, uv + vec2(shift, 0.0)).r, texture2D(uTexB, uv).g, texture2D(uTexB, uv - vec2(shift, 0.0)).b, cb.a),
            vec4(texture2D(uTexA, uv - vec2(shift, 0.0)).r, texture2D(uTexA, uv).g, texture2D(uTexA, uv + vec2(shift, 0.0)).b, ca.a),
            fringe
        );
        return col;
    }
}

