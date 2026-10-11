// Distortions that build towards the cut and relax after it. All of them are computed in
// aspect-correct space, so ripples are round and lenses are not stretched on portrait video.

const float PI = 3.14159265;

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }
vec2 mirrorUv(vec2 uv) { return 1.0 - abs(1.0 - mod(uv, 2.0)); }

vec4 both(vec2 uvA, vec2 uvB, float w) {
    vec4 a = w < 1.0 ? texture2D(uTexA, mirrorUv(uvA)) : vec4(0.0);
    vec4 b = w > 0.0 ? texture2D(uTexB, mirrorUv(uvB)) : vec4(0.0);
    return mix(a, b, w);
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float strength = clamp(p_strength, 0.0, 3.0);
    float asp = aspectRatio();
    vec2 p = (uv - 0.5) * vec2(asp, 1.0);
    float r = length(p);
    float env = sin(PI * t);
    env *= env;
    float w = smoothstep(0.35, 0.65, t);

    if (mode < 0.5) {
        // Ripple: rings travel outwards from the centre and fade with distance.
        float wave = sin(r * 38.0 - t * 26.0) * exp(-r * 1.6);
        vec2 dir = r > 0.0001 ? p / r : vec2(0.0);
        vec2 q = p + dir * wave * 0.035 * env * strength;
        vec2 d = q / vec2(asp, 1.0) + 0.5;
        vec4 col = both(d, d, w);
        // Light catches the wave crests.
        col.rgb += vec3(0.08) * wave * env * strength;
        return col;
    }
    if (mode < 1.5) {
        // Wave: the picture flows in horizontal waves whose amplitude swells with the cut.
        float amp = 0.045 * env * strength;
        vec2 off = vec2(sin(uv.y * 9.0 + t * 12.0) * amp, sin(uv.x * 7.0 + t * 9.0) * amp * 0.35);
        return both(uv + off, uv + off, w);
    }
    if (mode < 2.5) {
        // Fisheye: the centre bulges towards the viewer while the corners stay put
        // (radius maps to radius^2 / cornerRadius at full strength).
        float corner = length(vec2(asp, 1.0) * 0.5);
        float k = clamp(0.85 * env * strength, 0.0, 1.0);
        vec2 q = p * mix(1.0, r / corner, k);
        vec2 d = q / vec2(asp, 1.0) + 0.5;
        return both(d, d, w);
    }
    if (mode < 3.5) {
        // Warp: a twirl that winds up and unwinds, strongest at the centre.
        float angle = 2.4 * env * strength * exp(-r * 2.2);
        float s = sin(angle);
        float c = cos(angle);
        vec2 q = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
        vec2 d = q / vec2(asp, 1.0) + 0.5;
        return both(d, d, w);
    }
    // Lens distortion: barrel distortion with colour fringing that grows towards the edges.
    float corner2 = dot(vec2(asp, 1.0) * 0.5, vec2(asp, 1.0) * 0.5);
    float k = 0.55 * env * strength;
    vec2 base = p * (1.0 + k * (r * r - corner2) * 0.9);
    vec2 fringe = p * 0.018 * env * strength * r;
    vec2 dr = (base + fringe) / vec2(asp, 1.0) + 0.5;
    vec2 dg = base / vec2(asp, 1.0) + 0.5;
    vec2 db = (base - fringe) / vec2(asp, 1.0) + 0.5;
    vec4 g = both(dg, dg, w);
    return vec4(both(dr, dr, w).r, g.g, both(db, db, w).b, g.a);
}
