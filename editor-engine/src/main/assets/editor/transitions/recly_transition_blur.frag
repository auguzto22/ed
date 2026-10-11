// Blur transitions. The blur grows towards the cut and settles after it, and the clips swap under
// the strongest blur. Kernels use many jittered taps (golden-angle disc, jittered lines), so a
// large radius stays smooth instead of showing ghost copies.

const float PI = 3.14159265;

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 mirrorUv(vec2 uv) { return 1.0 - abs(1.0 - mod(uv, 2.0)); }

// Disc blur with Gaussian-like falloff; [radius] in pixels.
vec4 discBlur(sampler2D tex, vec2 uv, float radius) {
    if (radius < 0.5) return texture2D(tex, uv);
    vec2 scale = radius / max(uResolution, vec2(1.0));
    float rot = hash12(gl_FragCoord.xy) * 2.0 * PI;
    vec4 sum = vec4(0.0);
    float weight = 0.0;
    for (int i = 0; i < 24; i++) {
        float fi = float(i) + 0.5;
        float r = sqrt(fi / 24.0);
        float th = fi * 2.39996323 + rot;
        float w = exp(-2.0 * r * r);
        sum += texture2D(tex, mirrorUv(uv + vec2(cos(th), sin(th)) * r * scale)) * w;
        weight += w;
    }
    return sum / weight;
}

// Line blur along [dir] (unit, aspect-correct); [len] in pixels.
vec4 lineBlur(sampler2D tex, vec2 uv, vec2 dir, float len) {
    if (len < 0.5) return texture2D(tex, uv);
    vec2 delta = dir * len / max(uResolution, vec2(1.0));
    float jitter = hash12(gl_FragCoord.xy) - 0.5;
    vec4 sum = vec4(0.0);
    float weight = 0.0;
    for (int i = 0; i < 20; i++) {
        float k = (float(i) + 0.5 + jitter) / 20.0 - 0.5;
        float w = exp(-6.0 * k * k);
        sum += texture2D(tex, mirrorUv(uv + delta * k)) * w;
        weight += w;
    }
    return sum / weight;
}

// Zoom (radial) and spin (rotational) blur around the centre; amounts are fractions.
vec4 polarBlur(sampler2D tex, vec2 uv, float zoom, float spin) {
    float asp = aspectRatio();
    vec2 p = (uv - 0.5) * vec2(asp, 1.0);
    float jitter = hash12(gl_FragCoord.xy) - 0.5;
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 20; i++) {
        float k = (float(i) + 0.5 + jitter) / 20.0 - 0.5;
        float r = spin * k;
        vec2 q = mat2(cos(r), sin(r), -sin(r), cos(r)) * p * (1.0 - zoom * k);
        sum += texture2D(tex, mirrorUv(q / vec2(asp, 1.0) + 0.5));
    }
    return sum / 20.0;
}

vec4 blurred(sampler2D tex, vec2 uv, float mode, float amount, float radius) {
    if (mode < 0.5) return discBlur(tex, uv, radius * 1.6 * amount);
    if (mode < 1.5) return discBlur(tex, uv, radius * 2.2 * amount);
    if (mode < 2.5) return lineBlur(tex, uv, normalize(vec2(cos(p_angle), sin(p_angle))), radius * 5.0 * amount);
    float relative = radius * 400.0 / max(min(uResolution.x, uResolution.y), 1.0);
    if (mode < 3.5) return polarBlur(tex, uv, 0.0, relative * 0.022 * amount);
    return polarBlur(tex, uv, relative * 0.03 * amount, 0.0);
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    // Radii are authored for a 400 px short side and scale with the frame, so preview and export
    // (which render at different sizes) blur by the same proportion.
    float radius = clamp(p_radius, 1.0, 60.0) * min(uResolution.x, uResolution.y) / 400.0;
    // Blur rises towards the cut and falls after it with a smooth shoulder at both ends.
    float amount = sin(PI * t);
    amount = amount * amount;
    // Blur dissolve overlaps the clips for most of the duration; the others swap under the blur.
    float w = mode < 0.5 ? smoothstep(0.15, 0.85, t) : smoothstep(0.38, 0.62, t);
    vec4 colA = w < 1.0 ? blurred(uTexA, uv, mode, amount, radius) : vec4(0.0);
    vec4 colB = w > 0.0 ? blurred(uTexB, uv, mode, amount, radius) : vec4(0.0);
    return mix(colA, colB, w);
}
