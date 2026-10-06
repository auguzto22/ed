vec4 reclyTransition(vec2 uv, float progress) {
    vec4 ca = texture2D(uTexA, uv);
    vec4 cb = texture2D(uTexB, uv);
    if (progress <= 0.0) return ca;
    if (progress >= 1.0) return cb;

    float t = clamp(progress, 0.0, 1.0);
    float amount = sin(t * 3.14159265);

    if (p_mode < 0.5) {
        // Film Burn: warm overexposure burn dissolve
        vec4 burnColor = vec4(1.0, 0.72, 0.35, 1.0);
        float burnIntensity = amount * 1.5 * p_intensity;
        vec4 blended = mix(ca, cb, t);
        blended.rgb += burnColor.rgb * burnIntensity * (0.8 + 0.2 * sin(uv.x * 20.0 + uv.y * 30.0 + t * 10.0));
        return clamp(blended, 0.0, 1.0);
    } else if (p_mode < 1.5) {
        // Film Roll: vertical frame roll with jitter
        float jitter = (fract(sin(dot(uv + t, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) * 0.02 * amount;
        float y = fract(uv.y + t + jitter);
        vec2 rolledUv = vec2(uv.x, y);
        if (t < 0.5) {
            vec4 col = texture2D(uTexA, rolledUv);
            return mix(col, vec4(0.05, 0.05, 0.05, 1.0), smoothstep(0.48, 0.5, t));
        } else {
            vec4 col = texture2D(uTexB, rolledUv);
            return mix(vec4(0.05, 0.05, 0.05, 1.0), col, smoothstep(0.5, 0.52, t));
        }
    } else if (p_mode < 2.5) {
        // Leader Flash: vintage projector countdown flash
        float flash = amount * 2.0;
        vec4 base = mix(ca, cb, smoothstep(0.45, 0.55, t));
        vec3 sepia = vec3(
            dot(base.rgb, vec3(0.393, 0.769, 0.189)),
            dot(base.rgb, vec3(0.349, 0.686, 0.168)),
            dot(base.rgb, vec3(0.272, 0.534, 0.131))
        );
        vec3 res = mix(base.rgb, sepia, amount * 0.5);
        res += vec3(flash * 0.3);
        return vec4(clamp(res, 0.0, 1.0), base.a);
    } else if (p_mode < 3.5) {
        // Sprocket Slip: horizontal frame displacement
        float slip = (1.0 - abs(t - 0.5) * 2.0) * 0.15;
        vec2 uvA = vec2(uv.x + slip, uv.y);
        vec2 uvB = vec2(uv.x - (0.15 - slip), uv.y);
        return mix(texture2D(uTexA, uvA), texture2D(uTexB, uvB), t);
    } else {
        // Burn Dissolve: organic burn hole reveal
        float noise = fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453);
        float threshold = t * 1.2 - 0.1;
        float dist = distance(uv, vec2(0.5, 0.5)) + (noise - 0.5) * 0.2;
        if (dist < threshold) {
            float edge = smoothstep(threshold - 0.05, threshold, dist);
            vec4 glow = vec4(1.0, 0.4, 0.1, 1.0);
            return mix(cb, glow, (1.0 - edge) * amount);
        } else {
            return ca;
        }
    }
}

