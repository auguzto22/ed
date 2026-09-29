float rand(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec4 reclyTransition(vec2 uv, float progress) {
    if (progress <= 0.0) return texture2D(uTexA, uv);
    if (progress >= 1.0) return texture2D(uTexB, uv);

    float mode = floor(p_mode + 0.5);
    float t = clamp(progress, 0.0, 1.0);

    if (mode < 0.5) {
        // Split Reveal: four panels pull away from the center.
        vec2 quadrant = vec2(uv.x < 0.5 ? -1.0 : 1.0, uv.y < 0.5 ? -1.0 : 1.0);
        vec2 uvA = uv + quadrant * t * 0.5;
        if (uvA.x >= 0.0 && uvA.x <= 1.0 && uvA.y >= 0.0 && uvA.y <= 1.0) {
            return texture2D(uTexA, uvA);
        }
        return texture2D(uTexB, uv);
    } else if (mode < 2.5) {
        // Split Reveal / Horizontal (mode 1) / Vertical (mode 2)
        float open = t * 0.5;
        vec2 p = uv - 0.5;
        if (mode < 1.5) {
            // Horizontal split (left and right halves move apart)
            if (p.x < 0.0) {
                vec2 uvA = vec2(uv.x - open, uv.y);
                if (uvA.x >= 0.0) return texture2D(uTexA, uvA);
            } else {
                vec2 uvA = vec2(uv.x + open, uv.y);
                if (uvA.x <= 1.0) return texture2D(uTexA, uvA);
            }
        } else {
            // Vertical split (top and bottom halves move apart)
            if (p.y < 0.0) {
                vec2 uvA = vec2(uv.x, uv.y - open);
                if (uvA.y >= 0.0) return texture2D(uTexA, uvA);
            } else {
                vec2 uvA = vec2(uv.x, uv.y + open);
                if (uvA.y <= 1.0) return texture2D(uTexA, uvA);
            }
        }
        return texture2D(uTexB, uv);
    } else if (mode < 3.5) {
        // Mosaic Reveal: mosaic peaks at t = 0.5, undistorted at 0.0 and 1.0
        float peak = sin(t * 3.14159265);
        float blocks = mix(120.0, 16.0, peak);
        vec2 mUv = (peak > 0.01) ? (floor(uv * blocks) + 0.5) / blocks : uv;
        vec4 ca = texture2D(uTexA, clamp(mUv, 0.0, 1.0));
        vec4 cb = texture2D(uTexB, clamp(mUv, 0.0, 1.0));
        return mix(ca, cb, smoothstep(0.4, 0.6, t));
    } else if (mode < 4.5) {
        // Pixel Dissolve
        vec2 block = floor(uv * 60.0);
        float r = rand(block);
        return (r < t) ? texture2D(uTexB, uv) : texture2D(uTexA, uv);
    } else if (mode < 5.5) {
        // Prism Transition: chromatic split on both clips
        float split = sin(t * 3.14159265) * 0.04;
        vec4 rA = texture2D(uTexA, uv + vec2(split, 0.0));
        vec4 gA = texture2D(uTexA, uv);
        vec4 bA = texture2D(uTexA, uv - vec2(split, 0.0));
        vec4 colA = vec4(rA.r, gA.g, bA.b, 1.0);

        vec4 rB = texture2D(uTexB, uv + vec2(split, 0.0));
        vec4 gB = texture2D(uTexB, uv);
        vec4 bB = texture2D(uTexB, uv - vec2(split, 0.0));
        vec4 colB = vec4(rB.r, gB.g, bB.b, 1.0);

        return mix(colA, colB, smoothstep(0.4, 0.6, t));
    } else if (mode < 6.5) {
        // Mirror Transition: horizontal symmetry fold revealing Clip B
        float fold = abs(uv.x - 0.5) * 2.0;
        float foldLimit = 1.0 - t;
        if (fold > foldLimit) {
            return texture2D(uTexB, uv);
        } else {
            float mirrorDist = mix(0.0, abs(uv.x - 0.5), smoothstep(0.0, 0.3, t));
            vec2 mirrorUv = vec2(0.5 + (uv.x < 0.5 ? -mirrorDist : mirrorDist), uv.y);
            return texture2D(uTexA, mirrorUv);
        }
    } else if (mode < 7.5) {
        // Kaleidoscope Transition: modulated by sin curve so endpoints are clean
        float kIntensity = sin(t * 3.14159265);
        vec2 p = uv - 0.5;
        float a = atan(p.y, p.x);
        float r = length(p);
        float segments = 6.0;
        float kA = abs(mod(a, 6.28318 / segments) - 3.14159 / segments);
        vec2 kUv = mix(uv, vec2(cos(kA), sin(kA)) * r + 0.5, kIntensity);
        vec4 ca = texture2D(uTexA, clamp(kUv, 0.0, 1.0));
        vec4 cb = texture2D(uTexB, clamp(kUv, 0.0, 1.0));
        return mix(ca, cb, smoothstep(0.4, 0.6, t));
    } else {
        // Glass Transition: fluted glass refraction
        float glass = sin(uv.x * 50.0) * 0.02 * sin(t * 3.14159265);
        vec4 ca = texture2D(uTexA, uv + vec2(glass, 0.0));
        vec4 cb = texture2D(uTexB, uv - vec2(glass, 0.0));
        return mix(ca, cb, smoothstep(0.4, 0.6, t));
    }
}
