vec4 reclyEffect(vec2 uv) {
    vec2 center = vec2(p_center_x, p_center_y);
    vec2 delta = uv - center;
    float t = uProgress;
    float scale = 1.0;
    float angle = 0.0;
    
    if (p_mode < 0.5) {
        // Zoom In: progressive smooth expansion
        scale = 1.0 + p_amount * (0.5 - 0.5 * cos(t * 3.1415926));
    } else if (p_mode < 1.5) {
        // Zoom Out: progressive contraction
        scale = 1.0 + p_amount * (1.0 - (0.5 - 0.5 * cos(t * 3.1415926)));
    } else if (p_mode < 2.5) {
        // 3D Zoom: perspective keystoning / trapezoidal tilt
        float tilt = sin(uTime * p_speed) * p_amount * 0.4;
        delta.x *= 1.0 + delta.y * tilt;
        scale = 1.0 + p_amount * 0.3;
    } else if (p_mode < 3.5) {
        // Smooth Zoom: sine wave back-and-forth breathing
        scale = 1.0 + sin(uTime * p_speed * 3.1415926) * p_amount * 0.25;
    } else if (p_mode < 4.5) {
        // Dolly Zoom: vertigo focal warp
        float vertigo = (uv.y - 0.5) * p_amount * 0.5;
        scale = 1.0 + vertigo + sin(uTime * p_speed) * 0.15;
    } else if (p_mode < 5.5) {
        // Zoom Rotate: rotational twist + zoom
        angle = sin(uTime * p_speed) * p_rotation * 0.0174533;
        scale = 1.0 + p_amount * 0.3;
    } else if (p_mode < 6.5) {
        // Zoom Blur Motion: zoom + chromatic motion streaks
        scale = 1.0 + sin(uTime * p_speed) * p_amount * 0.2;
        vec2 streak = delta * (p_amount * 0.04);
        vec4 base = texture2D(uTexSampler, clamp(center + delta / max(scale, 0.01), 0.0, 1.0));
        vec4 rCol = texture2D(uTexSampler, clamp(center + (delta + streak) / max(scale, 0.01), 0.0, 1.0));
        vec4 bCol = texture2D(uTexSampler, clamp(center + (delta - streak) / max(scale, 0.01), 0.0, 1.0));
        return vec4(rCol.r, base.g, bCol.b, base.a);
    } else {
        // Elastic Zoom: spring bounce overshoot
        float tau = t * 6.28318;
        float decay = exp(-t * 4.0);
        scale = 1.0 + p_amount * cos(tau * p_speed) * decay;
    }

    if (abs(angle) > 0.0001) {
        float c = cos(angle); float s = sin(angle);
        delta = mat2(c, -s, s, c) * delta;
    }

    vec2 warped = center + delta / max(scale, 0.01);
    return texture2D(uTexSampler, clamp(warped, 0.0, 1.0));
}
