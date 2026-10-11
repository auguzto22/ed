// Wipes. Edges are measured in aspect-correct screen space, so a soft edge keeps the same width in
// every direction and the iris stays round. A direction names where the edge travels.

const float PI = 3.14159265;

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }

// 0 → A, 1 → B for a straight edge sweeping across [lo, hi] along coordinate [x].
float sweep(float x, float lo, float hi, float t, float soft) {
    float edge = mix(lo - soft, hi + soft, t);
    return 1.0 - smoothstep(edge - soft, edge + soft, x);
}

vec4 reclyTransition(vec2 uv, float progress) {
    vec4 a = texture2D(uTexA, uv);
    vec4 b = texture2D(uTexB, uv);
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float asp = aspectRatio();
    vec2 p = (uv - 0.5) * vec2(asp, 1.0);
    float soft = max(p_softness, 0.002) * 0.5;
    float m;

    if (mode < 0.5) m = sweep(-p.x, -asp * 0.5, asp * 0.5, t, soft);       // edge travels left
    else if (mode < 1.5) m = sweep(p.x, -asp * 0.5, asp * 0.5, t, soft);   // edge travels right
    else if (mode < 2.5) m = sweep(p.y, -0.5, 0.5, t, soft);               // edge travels up
    else if (mode < 3.5) m = sweep(-p.y, -0.5, 0.5, t, soft);              // edge travels down
    else if (mode < 4.5) {
        // Diagonal: from the top-left corner towards the bottom-right one.
        vec2 d = normalize(vec2(asp, -1.0));
        float reach = dot(vec2(asp, 1.0) * 0.5, abs(d));
        m = sweep(dot(p, d), -reach, reach, t, soft);
    } else if (mode < 5.5) {
        // Iris opening from the centre until it clears the corners.
        float radius = mix(-soft, length(vec2(asp, 1.0) * 0.5) + soft, t);
        m = 1.0 - smoothstep(radius - soft, radius + soft, length(p));
    } else if (mode < 6.5) {
        // Clock wipe from twelve o'clock, clockwise. The edge keeps a constant width on screen.
        float r = length(p);
        float angle = fract(atan(p.x, p.y) / (2.0 * PI) + 1.0);
        float s = min(soft / max(r * 2.0 * PI, 0.001), 0.25);
        float edge = mix(-s, 1.0 + s, t);
        m = 1.0 - smoothstep(edge - s, edge + s, angle);
    } else {
        // Soft wipe: a wide feathered edge travelling right.
        float wide = max(soft, 0.18 * asp);
        m = sweep(p.x, -asp * 0.5, asp * 0.5, t, wide);
    }
    return mix(a, b, clamp(m, 0.0, 1.0));
}
