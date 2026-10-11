// 3D transitions rendered by ray casting real geometry: each pixel shoots a ray from a pinhole
// camera and hits the card, cube faces or curled page, so perspective is exact, rotations do not
// shear, faces are lit by their angle to the camera, and the background shows through where
// nothing is hit.

const float PI = 3.14159265;

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }
float bell(float t) { return sin(PI * clamp(t, 0.0, 1.0)); }

// Ray hit on a rectangle of width [w] and height 1 centred at [c] with axes [u], [v] and normal [n].
// Returns (uv, distance); distance < 0 when missed or behind the camera.
vec3 hitRect(vec3 ro, vec3 rd, vec3 c, vec3 u, vec3 v, vec3 n, float w) {
    float denom = dot(rd, n);
    if (abs(denom) < 1e-5) return vec3(0.0, 0.0, -1.0);
    float lam = dot(c - ro, n) / denom;
    if (lam <= 0.0) return vec3(0.0, 0.0, -1.0);
    vec3 h = ro + rd * lam - c;
    return vec3(dot(h, u) / w + 0.5, dot(h, v) + 0.5, lam);
}

// Coverage of a hit; the edge is anti-aliased over one pixel centred on it.
float coverage(vec2 uv) {
    vec2 e = 0.5 / max(uResolution, vec2(1.0));
    vec2 lo = smoothstep(-e, e, uv);
    vec2 hi = smoothstep(-e, e, 1.0 - uv);
    return lo.x * lo.y * hi.x * hi.y;
}

vec4 shade(vec4 c, float light) { return vec4(c.rgb * light, c.a); }

mat3 rotY(float a) { return mat3(cos(a), 0.0, -sin(a), 0.0, 1.0, 0.0, sin(a), 0.0, cos(a)); }
mat3 rotX(float a) { return mat3(1.0, 0.0, 0.0, 0.0, cos(a), sin(a), 0.0, -sin(a), cos(a)); }

// A card flipping around Y (vertical axis) or X; A on the front, B on the back.
vec4 flipCard(vec3 ro, vec3 rd, float t, bool vertical, float asp) {
    float angle = PI * t;
    mat3 r = vertical ? rotX(angle) : rotY(angle);
    // The card backs away while edge-on so its near edge never leaves the frame.
    vec3 c = vec3(0.0, 0.0, -0.55 * bell(t) * (vertical ? 1.0 : asp));
    vec3 u = r * vec3(1.0, 0.0, 0.0);
    vec3 v = r * vec3(0.0, 1.0, 0.0);
    vec3 n = r * vec3(0.0, 0.0, 1.0);
    vec3 hit = hitRect(ro, rd, c, u, v, n, asp);
    if (hit.z < 0.0) return vec4(0.0);
    bool front = dot(rd, n) < 0.0;
    vec2 uv = hit.xy;
    // The back is seen mirrored; flip it so B reads correctly once the card has turned.
    if (!front) uv = vertical ? vec2(uv.x, 1.0 - uv.y) : vec2(1.0 - uv.x, uv.y);
    float cov = coverage(uv);
    if (cov <= 0.0) return vec4(0.0);
    float light = 0.55 + 0.45 * abs(n.z);
    vec4 col = front ? texture2D(uTexA, clamp(uv, 0.0, 1.0)) : texture2D(uTexB, clamp(uv, 0.0, 1.0));
    return shade(col, light) * cov;
}

// A cube whose front face shows A and whose side face shows B, turning by 90 degrees.
vec4 cube(vec3 ro, vec3 rd, float t, float side, float asp) {
    float angle = -side * 0.5 * PI * t;
    mat3 r = rotY(angle);
    float halfW = asp * 0.5;
    vec3 centre = vec3(0.0, 0.0, -halfW - 0.45 * bell(t) * asp);
    vec3 nA = r * vec3(0.0, 0.0, 1.0);
    vec3 nB = r * vec3(side, 0.0, 0.0);
    vec3 cA = centre + nA * halfW;
    vec3 cB = centre + nB * halfW;
    vec3 uA = r * vec3(1.0, 0.0, 0.0);
    vec3 uB = r * vec3(0.0, 0.0, -side);
    vec3 up = vec3(0.0, 1.0, 0.0);
    vec4 col = vec4(0.0);
    float depth = 1e9;
    if (dot(rd, nA) < 0.0) {
        vec3 h = hitRect(ro, rd, cA, uA, up, nA, asp);
        float cov = coverage(h.xy);
        if (h.z > 0.0 && cov > 0.0) {
            col = shade(texture2D(uTexA, clamp(h.xy, 0.0, 1.0)), 0.5 + 0.5 * max(nA.z, 0.0)) * cov;
            depth = h.z;
        }
    }
    if (dot(rd, nB) < 0.0) {
        vec3 h = hitRect(ro, rd, cB, uB, up, nB, asp);
        float cov = coverage(h.xy);
        if (h.z > 0.0 && cov > 0.0 && h.z < depth + 1e-4) {
            vec4 b = shade(texture2D(uTexB, clamp(h.xy, 0.0, 1.0)), 0.5 + 0.5 * max(nB.z, 0.0)) * cov;
            col = b + col * (1.0 - b.a);
        }
    }
    return col;
}

