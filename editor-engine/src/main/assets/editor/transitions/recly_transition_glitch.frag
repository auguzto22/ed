float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec4 reclyTransition(vec2 uv, float progress) {
    if (progress <= 0.0) return texture2D(uTexA, uv);
    if (progress >= 1.0) return texture2D(uTexB, uv);

    float mode = floor(p_mode + 0.5);
    float t = clamp(progress, 0.0, 1.0);
    float intensity = (p_intensity > 0.05 ? p_intensity : 1.0) * sin(t * 3.14159265);

    vec2 uvA = uv;
    vec2 uvB = uv;

    if (mode < 0.5) {
        // Digital Glitch: horizontal block displacement
        float blockY = floor(uv.y * 24.0);
        float noise = hash(vec2(blockY, floor(t * 15.0))) * 2.0 - 1.0;
        float displace = step(0.65, abs(noise)) * noise * 0.15 * intensity;
        uvA.x += displace;
        uvB.x -= displace;
    } else if (mode < 1.5) {
        // RGB Glitch: chromatic channel split
        float splitDist = 0.04 * intensity;
        vec4 rA = texture2D(uTexA, uv + vec2(splitDist, 0.0));
        vec4 gA = texture2D(uTexA, uv);
        vec4 bA = texture2D(uTexA, uv - vec2(splitDist, 0.0));
        vec4 colA = vec4(rA.r, gA.g, bA.b, gA.a);

        vec4 rB = texture2D(uTexB, uv + vec2(splitDist, 0.0));
        vec4 gB = texture2D(uTexB, uv);
        vec4 bB = texture2D(uTexB, uv - vec2(splitDist, 0.0));
        vec4 colB = vec4(rB.r, gB.g, bB.b, gB.a);

        return mix(colA, colB, smoothstep(0.4, 0.6, t));
    } else if (mode < 2.5) {
        // Signal Glitch: wave disturbance and scanline jitter
        float wave = sin(uv.y * 80.0 + t * 30.0) * 0.02 * intensity;
        uvA.x += wave;
        uvB.x += wave;
    } else {
        // VHS Glitch: tracking lines and band displacement
        float lineY = fract(t * 5.0);
        float band = smoothstep(0.08, 0.0, abs(uv.y - lineY)) * 0.1 * intensity;
        uvA.x += band;
        uvB.x -= band;
    }

    vec4 ca = texture2D(uTexA, clamp(uvA, 0.0, 1.0));
    vec4 cb = texture2D(uTexB, clamp(uvB, 0.0, 1.0));
    return mix(ca, cb, smoothstep(0.4, 0.6, t));
}
