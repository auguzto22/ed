// Luma transitions. Luma fade reveals the incoming clip through the outgoing clip's own
// brightness (highlights first, or shadows first when inverted). Luma wipe uses an organic
// ink-like matte. Mattes are lightly smoothed so edges do not crawl on fine detail.

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
               mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 5; i++) {
        v += valueNoise(p) * amp;
        p = p * 2.07 + vec2(11.3, 5.9);
        amp *= 0.5;
    }
    return v;
}

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float smoothLuma(vec2 uv) {
    vec2 d = 1.5 / max(uResolution, vec2(1.0)) * min(uResolution.x, uResolution.y) / 400.0;
    float l = luma(texture2D(uTexA, uv).rgb) * 0.4;
    l += luma(texture2D(uTexA, clamp(uv + vec2(d.x, 0.0), 0.0, 1.0)).rgb) * 0.15;
    l += luma(texture2D(uTexA, clamp(uv - vec2(d.x, 0.0), 0.0, 1.0)).rgb) * 0.15;
    l += luma(texture2D(uTexA, clamp(uv + vec2(0.0, d.y), 0.0, 1.0)).rgb) * 0.15;
    l += luma(texture2D(uTexA, clamp(uv - vec2(0.0, d.y), 0.0, 1.0)).rgb) * 0.15;
    return l;
}

vec4 reclyTransition(vec2 uv, float progress) {
    vec4 a = texture2D(uTexA, uv);
    vec4 b = texture2D(uTexB, uv);
    float t = clamp(progress, 0.0, 1.0);
    float soft = clamp(p_softness, 0.01, 0.5);
    float asp = uResolution.x / max(uResolution.y, 1.0);
    float matte;
    if (p_mode < 0.5) {
        matte = 1.0 - smoothLuma(uv);
    } else {
        // A left-to-right sweep broken up by ink-like noise, normalised to 0..1 so the reveal
        // advances evenly over the whole duration.
        vec2 p = (uv - 0.5) * vec2(asp, 1.0);
        float sweep = uv.x * 0.8 + uv.y * 0.2;
        matte = (sweep + (fbm(p * 3.0 + vec2(4.2, 1.3)) - 0.5) * 0.5 + 0.25) / 1.5;
    }
    if (p_invert > 0.5) matte = 1.0 - matte;
    // B appears where the matte is below a threshold that sweeps past both ends.
    float threshold = mix(-soft, 1.0 + soft, t);
    float m = 1.0 - smoothstep(threshold - soft, threshold + soft, matte);
    return mix(a, b, m);
}
