// Glitch transitions. Patterns change in discrete steps (a fixed number per transition, so the
// preview and the export glitch identically), their strength peaks at the cut, and the clips
// swap band by band at slightly different moments, like a corrupted stream.

const float PI = 3.14159265;

// exp(-x^2) without pow(): pow() is undefined for negative bases on many mobile GPUs.
float gauss(float x) { return exp(-x * x); }

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

vec4 pick(vec2 uv, float useB) {
    vec2 q = clamp(uv, 0.0, 1.0);
    return useB > 0.5 ? texture2D(uTexB, q) : texture2D(uTexA, q);
}

// RGB split: red and blue sampled from shifted positions.
vec4 splitPick(vec2 uv, vec2 shift, float useB) {
    vec4 g = pick(uv, useB);
    return vec4(pick(uv + shift, useB).r, g.g, pick(uv - shift, useB).b, g.a);
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float amount = clamp(p_intensity, 0.0, 3.0);
    // Calm at both ends, violent around the cut.
    float env = pow(max(sin(PI * t), 0.0), 1.6) * amount;
    float frame = floor(t * 22.0);

    if (mode < 0.5) {
        // Digital glitch: blocks of the picture slide sideways and swap to the next clip early or late.
        float rows = mix(6.0, 26.0, hash12(vec2(frame, 3.0)));
        float row = floor(uv.y * rows);
        float r = hash12(vec2(row, frame));
        float hit = step(1.0 - 0.55 * min(env, 1.0), r);
        float shift = (hash12(vec2(row, frame + 7.0)) - 0.5) * 0.35 * env * hit;
        // Coarse rectangular corruption on top of the bands.
        vec2 cell = floor(uv * vec2(9.0, 7.0));
        float block = step(1.0 - 0.14 * smoothstep(0.3, 1.0, env), hash12(cell + frame * 1.31));
        float swapAt = 0.5 + (hash12(vec2(row, 91.0)) - 0.5) * 0.3 * min(amount, 1.0);
        float useB = step(swapAt, t);
        if (block > 0.5) useB = 1.0 - useB;
        if (t < 0.02) useB = 0.0;
        if (t > 0.98) useB = 1.0;
        vec2 q = vec2(uv.x + shift, uv.y);
        return splitPick(q, vec2(0.012 * env * hit, 0.0), useB);
    }
    if (mode < 1.5) {
        // RGB glitch: channels tear apart in jittering slices.
        float slice = floor(uv.y * 14.0);
        float jitter = (hash12(vec2(slice, frame)) - 0.5) * 0.06 * env;
        vec2 shift = vec2(0.035 * env + jitter, 0.004 * env * sin(frame));
        float useB = step(0.5 + (hash12(vec2(slice, 5.0)) - 0.5) * 0.16, t);
        return splitPick(vec2(uv.x + jitter, uv.y), shift, useB);
    }
    if (mode < 2.5) {
        // Signal loss: every scanline jitters, a sine wobble rolls, static lines flicker.
        float line = floor(uv.y * uResolution.y * 0.5);
        float lineNoise = (hash12(vec2(line, frame)) - 0.5) * 0.02 * env;
        float wobble = sin(uv.y * 38.0 + t * 40.0) * 0.012 * env;
        float useB = step(0.5, t);
        vec4 col = splitPick(vec2(uv.x + lineNoise + wobble, uv.y), vec2(0.006 * env, 0.0), useB);
        float stat = step(0.985 - 0.02 * env, hash12(vec2(line * 0.37, frame + 2.0)));
        float snow = hash12(uv * uResolution + frame) * 0.25 * env;
        col.rgb = mix(col.rgb, vec3(0.9), stat * min(env, 1.0) * 0.8) + snow - 0.12 * env;
        return col;
    }
    // VHS: a tracking band rolls through, chroma bleeds sideways, colour fades and tape noise flickers.
    float bandY = fract(0.85 - t * 1.6);
    float band = gauss((uv.y - bandY) / 0.06) * env;
    float tear = (valueNoise(vec2(uv.y * 60.0, frame)) - 0.5) * 0.08 * band;
    float wave = sin(uv.y * 12.0 + t * 9.0) * 0.004 * env;
    float useB = step(0.5, t);
    vec2 q = vec2(uv.x + tear + wave + band * 0.04, uv.y);
    vec4 col = pick(q, useB);
    vec4 bleed = 0.25 * (pick(q - vec2(0.008, 0.0), useB) + pick(q - vec2(0.016, 0.0), useB) +
                         pick(q - vec2(0.024, 0.0), useB) + pick(q - vec2(0.032, 0.0), useB));
    float lum = dot(col.rgb, vec3(0.299, 0.587, 0.114));
    vec3 chroma = bleed.rgb - dot(bleed.rgb, vec3(0.299, 0.587, 0.114));
    vec3 vhs = vec3(lum) + chroma * 0.9;
    vhs = mix(col.rgb, vhs, min(env, 1.0));
    float noise = (hash12(uv * uResolution * 0.5 + frame * 3.7) - 0.5) * (0.12 * env + 0.5 * band);
    return vec4(vhs * (1.0 - 0.1 * env) + noise + vec3(band * 0.15), col.a);
}
