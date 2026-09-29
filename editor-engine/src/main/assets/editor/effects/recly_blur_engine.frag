vec4 reclyEffect(vec2 uv) {
    vec2 center = vec2(p_center_x, p_center_y);
    vec2 aspect = vec2(uResolution.x / uResolution.y, 1.0);
    vec2 d = uv - center;
    vec2 dAspect = d * aspect;
    float dist = length(dAspect);
    
    float blurMask = 1.0;
    if (p_mode > 0.5 && p_mode < 1.5) {
        // Background Blur: keep center area sharp, blur edges
        blurMask = smoothstep(0.15, 0.55, dist);
    } else if (p_mode > 2.5 && p_mode < 3.5) {
        // Tilt Shift: horizontal/angled sharp band
        float c = cos(p_angle); float s = sin(p_angle);
        float lineDist = abs(dAspect.x * s + dAspect.y * c);
        blurMask = smoothstep(0.08, 0.35, lineDist);
    } else if (p_mode > 3.5 && p_mode < 4.5) {
        // Focus Pull: animated focal breathing over time or manual slider
        float focalProgress = fract(uTime * 0.25);
        blurMask = abs(dist - focalProgress * 0.8) * 2.5;
        blurMask = clamp(blurMask, 0.0, 1.0);
    }

    vec4 color = vec4(0.0);
    float effectiveRadius = p_radius * blurMask * p_amount;
    
    if (p_mode < 0.5) {
        // Radial / Spin Blur
        vec2 tangent = vec2(-dAspect.y, dAspect.x) / max(dist, 0.001);
        tangent /= aspect;
        vec2 stepSize = tangent * (effectiveRadius / uResolution.x) * 0.25;
        color += texture2D(uTexSampler, clamp(uv - stepSize * 3.0, 0.0, 1.0)) * 0.09;
        color += texture2D(uTexSampler, clamp(uv - stepSize * 2.0, 0.0, 1.0)) * 0.12;
        color += texture2D(uTexSampler, clamp(uv - stepSize, 0.0, 1.0)) * 0.18;
        color += texture2D(uTexSampler, uv) * 0.22;
        color += texture2D(uTexSampler, clamp(uv + stepSize, 0.0, 1.0)) * 0.18;
        color += texture2D(uTexSampler, clamp(uv + stepSize * 2.0, 0.0, 1.0)) * 0.12;
        color += texture2D(uTexSampler, clamp(uv + stepSize * 3.0, 0.0, 1.0)) * 0.09;
    } else if (p_mode > 4.5) {
        // Speed Blur: hybrid directional streak + radial
        vec2 dir = vec2(cos(p_angle), sin(p_angle)) * (effectiveRadius / uResolution);
        vec2 rad = (center - uv) * (effectiveRadius * 0.015);
        vec2 stepSize = dir + rad;
        color += texture2D(uTexSampler, clamp(uv - stepSize * 1.5, 0.0, 1.0)) * 0.15;
        color += texture2D(uTexSampler, clamp(uv - stepSize * 0.75, 0.0, 1.0)) * 0.22;
        color += texture2D(uTexSampler, uv) * 0.26;
        color += texture2D(uTexSampler, clamp(uv + stepSize * 0.75, 0.0, 1.0)) * 0.22;
        color += texture2D(uTexSampler, clamp(uv + stepSize * 1.5, 0.0, 1.0)) * 0.15;
    } else {
        // Lens / Bokeh / Area Blur (hexagonal sampling kernel)
        vec2 r = effectiveRadius / uResolution;
        color += texture2D(uTexSampler, uv) * 0.24;
        color += texture2D(uTexSampler, clamp(uv + vec2(r.x, 0.0), 0.0, 1.0)) * 0.13;
        color += texture2D(uTexSampler, clamp(uv - vec2(r.x, 0.0), 0.0, 1.0)) * 0.13;
        color += texture2D(uTexSampler, clamp(uv + vec2(0.5 * r.x, 0.866 * r.y), 0.0, 1.0)) * 0.13;
        color += texture2D(uTexSampler, clamp(uv - vec2(0.5 * r.x, 0.866 * r.y), 0.0, 1.0)) * 0.13;
        color += texture2D(uTexSampler, clamp(uv + vec2(-0.5 * r.x, 0.866 * r.y), 0.0, 1.0)) * 0.12;
        color += texture2D(uTexSampler, clamp(uv - vec2(-0.5 * r.x, 0.866 * r.y), 0.0, 1.0)) * 0.12;
    }
    
    return color;
}
