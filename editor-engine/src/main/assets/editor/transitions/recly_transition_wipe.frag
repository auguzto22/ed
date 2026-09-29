vec4 reclyTransition(vec2 uv, float progress) {
    vec4 ca = texture2D(uTexA, uv);
    vec4 cb = texture2D(uTexB, uv);
    if (progress <= 0.0) return ca;
    if (progress >= 1.0) return cb;

    float mode = floor(p_mode + 0.5);
    float softness = max(p_softness, 0.001);
    float t = clamp(progress, 0.0, 1.0);
    float tMapped = mix(-softness, 1.0 + softness, t);
    float mask = 0.0;

    if (mode < 0.5) {
        // Wipe Left (progress moves from left to right)
        mask = smoothstep(uv.x - softness, uv.x + softness, tMapped);
    } else if (mode < 1.5) {
        // Wipe Right
        mask = smoothstep((1.0 - uv.x) - softness, (1.0 - uv.x) + softness, tMapped);
    } else if (mode < 2.5) {
        // Wipe Up
        mask = smoothstep(uv.y - softness, uv.y + softness, tMapped);
    } else if (mode < 3.5) {
        // Wipe Down
        mask = smoothstep((1.0 - uv.y) - softness, (1.0 - uv.y) + softness, tMapped);
    } else if (mode < 4.5) {
        // Diagonal Wipe (top-left to bottom-right)
        float d = (uv.x + uv.y) * 0.5;
        mask = smoothstep(d - softness, d + softness, tMapped);
    } else if (mode < 5.5) {
        // Circle Wipe / Iris
        vec2 center = vec2(p_center_x > 0.0 ? p_center_x : 0.5, p_center_y > 0.0 ? p_center_y : 0.5);
        float aspect = uResolution.x / max(uResolution.y, 1.0);
        vec2 diff = uv - center;
        diff.x *= aspect;
        float dist = length(diff);
        float maxDist = length(vec2(0.5 * aspect, 0.5));
        float radius = mix(-softness, maxDist * 1.42 + softness, t);
        mask = 1.0 - smoothstep(radius - softness, radius + softness, dist);
    } else if (mode < 6.5) {
        // Radial Wipe (Clock sweep)
        vec2 center = vec2(p_center_x > 0.0 ? p_center_x : 0.5, p_center_y > 0.0 ? p_center_y : 0.5);
        vec2 p = uv - center;
        float angle = atan(p.y, p.x) + 3.14159265; // [0, 2pi]
        float normAngle = angle / (2.0 * 3.14159265);
        mask = smoothstep(normAngle - softness, normAngle + softness, tMapped);
    } else {
        // Soft Wipe (linear smooth gradient)
        float softWipeEdge = mix(-0.2, 1.2, t);
        mask = 1.0 - smoothstep(softWipeEdge - 0.2, softWipeEdge + 0.2, uv.x);
    }

    return mix(ca, cb, clamp(mask, 0.0, 1.0));
}
