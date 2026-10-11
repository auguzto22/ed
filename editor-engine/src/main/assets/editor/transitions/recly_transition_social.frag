// Social-style transitions: feed swipes, story cubes, a heart iris, popping bubbles and a
// notification card that expands to full screen. Cards have rounded corners and soft shadows.

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
float easeOutBack(float t) {
    float c = 1.7;
    float u = t - 1.0;
    return 1.0 + (c + 1.0) * u * u * u + c * u * u;
}

// Signed distance to a rounded rectangle centred at the origin (aspect-correct units).
float roundedBox(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float pixelSize() { return 1.0 / max(min(uResolution.x, uResolution.y), 1.0); }

mat3 rotY(float a) { return mat3(cos(a), 0.0, -sin(a), 0.0, 1.0, 0.0, sin(a), 0.0, cos(a)); }

vec3 hitRect(vec3 ro, vec3 rd, vec3 c, vec3 u, vec3 v, vec3 n, float w) {
    float denom = dot(rd, n);
    if (abs(denom) < 1e-5) return vec3(0.0, 0.0, -1.0);
    float lam = dot(c - ro, n) / denom;
    if (lam <= 0.0) return vec3(0.0, 0.0, -1.0);
    vec3 h = ro + rd * lam - c;
    return vec3(dot(h, u) / w + 0.5, dot(h, v) + 0.5, lam);
}

float coverage(vec2 uv) {
    vec2 e = 0.5 / max(uResolution, vec2(1.0));
    vec2 lo = smoothstep(-e, e, uv);
    vec2 hi = smoothstep(-e, e, 1.0 - uv);
    return lo.x * lo.y * hi.x * hi.y;
}

float dot2(vec2 v) { return dot(v, v); }

// Signed distance to a heart about one unit tall with its tip at the origin (Inigo Quilez).
float sdHeart(vec2 p) {
    p.x = abs(p.x);
    if (p.y + p.x > 1.0) return sqrt(dot2(p - vec2(0.25, 0.75))) - sqrt(2.0) / 4.0;
    return sqrt(min(dot2(p - vec2(0.0, 1.0)), dot2(p - 0.5 * max(p.x + p.y, 0.0)))) * sign(p.x - p.y);
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float asp = aspectRatio();
    vec2 p = (uv - 0.5) * vec2(asp, 1.0);

    if (mode < 0.5) {
        // Swipe up: the next post slides up over the current one, which drifts back and dims.
        float e = easeInOut(t);
        vec2 uvB = uv - vec2(0.0, e - 1.0);
        float speed = sin(PI * t);
        if (uvB.y <= 1.0) {
            vec4 col = vec4(0.0);
            for (int i = 0; i < 8; i++) {
                float k = (float(i) + 0.5) / 8.0 - 0.5;
                col += texture2D(uTexB, clamp(uvB + vec2(0.0, k * 0.05 * speed), 0.0, 1.0));
            }
            return col / 8.0;
        }
        vec2 uvA = uv - vec2(0.0, e * 0.35);
        vec4 a = texture2D(uTexA, clamp(uvA, 0.0, 1.0));
        float shade = 1.0 - 0.45 * e - 0.35 * exp(-(uvB.y - 1.0) * 30.0) * speed;
        return vec4(a.rgb * shade, a.a);
    }
    if (mode < 1.5) {
        // Stories cube: the current story turns away to the left on a cube, the next one follows.
        float camDist = 1.6;
        vec3 ro = vec3(0.0, 0.0, camDist);
        vec3 rd = vec3(p, -camDist);
        float e = easeInOut(t);
        mat3 r = rotY(-0.5 * PI * e);
        float halfW = asp * 0.5;
        vec3 centre = vec3(0.0, 0.0, -halfW - 0.3 * sin(PI * t) * asp);
        vec3 nA = r * vec3(0.0, 0.0, 1.0);
        vec3 nB = r * vec3(1.0, 0.0, 0.0);
        vec4 col = vec4(0.0);
        if (dot(rd, nA) < 0.0) {
            vec3 h = hitRect(ro, rd, centre + nA * halfW, r * vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), nA, asp);
            float cov = coverage(h.xy);
            if (h.z > 0.0 && cov > 0.0) col = vec4(texture2D(uTexA, clamp(h.xy, 0.0, 1.0)).rgb * (0.35 + 0.65 * max(nA.z, 0.0)), 1.0) * cov;
        }
        if (dot(rd, nB) < 0.0) {
            vec3 h = hitRect(ro, rd, centre + nB * halfW, r * vec3(0.0, 0.0, -1.0), vec3(0.0, 1.0, 0.0), nB, asp);
            float cov = coverage(h.xy);
            if (h.z > 0.0 && cov > 0.0) {
                vec4 b = vec4(texture2D(uTexB, clamp(h.xy, 0.0, 1.0)).rgb * (0.35 + 0.65 * max(nB.z, 0.0)), 1.0) * cov;
                col = b + col * (1.0 - b.a);
            }
        }
        return col;
    }
    if (mode < 2.5) {
        // Heart iris: a heart opens from the centre with a thin glowing rim.
        float size = mix(0.0001, 3.2 * max(asp, 1.0), easeInOut(t) * easeInOut(t));
        float d = sdHeart(p / size + vec2(0.0, 0.5)) * size;
        float px = pixelSize();
        float inside = 1.0 - smoothstep(-px, px, d);
        vec4 a = texture2D(uTexA, uv);
        vec4 b = texture2D(uTexB, uv);
        float rim = gauss(d / 0.012) * smoothstep(0.0, 0.08, t) * (1.0 - smoothstep(0.85, 1.0, t));
        vec4 col = mix(a, b, inside);
        col.rgb += vec3(1.0, 0.4, 0.6) * rim * 0.55;
        return col;
    }
    if (mode < 3.5) {
        // Bubble pop: bubbles swell across the frame from left to right and pop open onto B.
        float cells = 7.0;
        vec2 grid = p * cells;
        vec2 cell = floor(grid);
        vec4 a = texture2D(uTexA, uv);
        vec4 b = texture2D(uTexB, uv);
        float reveal = 0.0;
        float rim = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                vec2 c = cell + vec2(float(i), float(j));
                vec2 centre = c + 0.5 + (vec2(hash12(c), hash12(c + 7.0)) - 0.5) * 0.6;
                float order = (centre.x / cells / asp + 0.5) * 0.6 + hash12(c + 3.0) * 0.25;
                float k = clamp((t - order * 0.8) / 0.25, 0.0, 1.0);
                float radius = easeOutBack(k) * 1.15 * step(0.0001, k);
                float d = length(grid - centre);
                reveal = max(reveal, 1.0 - smoothstep(radius - 0.03, radius + 0.03, d));
                // A thin highlight only while the bubble is still swelling.
                rim = max(rim, gauss((d - radius) / 0.03) * smoothstep(0.0, 0.2, k) * (1.0 - smoothstep(0.5, 0.9, k)));
            }
        }
        if (t >= 1.0) reveal = 1.0;
        vec4 col = mix(a, b, reveal);
        col.rgb += vec3(rim * 0.18);
        return col;
    }
    // Notification: B drops in as a rounded card at the top, then expands to fill the screen.
    float drop = easeOutBack(clamp(t / 0.45, 0.0, 1.0));
    float grow = easeInOut(clamp((t - 0.45) / 0.55, 0.0, 1.0));
    vec2 cardHalf = mix(vec2(asp * 0.44, 0.13), vec2(asp * 0.5, 0.5), grow);
    vec2 cardCentre = mix(vec2(0.0, mix(0.75, 0.31, drop)), vec2(0.0), grow);
    float radius = mix(0.05, 0.0, grow);
    float d = roundedBox(p - cardCentre, cardHalf, radius);
    float px = pixelSize();
    float inside = 1.0 - smoothstep(-px, px, d);
    // Inside the card, B is shown scaled to the card (cover fit).
    vec2 local = (p - cardCentre) / (cardHalf * 2.0) + 0.5;
    vec2 fit = vec2(1.0, (cardHalf.y * 2.0) / (cardHalf.x * 2.0 / asp));
    vec2 uvB = vec2(local.x, 0.5 + (local.y - 0.5) * min(fit.y, 1.0));
    vec4 b = texture2D(uTexB, clamp(uvB, 0.0, 1.0));
    vec4 a = texture2D(uTexA, uv);
    float shadow = exp(-max(d, 0.0) * 40.0) * 0.45 * (1.0 - grow);
    a.rgb *= (1.0 - 0.3 * smoothstep(0.0, 0.45, t) * (1.0 - grow)) * (1.0 - shadow);
    return mix(a, b, inside);
}
