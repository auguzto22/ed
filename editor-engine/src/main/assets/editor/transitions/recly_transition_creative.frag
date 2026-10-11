// Creative transitions: splits, mosaics, prisms, mirrors and glass. Panels cast soft shadows on
// what they reveal, tiles are square on any aspect ratio, and optical effects peak at the cut and
// vanish at both ends so the first and last frames are untouched.

const float PI = 3.14159265;

// exp(-x^2) without pow(): pow() is undefined for negative bases on many mobile GPUs.
float gauss(float x) { return exp(-x * x); }

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }
float bell(float t) { return sin(PI * clamp(t, 0.0, 1.0)); }
vec2 mirrorUv(vec2 uv) { return 1.0 - abs(1.0 - mod(uv, 2.0)); }

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float insideAA(vec2 uv) {
    vec2 e = 0.5 / max(uResolution, vec2(1.0));
    vec2 lo = smoothstep(-e, e, uv);
    vec2 hi = smoothstep(-e, e, 1.0 - uv);
    return lo.x * lo.y * hi.x * hi.y;
}

float easeInOut(float t) { return t * t * (3.0 - 2.0 * t); }

// Which part of A covers uv when its panels have slid apart by [offset] along the masked axes.
vec4 panelSample(vec2 uv, vec2 offset, vec2 axisMask) {
    vec2 side = sign(uv - 0.5 + 1e-5) * axisMask;
    vec2 q = uv - side * offset;
    // A panel only carries its own half (or quarter) of A.
    vec2 own = step(vec2(0.0), (q - 0.5) * side + 1e-5);
    float keep = (axisMask.x > 0.5 ? own.x : 1.0) * (axisMask.y > 0.5 ? own.y : 1.0);
    return texture2D(uTexA, clamp(q, 0.0, 1.0)) * insideAA(q) * keep;
}

