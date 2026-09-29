float noise2D(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec4 reclyTransition(vec2 uv, float progress) {
    vec4 ca = texture2D(uTexA, uv);
    vec4 cb = texture2D(uTexB, uv);
    if (progress <= 0.0) return ca;
    if (progress >= 1.0) return cb;

    float mode = floor(p_mode + 0.5);
    float t = clamp(progress, 0.0, 1.0);
    vec4 base = mix(ca, cb, smoothstep(0.4, 0.6, t));

    float intensity = p_intensity > 0.05 ? p_intensity : 1.0;
    // Peak light at progress = 0.5
    float lightCurve = sin(t * 3.14159265);

    if (mode < 0.5) {
        // White Flash: strong exposure flash preserving highlights
        float flash = pow(lightCurve, 1.5) * intensity;
        return clamp(base + vec4(vec3(flash), 0.0), 0.0, 1.0);
    } else if (mode < 1.5) {
        // Color Flash
        vec3 col = vec3(1.0, 0.4, 0.8);
        float flash = pow(lightCurve, 1.8) * intensity;
        return clamp(base + vec4(col * flash, 0.0), 0.0, 1.0);
    } else if (mode < 2.5) {
        // Light Leak (dynamic warm flare moving across screen)
        vec2 center = vec2(mix(-0.2, 1.2, t), 0.3 + sin(t * 3.0) * 0.2);
        float dist = length(uv - center);
        float leak = exp(-dist * 2.5) * lightCurve * intensity * 1.8;
        vec3 warm = vec3(1.0, 0.65, 0.25) * leak;
        return clamp(base + vec4(warm, 0.0), 0.0, 1.0);
    } else if (mode < 3.5) {
        // Film Burn
        float n = noise2D(uv * 8.0 + vec2(t * 4.0));
        float burnShape = smoothstep(0.4, 0.8, n * lightCurve * 1.4);
        vec3 burnColor = mix(vec3(1.0, 0.4, 0.1), vec3(1.0, 0.9, 0.7), burnShape);
        return clamp(base + vec4(burnColor * burnShape * intensity * 1.5, 0.0), 0.0, 1.0);
    } else {
        // Glow Flash: diffuse bloom flash
        float flash = pow(lightCurve, 1.2) * intensity;
        vec3 glow = (base.rgb * 0.8 + vec3(0.5)) * flash;
        return clamp(base + vec4(glow, 0.0), 0.0, 1.0);
    }
}
