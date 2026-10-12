// Recly Color Style — filtros estilizados. p_mode escolhe o estilo; p_hue_a/p_hue_b, p_amount e
// p_levels ajustam cada um.
vec3 reclyStyleHue(float h) {
    return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
}
float reclyStyleLuma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 reclyStyleRgb2Hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1.0e-10)), d / (q.x + 1.0e-10), q.x);
}

vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    vec3 c = source.rgb;
    float l = reclyStyleLuma(c);
    float mode = floor(p_mode + 0.5);
    vec2 px = 1.0 / max(uResolution, vec2(1.0));
    vec3 a = reclyStyleHue(p_hue_a);
    vec3 b = reclyStyleHue(p_hue_b);
    vec3 outColor = c;
    if (mode < 0.5) {
        // Duotone
        outColor = mix(a * 0.25, mix(b, vec3(1.0), 0.15), smoothstep(0.0, 1.0, l));
    } else if (mode < 1.5) {
        // Posterize
        float n = max(p_levels, 2.0);
        outColor = floor(c * n + 0.5) / n;
    } else if (mode < 2.5) {
        // Sepia
        outColor = vec3(dot(c, vec3(0.393, 0.769, 0.189)), dot(c, vec3(0.349, 0.686, 0.168)), dot(c, vec3(0.272, 0.534, 0.131)));
    } else if (mode < 3.5) {
        // Color splash: mantém só a matiz escolhida
        vec3 hsv = reclyStyleRgb2Hsv(c);
        float dh = abs(hsv.x - p_hue_a); dh = min(dh, 1.0 - dh);
        float keep = (1.0 - smoothstep(0.04, 0.04 + 0.25 * p_amount, dh)) * smoothstep(0.15, 0.35, hsv.y);
        outColor = mix(vec3(l), c, keep);
    } else if (mode < 4.5) {
        // Halftone (pontos)
        float cell = max(p_levels, 2.0) * px.y * 3.0;
        vec2 g = mod(uv, cell) / cell - 0.5;
        float r = sqrt(1.0 - l) * 0.62;
        outColor = vec3(1.0 - (1.0 - smoothstep(r - 0.06, r + 0.06, length(g))));
        outColor = mix(outColor, outColor * mix(vec3(1.0), b, 0.6), p_amount);
    } else if (mode < 5.5) {
        // Esboço (bordas a lápis)
        float tl = reclyStyleLuma(texture2D(uTexSampler, uv + px * vec2(-1.0, 1.0)).rgb);
        float tr = reclyStyleLuma(texture2D(uTexSampler, uv + px * vec2(1.0, 1.0)).rgb);
        float bl = reclyStyleLuma(texture2D(uTexSampler, uv + px * vec2(-1.0, -1.0)).rgb);
        float br = reclyStyleLuma(texture2D(uTexSampler, uv + px * vec2(1.0, -1.0)).rgb);
        float edge = length(vec2(tl + bl - tr - br, tl + tr - bl - br));
        outColor = vec3(1.0 - clamp(edge * (2.0 + 6.0 * p_amount), 0.0, 1.0));
    } else if (mode < 6.5) {
        // Infravermelho
        outColor = vec3(clamp(c.g * 1.4, 0.0, 1.0), c.r * 0.6 + c.b * 0.3, c.b * 0.8 + 0.1 * p_amount);
        outColor = mix(outColor, vec3(1.0, 0.85, 0.9) * reclyStyleLuma(outColor) * 1.3, 0.35);
    } else if (mode < 7.5) {
        // Térmico
        float t = clamp(l, 0.0, 1.0);
        outColor = t < 0.25 ? mix(vec3(0.0, 0.0, 0.25), vec3(0.3, 0.0, 0.6), t * 4.0)
            : t < 0.5 ? mix(vec3(0.3, 0.0, 0.6), vec3(0.9, 0.1, 0.2), (t - 0.25) * 4.0)
            : t < 0.75 ? mix(vec3(0.9, 0.1, 0.2), vec3(1.0, 0.7, 0.0), (t - 0.5) * 4.0)
            : mix(vec3(1.0, 0.7, 0.0), vec3(1.0, 1.0, 0.9), (t - 0.75) * 4.0);
    } else if (mode < 8.5) {
        // Revelação cruzada
        outColor = vec3(smoothstep(0.05, 0.95, c.r), c.g * 1.1 - 0.02, c.b * 0.7 + 0.12);
    } else if (mode < 9.5) {
        // Bleach bypass
        vec3 blend = vec3(l);
        vec3 overlay = mix(2.0 * c * blend, 1.0 - 2.0 * (1.0 - c) * (1.0 - blend), step(0.5, blend));
        outColor = mix(c, overlay, 0.85) * vec3(0.98, 1.0, 1.02);
    } else if (mode < 10.5) {
        // Visão noturna
        float scan = 0.9 + 0.1 * sin(uv.y * uResolution.y * 1.5);
        outColor = vec3(0.1, 1.0, 0.25) * pow(l, 0.7) * 1.4 * scan;
    } else if (mode < 11.5) {
        // Cianótipo
        outColor = mix(vec3(0.02, 0.1, 0.3), vec3(0.75, 0.88, 1.0), l);
    } else if (mode < 12.5) {
        // Pop art (limiar em duas cores)
        outColor = l > 0.5 - 0.3 * (p_amount - 0.5) ? mix(b, vec3(1.0), 0.2) : a * 0.7;
    } else if (mode < 13.5) {
        // Bordas neon
        float tl = reclyStyleLuma(texture2D(uTexSampler, uv + px * vec2(-1.0, 1.0)).rgb);
        float tr = reclyStyleLuma(texture2D(uTexSampler, uv + px * vec2(1.0, 1.0)).rgb);
        float bl = reclyStyleLuma(texture2D(uTexSampler, uv + px * vec2(-1.0, -1.0)).rgb);
        float br = reclyStyleLuma(texture2D(uTexSampler, uv + px * vec2(1.0, -1.0)).rgb);
        float edge = clamp(length(vec2(tl + bl - tr - br, tl + tr - bl - br)) * (3.0 + 8.0 * p_amount), 0.0, 1.0);
        outColor = c * 0.15 + mix(a, b, uv.y) * edge * 1.4;
    } else if (mode < 14.5) {
        // Gradiente de mapa (luma → matiz A → matiz B)
        outColor = mix(mix(vec3(0.02), a, smoothstep(0.0, 0.5, l)), b, smoothstep(0.5, 1.0, l));
    } else {
        // Preto e branco de alto contraste com granulação
        float g = fract(sin(dot(floor(uv * uResolution), vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
        outColor = vec3(smoothstep(0.15, 0.85, l) + g * 0.08 * p_amount);
    }
    return vec4(clamp(outColor, 0.0, 1.0), source.a);
}
