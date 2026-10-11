// Film transitions: the strip slips in the gate, the projector flashes, sprockets show and the
// emulsion burns. Grain and gate weave rise towards the cut, so the clean shots on either side
// stay untouched.

const float PI = 3.14159265;

// exp(-x^2) without pow(): pow() is undefined for negative bases on many mobile GPUs.
float gauss(float x) { return exp(-x * x); }

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }

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
    for (int i = 0; i < 4; i++) {
        v += valueNoise(p) * amp;
        p = p * 2.1 + vec2(13.7, 3.1);
        amp *= 0.5;
    }
    return v;
}

vec3 grain(vec2 uv, float t, float amount) {
    float frame = floor(t * 24.0);
    float g = hash12(floor(uv * uResolution / max(min(uResolution.x, uResolution.y) / 540.0, 1.0)) + frame * 17.0) - 0.5;
    return vec3(g * 0.16 * amount);
}

// The strip in the gate: A, a black frame line, then B, offset by [roll] frames.
vec4 strip(vec2 uv, float roll, float line) {
    float y = uv.y - roll * (1.0 + line);
    if (y >= 0.0) return texture2D(uTexA, vec2(uv.x, min(y, 1.0)));
    float yb = y + 1.0 + line;
    if (yb <= 1.0 && yb >= 0.0) return texture2D(uTexB, vec2(uv.x, yb));
    return vec4(0.02, 0.018, 0.016, 1.0);
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float env = sin(PI * t);
    float asp = aspectRatio();
    vec4 a = texture2D(uTexA, uv);
    vec4 b = texture2D(uTexB, uv);

    if (mode < 1.5) {
        // Film roll: the frame slips up through the gate with a frame line, motion blur and weave.
        float e = t * t * (3.0 - 2.0 * t);
        float weave = (valueNoise(vec2(t * 30.0, 1.0)) - 0.5) * 0.01 * env;
        float blur = 0.09 * env * env;
        vec4 col = vec4(0.0);
        for (int i = 0; i < 10; i++) {
            float k = (float(i) + 0.5) / 10.0 - 0.5;
            col += strip(vec2(uv.x + weave, uv.y + k * blur), e, 0.08);
        }
        col /= 10.0;
        float flicker = 1.0 + (hash12(vec2(floor(t * 24.0), 3.0)) - 0.5) * 0.18 * env;
        return vec4(col.rgb * flicker + grain(uv, t, env), col.a);
    }
    if (mode < 2.5) {
        // Leader flash: the projector flares, a countdown sweep passes over a warm, grainy image.
        float flash = gauss((t - 0.5) / 0.09);
        vec4 base = mix(a, b, smoothstep(0.47, 0.53, t));
        float lum = dot(base.rgb, vec3(0.299, 0.587, 0.114));
        vec3 warm = mix(base.rgb, vec3(lum) * vec3(1.08, 0.98, 0.82), 0.55 * env);
        vec2 p = (uv - 0.5) * vec2(asp, 1.0);
        float r = length(p);
        float ang = fract(atan(p.x, p.y) / (2.0 * PI) + 1.0);
        float sweep = fract(t * 2.0);
        float sweepShade = step(ang, sweep) * 0.12;
        float rings = ((1.0 - smoothstep(0.0, 0.006, abs(r - 0.28))) + (1.0 - smoothstep(0.0, 0.006, abs(r - 0.36)))) * 0.5;
        float crossLines = (1.0 - smoothstep(0.0, 0.004, abs(p.x))) + (1.0 - smoothstep(0.0, 0.004, abs(p.y)));
        float overlay = smoothstep(0.35, 0.85, env);
        vec3 col = warm * (1.0 - sweepShade * overlay) + vec3(rings + crossLines * 0.4) * 0.35 * overlay;
        col = col + vec3(1.0, 0.95, 0.85) * flash * 0.9;
        float flicker = 1.0 + (hash12(vec2(floor(t * 24.0), 9.0)) - 0.5) * 0.2 * env;
        return vec4(min(col * flicker, vec3(1.0)) + grain(uv, t, env), base.a);
    }
    if (mode < 3.5) {
        // Sprocket slip: the frame loses its loop, slips a full frame with the perforations in view.
        float e = t < 0.5 ? 0.5 * (8.0 * t * t * t) : 1.0 - 0.5 * ((2.0 - 2.0 * t) * (2.0 - 2.0 * t) * (2.0 - 2.0 * t));
        float jitterX = (hash12(vec2(floor(t * 30.0), 5.0)) - 0.5) * 0.03 * env;
        // Scale the frame down a touch so the film edge and its holes become visible.
        float edgeW = 0.07 * smoothstep(0.0, 0.25, env);
        vec2 q = vec2((uv.x - edgeW) / (1.0 - edgeW) + jitterX, uv.y);
        vec4 col = strip(q, e, 0.06);
        if (uv.x < edgeW) {
            // Film base with rounded perforations scrolling with the strip.
            float y = (uv.y - e * 1.06) * 8.0;
            vec2 hole = vec2((uv.x / max(edgeW, 0.001) - 0.5) * 1.4, fract(y) - 0.5);
            float holeMask = (1.0 - smoothstep(0.26, 0.32, length(max(abs(hole) - vec2(0.12, 0.1), 0.0)) + 0.18));
            col = vec4(mix(vec3(0.09, 0.07, 0.05), vec3(0.85, 0.82, 0.75), holeMask), 1.0);
        }
        return vec4(col.rgb + grain(uv, t, env), col.a);
    }
    // Burn dissolve (and the legacy film burn): the emulsion chars and melts away from a few
    // spots, a glowing rim leads the burn and the next shot shows through the holes.
    vec2 p = (uv - 0.5) * vec2(asp, 1.0);
    float field = fbm(p * 3.3 + vec2(1.3, 7.1)) * 0.7
        + 0.3 * max(1.0 - length(p - vec2(-0.35, 0.18)) * 0.9, 1.0 - length(p - vec2(0.4, -0.22)) * 1.1);
    float front = mix(1.1, -0.2, t);
    float hole = smoothstep(front - 0.015, front + 0.015, field);
    float rim = gauss((field - front) / 0.025);
    float charred = gauss((field - front + 0.05) / 0.06) * (1.0 - hole);
    vec3 col = mix(a.rgb * (1.0 - 0.85 * charred), b.rgb, hole);
    col += vec3(1.0, 0.45, 0.08) * rim * 1.4 + vec3(1.0, 0.85, 0.5) * rim * rim * 0.6;
    return vec4(min(col, vec3(1.0)) + grain(uv, t, env * 0.6), mix(a.a, b.a, hole));
}
