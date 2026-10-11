// Cinematic transitions: anamorphic streaks, letterbox bars, a sweeping lens flare and a rotary
// shutter. Light is added in linear light with a soft shoulder; the clips swap under the
// brightest or darkest moment.

const float PI = 3.14159265;

// exp(-x^2) without pow(): pow() is undefined for negative bases on many mobile GPUs.
float gauss(float x) { return exp(-x * x); }

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }
vec3 toLinear(vec3 c) { return c * c; }
vec3 toDisplay(vec3 c) { return sqrt(max(c, 0.0)); }

vec3 shoulder(vec3 x) {
    vec3 over = max(x - 0.7, 0.0);
    return min(x, vec3(0.7)) + 0.3 * (1.0 - exp(-over / 0.3));
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec4 swapped(vec2 uv, float w) {
    return mix(texture2D(uTexA, uv), texture2D(uTexB, uv), w);
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float amount = clamp(p_intensity, 0.0, 3.0);
    float asp = aspectRatio();
    float w = smoothstep(0.45, 0.55, t);
    float env = gauss((t - 0.5) / 0.22);

    if (mode < 0.5) {
        // Anamorphic streak: highlights smear into long horizontal blue streaks around the cut.
        vec4 base = swapped(uv, w);
        float jitter = hash12(gl_FragCoord.xy) - 0.5;
        vec3 streak = vec3(0.0);
        for (int i = 0; i < 16; i++) {
            float k = (float(i) + 0.5 + jitter) / 16.0 - 0.5;
            vec3 c = toLinear(swapped(vec2(uv.x + k * 0.6, uv.y), w).rgb);
            float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
            streak += vec3(max(lum - 0.35, 0.0)) * (1.0 - abs(k) * 1.6);
        }
        streak = streak / 16.0 * vec3(0.35, 0.65, 1.0);
        float line = gauss((uv.y - 0.5) / 0.012) * env;
        vec3 col = toLinear(base.rgb) + (streak * 7.0 * env + vec3(0.3, 0.6, 1.0) * line * 0.8) * amount;
        return vec4(toDisplay(shoulder(col)), base.a);
    }
    if (mode < 1.5) {
        // Letterbox: black bars close from top and bottom, meet at the cut, then open on B.
        float close = t < 0.5 ? smoothstep(0.0, 1.0, t * 2.0) : smoothstep(0.0, 1.0, (1.0 - t) * 2.0);
        float bar = close * 0.5;
        float soft = 0.004;
        float d = 0.5 - abs(uv.y - 0.5);
        float m = smoothstep(bar - soft, bar + soft, d);
        vec4 col = t < 0.5 ? texture2D(uTexA, uv) : texture2D(uTexB, uv);
        return vec4(col.rgb * m, mix(1.0, col.a, m));
    }
    if (mode < 2.5) {
        // Flare sweep: a lens flare crosses the frame; ghosts mirror it through the centre.
        vec4 base = swapped(uv, w);
        vec2 p = (uv - 0.5) * vec2(asp, 1.0);
        vec2 src = vec2(mix(-0.75, 0.75, t) * asp, 0.12 + 0.08 * sin(t * PI));
        float d = length(p - src);
        float core = exp(-d * d * 60.0) * 2.0 + exp(-d * 6.0) * 0.35;
        float streak = gauss((p.y - src.y) / 0.01) * exp(-abs(p.x - src.x) * 2.5) * 0.9;
        vec3 flare = vec3(1.0, 0.82, 0.6) * core + vec3(0.55, 0.75, 1.0) * streak;
        for (int i = 1; i < 4; i++) {
            float fi = float(i);
            vec2 ghost = -src * (0.35 * fi);
            float gd = length(p - ghost);
            float ring = gauss((gd - 0.04 * fi) / (0.012 * fi)) * 0.12 + exp(-gd * gd * 400.0 / fi) * 0.12;
            flare += vec3(0.6, 0.9, 0.7 + 0.1 * fi) * ring;
        }
        float strength = (0.35 + 0.65 * env) * amount * smoothstep(0.0, 0.12, t) * (1.0 - smoothstep(0.88, 1.0, t));
        vec3 col = toLinear(base.rgb);
        col = 1.0 - (1.0 - min(col, 1.0)) * (1.0 - min(flare * strength, 1.0));
        return vec4(toDisplay(shoulder(col + flare * strength * 0.15)), base.a);
    }
    // Rotary shutter: a blade sweeps closed around the centre, then sweeps open on the next shot.
    vec2 p = (uv - 0.5) * vec2(asp, 1.0);
    float ang = fract(atan(p.x, p.y) / (2.0 * PI) + 1.0);
    float r = length(p);
    float soft = min(0.008 / max(r, 0.01), 0.2);
    float closing = smoothstep(0.0, 1.0, min(t * 2.0, 1.0));
    float opening = smoothstep(0.0, 1.0, max(t * 2.0 - 1.0, 0.0));
    // Covered sector runs from `opening` to `closing` (in turns).
    float covered = smoothstep(opening - soft, opening + soft, ang) * (1.0 - smoothstep(closing - soft, closing + soft, ang));
    if (closing >= 1.0 && opening <= 0.0) covered = 1.0;
    vec4 col = t < 0.5 ? texture2D(uTexA, uv) : texture2D(uTexB, uv);
    return vec4(col.rgb * (1.0 - covered), mix(col.a, 1.0, covered));
}