// Page curl: A peels away around a cylinder from the right edge, showing B underneath.
vec4 pageCurl(vec2 p, float t, float asp) {
    vec2 n = normalize(vec2(1.0, -0.28));      // from the fold towards the lifting edge
    float radius = 0.12;
    vec2 halfSize = vec2(asp, 1.0) * 0.5;
    float maxN = dot(halfSize, abs(n));
    float startF = maxN;
    // Ends once the flipped page and the curl have both left the frame.
    float endF = -maxN - PI * radius * 0.5 - 0.02;
    float f = mix(startF, endF, t * t * (3.0 - 2.0 * t) * 0.25 + t * 0.75);
    float d = dot(p, n) - f;
    vec4 under = texture2D(uTexB, p / vec2(asp, 1.0) + 0.5);
    vec4 flatA = texture2D(uTexA, p / vec2(asp, 1.0) + 0.5);

    // Back of the page lying flipped over beyond the fold (topmost layer).
    if (d < 0.0) {
        vec2 q = p + n * (PI * radius - 2.0 * d);
        vec2 uv = q / vec2(asp, 1.0) + 0.5;
        float cov = coverage(uv);
        vec4 back = vec4(mix(texture2D(uTexA, clamp(uv, 0.0, 1.0)).rgb, vec3(0.93), 0.72), 1.0) * cov;
        // The overhang casts a soft shadow on the page below it.
        float shadow = 1.0 - 0.35 * exp(d / 0.05) * smoothstep(0.0, 0.05, t);
        return back + vec4(flatA.rgb * shadow, flatA.a) * (1.0 - back.a);
    }
    if (d < radius) {
        // Upper (back) side of the curl first, then the lower (front) side.
        float aBack = PI - asin(d / radius);
        vec2 qBack = p + n * (radius * aBack - d);
        vec2 uvBack = qBack / vec2(asp, 1.0) + 0.5;
        float covBack = coverage(uvBack);
        float aFront = asin(d / radius);
        vec2 qFront = p + n * (radius * aFront - d);
        vec2 uvFront = qFront / vec2(asp, 1.0) + 0.5;
        float covFront = coverage(uvFront);
        float lightFront = 0.75 + 0.25 * cos(aFront) - 0.3 * (1.0 - cos(aFront));
        vec4 frontCol = shade(texture2D(uTexA, clamp(uvFront, 0.0, 1.0)), lightFront) * covFront;
        float lightBack = 0.8 + 0.2 * sin(aBack);
        vec4 backCol = vec4(mix(texture2D(uTexA, clamp(uvBack, 0.0, 1.0)).rgb, vec3(0.93), 0.72) * lightBack, 1.0) * covBack;
        vec4 lower = frontCol + under * (1.0 - frontCol.a);
        return backCol + lower * (1.0 - backCol.a);
    }
    // Shadow of the curl falling on the next page.
    float shadow = 1.0 - 0.45 * exp(-(d - radius) / 0.07) * step(0.001, t);
    return vec4(under.rgb * shadow, under.a);
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float asp = aspectRatio();
    vec2 p = (uv - 0.5) * vec2(asp, 1.0);
    float camDist = 2.2 / clamp(p_perspective, 0.1, 2.0);
    vec3 ro = vec3(0.0, 0.0, camDist);
    vec3 rd = vec3(p, -camDist);

    if (mode < 0.5) return flipCard(ro, rd, t, false, asp);
    if (mode < 1.5) return flipCard(ro, rd, t, true, asp);
    if (mode < 2.5) return cube(ro, rd, t, 1.0, asp);
    if (mode < 3.5) return cube(ro, rd, t, -1.0, asp);
    if (mode < 4.5) return pageCurl(p, t, asp);

    // Perspective slide: A swings away to the left into depth while B swings in from the right.
    float s = t * t * (3.0 - 2.0 * t);
    vec4 col = vec4(0.0);
    float depth = 1e9;
    for (int i = 0; i < 2; i++) {
        float k = i == 0 ? s : s - 1.0;
        mat3 r = rotY(-0.62 * k);
        vec3 c = vec3(-asp * 0.92 * k, 0.0, -abs(k) * asp * 0.75);
        vec3 n = r * vec3(0.0, 0.0, 1.0);
        vec3 h = hitRect(ro, rd, c, r * vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), n, asp);
        float cov = coverage(h.xy);
        if (h.z > 0.0 && cov > 0.0 && h.z < depth) {
            vec4 tex = i == 0 ? texture2D(uTexA, clamp(h.xy, 0.0, 1.0)) : texture2D(uTexB, clamp(h.xy, 0.0, 1.0));
            vec4 c2 = shade(tex, 0.55 + 0.45 * max(n.z, 0.0)) * cov;
            col = c2 + col * (1.0 - c2.a);
            depth = h.z;
        }
    }
    return col;
}
