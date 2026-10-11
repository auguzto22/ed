// Colour-channel transitions: the red, green and blue records of the picture separate like a
// misregistered print, peak at the cut and lock back into register on the next shot.

const float PI = 3.14159265;

// exp(-x^2) without pow(): pow() is undefined for negative bases on many mobile GPUs.
float gauss(float x) { return exp(-x * x); }

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }
vec2 mirrorUv(vec2 uv) { return 1.0 - abs(1.0 - mod(uv, 2.0)); }

vec4 frameAt(vec2 uv, float w) {
    vec2 q = mirrorUv(uv);
    return mix(texture2D(uTexA, q), texture2D(uTexB, q), w);
}

vec4 channels(vec2 r, vec2 g, vec2 b, float w) {
    vec4 cg = frameAt(g, w);
    return vec4(frameAt(r, w).r, cg.g, frameAt(b, w).b, cg.a);
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float amount = clamp(p_intensity, 0.0, 3.0);
    float env = sin(PI * t);
    env *= env;
    float k = env * amount;
    float w = smoothstep(0.42, 0.58, t);
    float asp = aspectRatio();

    if (mode < 0.5) {
        // RGB split: red and blue slide apart horizontally.
        vec2 d = vec2(0.03 * k, 0.0);
        return channels(uv + d, uv, uv - d, w);
    }
    if (mode < 1.5) {
        // RGB shift: channels drift on different diagonals and scales.
        vec2 c = uv - 0.5;
        return channels(0.5 + c * (1.0 + 0.03 * k) + vec2(0.012, 0.02) * k,
                        uv,
                        0.5 + c * (1.0 - 0.03 * k) - vec2(0.012, 0.02) * k, w);
    }
    if (mode < 2.5) {
        // Chromatic drift: radial aberration that grows towards the edges and slowly rotates.
        vec2 p = (uv - 0.5) * vec2(asp, 1.0);
        float a = t * 2.0;
        vec2 swirl = vec2(cos(a), sin(a)) * 0.006 * k;
        vec2 dr = (p * (1.0 + 0.05 * k) + swirl) / vec2(asp, 1.0) + 0.5;
        vec2 db = (p * (1.0 - 0.05 * k) - swirl) / vec2(asp, 1.0) + 0.5;
        return channels(dr, uv, db, w);
    }
    if (mode < 3.5) {
        // Channel shear: each channel leans by a different amount, like a rolling shutter.
        float y = uv.y - 0.5;
        return channels(uv + vec2(y * 0.12 * k, 0.0), uv, uv - vec2(y * 0.12 * k, 0.0), w);
    }
    // Prism wipe: a wipe whose edge disperses into a rainbow.
    float edge = mix(-0.15, 1.15, t);
    float x = uv.x + (uv.y - 0.5) * 0.15;
    float spread = 0.03 + 0.012 * amount;
    float mr = 1.0 - smoothstep(edge - spread * 1.5, edge - spread * 0.5, x);
    float mg = 1.0 - smoothstep(edge - spread * 0.5, edge + spread * 0.5, x);
    float mb = 1.0 - smoothstep(edge + spread * 0.5, edge + spread * 1.5, x);
    vec4 a = texture2D(uTexA, uv);
    vec4 b = texture2D(uTexB, uv);
    vec3 col = vec3(mix(a.r, b.r, mr), mix(a.g, b.g, mg), mix(a.b, b.b, mb));
    // Light refracted at the edge.
    float glow = gauss((x - edge) / (spread * 0.6)) * 0.12 * amount;
    return vec4(col + glow, mix(a.a, b.a, mg));
}
