// Light transitions: flashes, leaks, burns and glows. Light is added in linear light and rolled
// off with a soft shoulder, so highlights bloom without clipping to a flat white frame, and the
// clips swap underneath the brightest moment.

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

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += valueNoise(p) * amp;
        p = p * 2.03 + vec2(17.1, 9.7);
        amp *= 0.5;
    }
    return v;
}

// A peak centred on the cut; [width] is the half-width in progress units. It is forced to zero at
// both ends so the first and last frames are the untouched shots.
float pulse(float t, float width) {
    float d = (t - 0.5) / width;
    return exp(-d * d) * smoothstep(0.0, 0.1, t) * (1.0 - smoothstep(0.9, 1.0, t));
}

vec3 glowAround(sampler2D tex, vec2 uv, float radius) {
    vec2 scale = radius / max(uResolution, vec2(1.0)) * min(uResolution.x, uResolution.y) / 400.0;
    float rot = hash12(gl_FragCoord.xy) * 2.0 * PI;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 16; i++) {
        float fi = float(i) + 0.5;
        float r = sqrt(fi / 16.0);
        float th = fi * 2.39996323 + rot;
        vec3 c = toLinear(texture2D(tex, clamp(uv + vec2(cos(th), sin(th)) * r * scale, 0.0, 1.0)).rgb);
        // Only highlights glow, and the glow is less saturated than its source, like halation.
        float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
        sum += max(lum - 0.3, 0.0) * mix(vec3(lum), c, 0.5) / max(lum, 0.001);
    }
    return sum / 16.0;
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float intensity = clamp(p_intensity, 0.0, 3.0);
    float asp = aspectRatio();
    vec4 a = texture2D(uTexA, uv);
    vec4 b = texture2D(uTexB, uv);
    float swap = smoothstep(0.46, 0.54, t);
    vec4 base = mix(a, b, swap);
    vec3 lin = toLinear(base.rgb);

    if (mode < 0.5) {
        // White flash: exposure ramps up, a brief white peak hides the cut, then settles.
        float f = pulse(t, 0.2);
        float peak = pulse(t, 0.07);
        vec3 exposed = lin * exp2(2.2 * f * intensity) + vec3(peak * intensity * 1.2);
        return vec4(toDisplay(shoulder(exposed)), base.a);
    }
    if (mode < 1.5) {
        // Colour flash: a graded light sweeps through magenta to amber.
        float f = pulse(t, 0.2);
        vec2 p = (uv - 0.5) * vec2(asp, 1.0);
        vec3 gel = mix(vec3(1.0, 0.25, 0.6), vec3(1.0, 0.62, 0.2), clamp(0.5 + p.x * 0.6 - p.y * 0.4, 0.0, 1.0));
        vec3 lit = lin * (1.0 + 2.5 * f * intensity * gel) + toLinear(gel) * f * f * intensity * 0.9;
        return vec4(toDisplay(shoulder(lit)), base.a);
    }
    if (mode < 2.5) {
        // Light leak: warm blobs drift across the frame and are screened over the picture.
        vec2 p = (uv - 0.5) * vec2(asp, 1.0);
        float f = pulse(t, 0.28) * intensity;
        float drift = t - 0.5;
        float leak = 0.0;
        leak += exp(-dot(p - vec2(-0.55 + drift * 1.6, 0.25), p - vec2(-0.55 + drift * 1.6, 0.25)) * 3.0);
        leak += 0.8 * exp(-dot(p - vec2(0.45 + drift * 1.2, -0.2), p - vec2(0.45 + drift * 1.2, -0.2)) * 4.0);
        leak += 0.6 * exp(-dot(p - vec2(drift * 2.0, 0.05), p - vec2(drift * 2.0, 0.05)) * 2.0);
        leak *= 0.6 + 0.8 * fbm(p * 2.2 + vec2(t * 0.7, 0.0));
        vec3 tint = mix(vec3(1.0, 0.32, 0.08), vec3(1.0, 0.82, 0.45), clamp(leak * 0.6, 0.0, 1.0));
        vec3 light = toLinear(tint) * leak * f * 1.6;
        vec3 screened = 1.0 - (1.0 - min(lin, 1.0)) * (1.0 - min(light, 1.0));
        return vec4(toDisplay(shoulder(screened + light * 0.25)), base.a);
    }
    if (mode < 3.5) {
        // Film burn: hot spots eat through A with an organic front; B shows behind the burn.
        vec2 p = (uv - 0.5) * vec2(asp, 1.0);
        float field = fbm(p * 2.6 + vec2(3.1, 1.7)) * 0.75 + (1.0 - length(p - vec2(0.25, -0.1)) * 0.55) * 0.35;
        float front = mix(1.25, -0.1, t);
        float burnt = smoothstep(front - 0.02, front + 0.02, field);
        float edge = gauss((field - front) / 0.06);
        float halo = gauss((field - front) / 0.18);
        vec3 linA = toLinear(a.rgb) * (1.0 - 0.45 * halo * (1.0 - burnt));
        vec3 linB = toLinear(b.rgb);
        vec3 col = mix(linA, linB, burnt);
        vec3 fire = mix(vec3(1.0, 0.25, 0.02), vec3(1.0, 0.85, 0.5), edge);
        col += toLinear(fire) * (edge * 2.2 + halo * 0.35) * intensity;
        return vec4(toDisplay(shoulder(col)), mix(a.a, b.a, burnt));
    }
    // Glow flash: highlights bloom out, the picture lifts, and the swap hides inside the glow.
    float f = pulse(t, 0.24) * intensity;
    vec3 glowA = swap < 1.0 ? glowAround(uTexA, uv, 40.0) : vec3(0.0);
    vec3 glowB = swap > 0.0 ? glowAround(uTexB, uv, 40.0) : vec3(0.0);
    vec3 glow = mix(glowA, glowB, swap);
    vec3 col = lin * exp2(1.0 * f) + glow * 3.5 * f + vec3(0.06 * f * f);
    return vec4(toDisplay(shoulder(col)), base.a);
}
