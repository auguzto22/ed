// Recly Color Grade — motor único dos filtros de cor. Cada filtro do catálogo é um conjunto de
// valores padrão destes parâmetros, então todo filtro continua ajustável depois de aplicado.
vec3 reclyHue(float h) {
    return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
}
float reclyLuma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    vec3 c = source.rgb;
    // Exposição em stops e gama.
    c *= pow(2.0, p_exposure);
    c = pow(max(c, 0.0), vec3(1.0 / max(p_gamma, 0.05)));
    // Temperatura (azul ↔ âmbar) e tinta (verde ↔ magenta).
    c *= vec3(1.0 + 0.18 * p_temperature + 0.06 * p_tint, 1.0 - 0.12 * p_tint, 1.0 - 0.18 * p_temperature + 0.06 * p_tint);
    // Contraste em torno do cinza médio.
    c = (c - 0.5) * (1.0 + p_contrast) + 0.5;
    // Saturação e vibrância (vibrância protege cores já saturadas).
    float l = reclyLuma(c);
    c = mix(vec3(l), c, 1.0 + p_saturation);
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    c = mix(vec3(reclyLuma(c)), c, 1.0 + p_vibrance * (1.0 - sat));
    // Split toning: matiz nas sombras e nas altas luzes.
    l = clamp(reclyLuma(c), 0.0, 1.0);
    vec3 shadowTone = reclyHue(p_shadow_hue) - 0.5;
    vec3 highlightTone = reclyHue(p_highlight_hue) - 0.5;
    c += shadowTone * p_shadow_tint * (1.0 - l) * 0.35;
    c += highlightTone * p_highlight_tint * l * 0.35;
    // Fade: levanta os pretos como filme lavado.
    c = mix(c, vec3(0.08) + c * 0.92, p_fade);
    // Vinheta.
    vec2 d = uv - 0.5;
    d.x *= uResolution.x / max(uResolution.y, 1.0);
    c *= 1.0 - p_vignette * smoothstep(0.25, 0.85, length(d));
    return vec4(clamp(c, 0.0, 1.0), source.a);
}
