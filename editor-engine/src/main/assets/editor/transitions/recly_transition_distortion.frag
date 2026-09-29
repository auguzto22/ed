vec4 reclyTransition(vec2 uv, float progress) {
    if (progress <= 0.0) return texture2D(uTexA, uv);
    if (progress >= 1.0) return texture2D(uTexB, uv);

    float mode = floor(p_mode + 0.5);
    float t = clamp(progress, 0.0, 1.0);
    float peak = sin(t * 3.14159265);
    float strength = (p_strength > 0.05 ? p_strength : 1.0) * peak;
    float aspect = uResolution.x / max(uResolution.y, 1.0);

    vec2 p = uv - 0.5;
    vec2 uvDistort = uv;

    if (mode < 0.5) {
        // Ripple Transition: concentric water waves
        vec2 pAspect = vec2(p.x * aspect, p.y);
        float dist = length(pAspect);
        float wave = sin(dist * 35.0 - t * 25.0) * 0.04 * strength;
        vec2 norm = dist > 0.001 ? normalize(pAspect) : vec2(0.0);
        norm.x /= aspect;
        uvDistort = uv + norm * wave;
    } else if (mode < 1.5) {
        // Wave Transition: sine waves in X and Y
        float waveX = sin(uv.y * 20.0 + t * 15.0) * 0.05 * strength;
        float waveY = cos(uv.x * 20.0 + t * 15.0) * 0.05 * strength;
        uvDistort = uv + vec2(waveX, waveY);
    } else if (mode < 2.5) {
        // Fisheye Transition
        vec2 pAspect = vec2(p.x * aspect, p.y);
        float r = length(pAspect);
        float bind = 0.5;
        if (r < bind) {
            float theta = atan(pAspect.y, pAspect.x);
            float rDist = pow(r / bind, 1.0 + strength * 1.5) * bind;
            uvDistort = vec2(rDist * cos(theta) / aspect, rDist * sin(theta)) + 0.5;
        }
    } else if (mode < 3.5) {
        // Warp / Twirl Transition
        vec2 pAspect = vec2(p.x * aspect, p.y);
        float r = length(pAspect);
        float angle = atan(pAspect.y, pAspect.x) + (1.0 - smoothstep(0.0, 0.5, r)) * strength * 4.0;
        uvDistort = vec2(r * cos(angle) / aspect, r * sin(angle)) + 0.5;
    } else {
        // Lens Distortion: barrel to pincushion
        float r2 = p.x * p.x + p.y * p.y;
        uvDistort = uv + p * (r2 * strength * 1.2);
    }

    vec4 ca = texture2D(uTexA, clamp(uvDistort, 0.0, 1.0));
    vec4 cb = texture2D(uTexB, clamp(uvDistort, 0.0, 1.0));
    return mix(ca, cb, smoothstep(0.4, 0.6, t));
}
