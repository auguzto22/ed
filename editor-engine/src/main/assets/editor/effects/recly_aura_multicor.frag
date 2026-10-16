// Aura multicor: três manchas de luz coloridas que passeiam devagar pela tela, com névoa nos
// pretos e bordas escurecidas. É o look dos edits de futebol (roxo em cima, laranja embaixo,
// verde-água na lateral). Paletas: 0 Aura, 1 Retrô, 2 Fogo, 3 Gelo.
vec3 auraColor(float slot, float palette) {
    if (palette < 0.5) {
        if (slot < 0.5) return vec3(0.66, 0.24, 0.92);
        if (slot < 1.5) return vec3(1.00, 0.46, 0.16);
        return vec3(0.12, 0.86, 0.74);
    }
    if (palette < 1.5) {
        if (slot < 0.5) return vec3(1.00, 0.58, 0.55);
        if (slot < 1.5) return vec3(1.00, 0.80, 0.50);
        return vec3(0.30, 0.92, 0.42);
    }
    if (palette < 2.5) {
        if (slot < 0.5) return vec3(1.00, 0.20, 0.18);
        if (slot < 1.5) return vec3(1.00, 0.62, 0.12);
        return vec3(1.00, 0.86, 0.30);
    }
    if (slot < 0.5) return vec3(0.55, 0.45, 1.00);
    if (slot < 1.5) return vec3(0.25, 0.60, 1.00);
    return vec3(0.40, 0.95, 1.00);
}

// Tom das sombras levantadas pela névoa (pretos "leitosos" com a cor da paleta).
vec3 auraShadow(float palette) {
    if (palette < 0.5) return vec3(0.14, 0.12, 0.28);
    if (palette < 1.5) return vec3(0.22, 0.24, 0.18);
    if (palette < 2.5) return vec3(0.30, 0.10, 0.08);
    return vec3(0.08, 0.16, 0.30);
}

float auraBlob(vec2 uv, vec2 center, float radius, float aspect) {
    vec2 q = (uv - center) * vec2(aspect, 1.0);
    return exp(-dot(q, q) / max(radius * radius, 0.0001) * 2.2);
}

vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float aspect = uResolution.x / max(uResolution.y, 1.0);
    float t = uTime * p_speed;
    float pal = floor(p_palette + 0.5);

    // Centros em movimento lento (cada um com período próprio para nunca repetir em sincronia).
    vec2 cTop = vec2(0.10 + 0.14 * sin(t * 0.71), 0.92 + 0.06 * cos(t * 0.53));
    vec2 cBottom = vec2(0.50 + 0.30 * sin(t * 0.43 + 1.7), -0.04 + 0.06 * sin(t * 0.91));
    vec2 cSide = vec2(1.02 + 0.05 * sin(t * 0.62 + 0.4), 0.50 + 0.28 * sin(t * 0.37 + 2.1));

    vec3 light = auraColor(0.0, pal) * auraBlob(uv, cTop, 0.48, aspect) * 0.8
               + auraColor(1.0, pal) * auraBlob(uv, cBottom, 0.72, aspect) * 1.3
               + auraColor(2.0, pal) * auraBlob(uv, cSide, 0.5, aspect);
    // Respiração suave da luz, como um vazamento de filme real.
    light *= p_amount * (0.86 + 0.14 * sin(t * 1.3 + 0.8));

    vec3 c = s.rgb;
    // Névoa: levanta os pretos na direção da cor de sombra da paleta.
    vec3 lift = auraShadow(pal) * p_haze * 0.55;
    c = lift + c * (1.0 - lift);
    // Luz somada em modo screen (nunca estoura de forma dura).
    c = 1.0 - (1.0 - c) * (1.0 - clamp(light, 0.0, 1.0));
    // Bordas escurecidas.
    vec2 d = (uv - 0.5) * vec2(1.0, 1.15);
    float vig = smoothstep(0.32, 0.86, length(d));
    c *= 1.0 - p_vignette * vig * 0.65;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}