// Panels of A slide off; B shows behind them with a soft shadow along the panel edges.
vec4 panels(vec2 uv, vec2 offset, vec2 axisMask) {
    vec4 a = panelSample(uv, offset, axisMask);
    vec4 b = texture2D(uTexB, uv);
    vec2 s = vec2(0.022) / vec2(aspectRatio(), 1.0);
    float near = panelSample(uv + vec2(s.x, s.y), offset, axisMask).a + panelSample(uv + vec2(-s.x, s.y), offset, axisMask).a
        + panelSample(uv + vec2(s.x, -s.y), offset, axisMask).a + panelSample(uv - s, offset, axisMask).a;
    b.rgb *= 1.0 - 0.5 * near * 0.25 * step(0.0005, length(offset));
    return a + b * (1.0 - a.a);
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float asp = aspectRatio();
    vec2 p = (uv - 0.5) * vec2(asp, 1.0);

    if (mode < 0.5) return panels(uv, vec2(0.52) * easeInOut(t), vec2(1.0, 1.0));     // four panels
    if (mode < 1.5) return panels(uv, vec2(0.52 * easeInOut(t), 0.0), vec2(1.0, 0.0)); // doors open sideways
    if (mode < 2.5) return panels(uv, vec2(0.0, 0.52 * easeInOut(t)), vec2(0.0, 1.0)); // top and bottom part

    if (mode < 3.5) {
        // Mosaic: square tiles flip over one by one, sweeping diagonally with some randomness.
        float cols = 12.0;
        vec2 grid = vec2(cols, cols / asp);
        vec2 cell = floor(uv * grid);
        vec2 local = fract(uv * grid);
        float order = (cell.x / grid.x) * 0.55 + (1.0 - cell.y / grid.y) * 0.25 + hash12(cell) * 0.2;
        float k = clamp((t - order * 0.6) / 0.4, 0.0, 1.0);
        float flip = easeInOut(k);
        // Each tile turns around its vertical centre line: width shrinks to zero, then B opens.
        float w = abs(cos(flip * PI));
        float x = (local.x - 0.5) / max(w, 0.001) + 0.5;
        // Behind a turning tile the next shot is already there, in the tile's shadow.
        if (x < 0.0 || x > 1.0) return vec4(texture2D(uTexB, uv).rgb * 0.45, 1.0);
        vec2 sampleUv = (cell + vec2(x, local.y)) / grid;
        vec4 col = flip < 0.5 ? texture2D(uTexA, sampleUv) : texture2D(uTexB, sampleUv);
        return vec4(col.rgb * (0.6 + 0.4 * w), col.a);
    }
    if (mode < 4.5) {
        // Pixel dissolve: small square blocks fade at random moments.
        float size = max(min(uResolution.x, uResolution.y) / 54.0, 2.0);
        vec2 cell = floor(gl_FragCoord.xy / size);
        float at = hash12(cell) * 0.8;
        float k = smoothstep(at, at + 0.2, t);
        return mix(texture2D(uTexA, uv), texture2D(uTexB, uv), k);
    }
    if (mode < 5.5) {
        // Prism: the picture splits into three tinted copies refracted away from the centre.
        float amount = bell(t);
        amount *= amount;
        vec2 dir = p * 0.09 * amount;
        float w = smoothstep(0.4, 0.6, t);
        vec4 col = vec4(0.0);
        for (int i = 0; i < 3; i++) {
            float k = float(i) - 1.0;
            vec2 q = (p * (1.0 - 0.06 * amount) + dir * k) / vec2(asp, 1.0) + 0.5;
            vec4 s = mix(texture2D(uTexA, mirrorUv(q)), texture2D(uTexB, mirrorUv(q)), w);
            vec3 tint = i == 0 ? vec3(1.0, 0.0, 0.0) : (i == 1 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0));
            col.rgb += s.rgb * tint;
            col.a += s.a / 3.0;
        }
        return col;
    }
    if (mode < 6.5) {
        // Mirror: a reflection of the left half fades in until the frame is symmetric, the clips
        // swap inside the symmetric image, and the reflection fades away again.
        float amount = smoothstep(0.0, 0.4, t) * (1.0 - smoothstep(0.6, 1.0, t));
        float angle = (t - 0.5) * 0.35;
        vec2 normal = vec2(cos(angle), sin(angle));
        float side = dot(p, normal);
        vec2 reflected = side > 0.0 ? p - 2.0 * side * normal : p;
        vec2 d0 = p / vec2(asp, 1.0) + 0.5;
        vec2 d1 = reflected / vec2(asp, 1.0) + 0.5;
        float w = smoothstep(0.42, 0.58, t);
        vec4 plain = mix(texture2D(uTexA, d0), texture2D(uTexB, d0), w);
        vec4 mirrored = mix(texture2D(uTexA, mirrorUv(d1)), texture2D(uTexB, mirrorUv(d1)), w);
        vec4 col = mix(plain, mirrored, amount);
        col.rgb += vec3(0.1) * exp(-abs(side) * 140.0) * amount;
        return col;
    }
    if (mode < 7.5) {
        // Kaleidoscope: six mirrored wedges turn while the clips swap at the centre of the effect.
        float amount = bell(t);
        amount *= amount;
        float r = length(p);
        float a = atan(p.y, p.x) + t * 1.2;
        float wedge = PI / 3.0;
        float folded = abs(mod(a, wedge) - wedge * 0.5);
        vec2 k = vec2(cos(folded), sin(folded)) * r * (1.0 - 0.3 * amount);
        vec2 d0 = uv;
        vec2 d1 = k / vec2(asp, 1.0) + 0.5;
        float w = smoothstep(0.42, 0.58, t);
        vec4 plain = mix(texture2D(uTexA, d0), texture2D(uTexB, d0), w);
        vec4 scope = mix(texture2D(uTexA, mirrorUv(d1)), texture2D(uTexB, mirrorUv(d1)), w);
        return mix(plain, scope, smoothstep(0.0, 0.6, amount));
    }
    // Fluted glass: vertical ribs refract the picture; the swap happens behind the glass.
    float amount = bell(t);
    float ribs = 18.0;
    float x = uv.x * ribs;
    float phase = fract(x) - 0.5;
    vec2 bend = vec2(phase * 0.09 * amount, 0.0);
    float w = smoothstep(0.38, 0.62, t);
    vec2 d = uv + bend;
    vec4 col = mix(texture2D(uTexA, mirrorUv(d)), texture2D(uTexB, mirrorUv(d)), w);
    float highlight = gauss(phase / 0.08) * 0.18 * amount - phase * 0.12 * amount;
    return vec4(col.rgb + highlight, col.a);
}
