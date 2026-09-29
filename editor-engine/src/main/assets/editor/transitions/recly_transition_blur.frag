vec4 blurSample(sampler2D tex, vec2 uv, vec2 dir, float radius) {
    vec4 sum = vec4(0.0);
    vec2 step = dir * (radius / uResolution);
    sum += texture2D(tex, uv - step * 3.0) * 0.05;
    sum += texture2D(tex, uv - step * 2.0) * 0.12;
    sum += texture2D(tex, uv - step * 1.0) * 0.22;
    sum += texture2D(tex, uv) * 0.32;
    sum += texture2D(tex, uv + step * 1.0) * 0.22;
    sum += texture2D(tex, uv + step * 2.0) * 0.12;
    sum += texture2D(tex, uv + step * 3.0) * 0.05;
    return sum;
}

vec4 spinBlurSample(sampler2D tex, vec2 uv, float strength) {
    vec4 sum = vec4(0.0);
    vec2 center = vec2(0.5, 0.5);
    vec2 toCenter = uv - center;
    vec2 tangent = vec2(-toCenter.y, toCenter.x);
    for (int i = -3; i <= 3; i++) {
        float factor = float(i) / 3.0 * strength;
        sum += texture2D(tex, clamp(uv + tangent * factor, 0.0, 1.0));
    }
    return sum / 7.0;
}

vec4 zoomBlurSample(sampler2D tex, vec2 uv, float strength) {
    vec4 sum = vec4(0.0);
    vec2 center = vec2(0.5, 0.5);
    vec2 toCenter = center - uv;
    for (int i = 0; i < 8; i++) {
        float factor = float(i) / 7.0 * strength;
        sum += texture2D(tex, clamp(uv + toCenter * factor, 0.0, 1.0));
    }
    return sum / 8.0;
}

vec4 reclyTransition(vec2 uv, float progress) {
    if (progress <= 0.0) return texture2D(uTexA, uv);
    if (progress >= 1.0) return texture2D(uTexB, uv);

    float mode = floor(p_mode + 0.5);
    float t = clamp(progress, 0.0, 1.0);
    // Blur peaks at progress = 0.5
    float blurCurve = 1.0 - abs(t - 0.5) * 2.0;
    float maxRadius = (p_radius > 0.1 ? p_radius : 16.0) * blurCurve;

    vec4 ca, cb;
    if (mode < 0.5) {
        // Blur Dissolve: broad horizontal motion blur around the dissolve.
        ca = blurSample(uTexA, uv, vec2(1.0, 0.0), maxRadius * 1.35);
        cb = blurSample(uTexB, uv, vec2(-1.0, 0.0), maxRadius * 1.35);
    } else if (mode < 1.5) {
        // Gaussian Blur (both axes)
        vec4 caH = blurSample(uTexA, uv, vec2(1.0, 0.0), maxRadius);
        vec4 caV = blurSample(uTexA, uv, vec2(0.0, 1.0), maxRadius);
        ca = mix(caH, caV, 0.5);
        vec4 cbH = blurSample(uTexB, uv, vec2(1.0, 0.0), maxRadius);
        vec4 cbV = blurSample(uTexB, uv, vec2(0.0, 1.0), maxRadius);
        cb = mix(cbH, cbV, 0.5);
    } else if (mode < 2.5) {
        // Directional Blur
        vec2 dir = vec2(cos(p_angle), sin(p_angle));
        ca = blurSample(uTexA, uv, dir, maxRadius * 1.5);
        cb = blurSample(uTexB, uv, dir, maxRadius * 1.5);
    } else if (mode < 3.5) {
        // Radial / Spin Blur (mode 3)
        float str = maxRadius * 0.015;
        ca = spinBlurSample(uTexA, uv, str);
        cb = spinBlurSample(uTexB, uv, str);
    } else {
        // Zoom Blur (mode 4)
        float str = maxRadius * 0.02;
        ca = zoomBlurSample(uTexA, uv, str);
        cb = zoomBlurSample(uTexB, uv, str);
    }

    return mix(ca, cb, smoothstep(0.4, 0.6, t));
}
