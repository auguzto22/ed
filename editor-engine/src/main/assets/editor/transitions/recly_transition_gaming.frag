// Gaming-style transitions: 8-bit pixelation, interlaced scanlines, an energy shockwave, a
// striped "level clear" wipe and a neon grid. Sizes are relative to the frame, so the preview and
// the export look the same at any resolution.

const float PI = 3.14159265;

// exp(-x^2) without pow(): pow() is undefined for negative bases on many mobile GPUs.
float gauss(float x) { return exp(-x * x); }

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float easeInOut(float t) { return t * t * (3.0 - 2.0 * t); }

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float asp = aspectRatio();
    vec2 p = (uv - 0.5) * vec2(asp, 1.0);
    vec4 a = texture2D(uTexA, uv);
    vec4 b = texture2D(uTexB, uv);

    if (mode < 0.5) {
        // Arcade pixels: the picture pixelates into big blocks with a reduced palette, swaps,
        // and resolves again. Block size steps like an old console.
        float env = sin(PI * t);
        float blocks = floor(mix(160.0, 12.0, env * env) / 4.0) * 4.0;
        vec2 grid = vec2(blocks * asp, blocks);
        vec2 q = (floor(uv * grid) + 0.5) / grid;
        vec4 col = t < 0.5 ? texture2D(uTexA, q) : texture2D(uTexB, q);
        float levels = mix(256.0, 5.0, smoothstep(0.2, 0.9, env));
        col.rgb = floor(col.rgb * levels + 0.5) / levels;
        return mix(t < 0.5 ? a : b, col, smoothstep(0.02, 0.2, env));
    }
    if (mode < 1.5) {
        // Interlaced scanlines: odd lines fill from the top, even lines from the bottom, behind a beam.
        float lines = floor(min(uResolution.x, uResolution.y) / 6.0);
        float row = floor(uv.y * lines);
        float odd = mod(row, 2.0);
        float y = odd > 0.5 ? 1.0 - uv.y : uv.y;
        float edge = mix(-0.05, 1.05, easeInOut(t));
        float m = step(y, edge);
        float beam = gauss((y - edge) / 0.012) * step(0.001, t) * step(t, 0.999);
        vec4 col = mix(a, b, m);
        col.rgb += vec3(0.4, 1.0, 0.9) * beam * 0.6;
        return col;
    }
    if (mode < 2.5) {
        // Energy blast: a shockwave ring expands from the centre, refracting the picture; B is inside.
        float amount = clamp(p_intensity, 0.0, 3.0);
        float maxR = length(vec2(asp, 1.0) * 0.5) + 0.1;
        float radius = mix(0.0, maxR, easeInOut(t));
        float r = length(p);
        float ringD = (r - radius) / 0.05;
        float ring = exp(-ringD * ringD);
        vec2 dir = r > 0.0001 ? p / r : vec2(0.0);
        vec2 q = (p - dir * ring * 0.03 * amount) / vec2(asp, 1.0) + 0.5;
        float inside = 1.0 - smoothstep(radius - 0.01, radius + 0.01, r);
        vec4 col = mix(texture2D(uTexA, clamp(q, 0.0, 1.0)), texture2D(uTexB, clamp(q, 0.0, 1.0)), inside);
        // A short flash as the blast leaves the centre, ramped in over a couple of frames.
        float flash = exp(-t * 14.0) * smoothstep(0.0, 0.05, t);
        vec3 energy = vec3(0.25, 1.0, 0.85) * ring * 1.2 + vec3(0.8, 1.0, 1.0) * pow(ring, 6.0) * 0.8;
        col.rgb += (energy * (1.0 - smoothstep(0.85, 1.0, t)) + vec3(flash * 0.5)) * amount;
        return col;
    }
    if (mode < 3.5) {
        // Level clear: diagonal stripes sweep across one after another with a golden leading edge.
        float stripes = 9.0;
        vec2 d = normalize(vec2(1.0, 1.0));
        float along = dot(p, vec2(-d.y, d.x));
        float stripe = floor((along / (asp * 0.7 + 0.7) + 0.5) * stripes);
        float delay = stripe / stripes * 0.35;
        float k = clamp((t - delay) / 0.65, 0.0, 1.0);
        float reach = dot(vec2(asp, 1.0) * 0.5, abs(d));
        float edge = mix(-reach - 0.05, reach + 0.05, easeInOut(k));
        float x = dot(p, d);
        float m = 1.0 - smoothstep(edge - 0.004, edge + 0.004, x);
        float gold = gauss((x - edge) / 0.012) * step(0.001, k) * step(k, 0.999);
        vec4 col = mix(a, b, m);
        col.rgb += vec3(1.0, 0.82, 0.25) * gold * 0.9;
        return col;
    }
    // Cyber grid: a neon grid lights up over A, its cells fill with B in a random order, then fades.
    float cols = 10.0;
    vec2 grid = vec2(cols, cols / asp);
    vec2 cell = floor(uv * grid);
    vec2 f = fract(uv * grid);
    float lineW = 0.04;
    float lines = max(1.0 - smoothstep(0.0, lineW, min(f.x, 1.0 - f.x)), 1.0 - smoothstep(0.0, lineW, min(f.y, 1.0 - f.y)));
    float gridOn = smoothstep(0.0, 0.2, t) * (1.0 - smoothstep(0.8, 1.0, t));
    float order = hash12(cell) * 0.7 + (cell.x / grid.x) * 0.3;
    float fill = smoothstep(order * 0.6 + 0.15, order * 0.6 + 0.3, t);
    vec4 col = mix(a, b, fill);
    float pulse = 0.6 + 0.4 * sin(t * 30.0 + cell.x * 0.7 + cell.y * 1.3);
    col.rgb = col.rgb * (1.0 - 0.25 * gridOn) + vec3(0.1, 0.9, 1.0) * lines * gridOn * 0.8 * pulse;
    return col;
}
