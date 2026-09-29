vec4 reclyEffect(vec2 uv) {
    vec2 center = vec2(p_center_x, p_center_y);
    vec2 aspect = vec2(uResolution.x / uResolution.y, 1.0);
    vec2 delta = (uv - center) * aspect;
    float dist = length(delta);
    vec2 warped = uv;
    
    if (p_mode < 0.5) {
        // Bulge: magnifying spherical lens curvature
        if (dist < p_radius) {
            float percent = dist / p_radius;
            float r = percent * (1.0 + (percent - 1.0) * p_amount);
            warped = center + (delta / aspect) * (r / max(percent, 0.0001));
        }
    } else if (p_mode < 1.5) {
        // Pinch: concave inward funnel suction
        if (dist < p_radius) {
            float percent = dist / p_radius;
            float r = pow(percent, 1.0 + p_amount * 2.0);
            warped = center + (delta / aspect) * (r / max(percent, 0.0001));
        }
    } else if (p_mode < 2.5) {
        // Heat Haze: vertical turbulent heat shimmer
        float wave1 = sin(uv.y * 35.0 - uTime * p_speed * 4.0);
        float wave2 = cos(uv.x * 25.0 + uTime * p_speed * 3.0);
        vec2 offset = vec2(wave1 * wave2 * 0.015 * p_amount, wave1 * 0.01 * p_amount);
        warped = uv + offset;
    } else if (p_mode < 3.5) {
        // Water Distortion: multi-frequency fluid ripples & caustics
        float waveX = sin(uv.y * 20.0 + uTime * p_speed * 2.5) + sin(uv.x * 15.0 - uTime * p_speed * 1.8);
        float waveY = cos(uv.x * 22.0 - uTime * p_speed * 2.0) + cos(uv.y * 18.0 + uTime * p_speed * 3.0);
        vec2 offset = vec2(waveX, waveY) * 0.012 * p_amount;
        warped = uv + offset;
    } else if (p_mode < 4.5) {
        // Twirl: vortex swirl rotation around center
        if (dist < p_radius) {
            float angle = (1.0 - dist / p_radius) * p_amount * 3.14159;
            float c = cos(angle); float s = sin(angle);
            vec2 rotated = mat2(c, -s, s, c) * delta;
            warped = center + rotated / aspect;
        }
    } else {
        // Turbulence Distortion: multi-octave pseudo-noise displacement
        float n1 = sin(uv.x * 12.0 + uTime * p_speed) * cos(uv.y * 12.0 + uTime * p_speed);
        float n2 = sin(uv.x * 24.0 - uTime * p_speed * 1.5) * cos(uv.y * 24.0 + uTime * p_speed * 1.5);
        vec2 disp = vec2(n1 + n2 * 0.5, n2 - n1 * 0.5) * 0.02 * p_amount;
        warped = uv + disp;
    }

    return texture2D(uTexSampler, clamp(warped, 0.0, 1.0));
}
