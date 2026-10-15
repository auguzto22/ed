// Barra branca: um painel atravessa a tela cobrindo a cena atual e, atrás dele, já aparece a
// próxima (transição dos edits P&B). p_direction: 0 esquerda→direita, 1 direita→esquerda,
// 2 cima→baixo, 3 baixo→cima. p_width: largura da barra. p_color: 0 branca, 1 preta.
// p_steps: 0 = varredura contínua; 2 ou mais = a barra salta em posições fixas. Com 2 degraus e
// largura 0.5 fica igual ao edit de referência: metade esquerda branca, depois metade direita
// branca com a próxima cena já aparecendo à esquerda.
vec4 reclyTransition(vec2 uv, float reclyProgress) {
    float p = clamp(reclyProgress, 0.0, 1.0);
    float steps = floor(p_steps + 0.5);
    if (steps >= 2.0) {
        p = (min(floor(p * steps), steps - 1.0) + 1.0) / (steps + 1.0);
    } else {
        // Aceleração no começo e freio no fim, como o painel arrastado à mão.
        p = p * p * (3.0 - 2.0 * p);
    }
    float dir = floor(p_direction + 0.5);
    float x = uv.x;
    float across = uv.y;
    if (dir > 0.5 && dir < 1.5) x = 1.0 - uv.x;
    else if (dir > 1.5 && dir < 2.5) { x = 1.0 - uv.y; across = uv.x; }
    else if (dir > 2.5) { x = uv.y; across = uv.x; }

    float w = clamp(p_width, 0.05, 1.0);
    float lead = p * (1.0 + w);
    float tail = lead - w;
    float s = 0.004 + p_softness * 0.08;

    vec4 a = texture2D(uTexA, uv);
    vec4 b = texture2D(uTexB, uv);
    // Atrás da barra (já percorrido) fica a próxima cena; à frente, a atual.
    vec4 scene = mix(a, b, 1.0 - smoothstep(tail - s, tail + s, x));
    float bar = smoothstep(tail - s, tail + s, x) * (1.0 - smoothstep(lead - s, lead + s, x));

    // Painel com leve degradê no sentido transversal (papel/luz, não um branco chapado).
    float shade = 1.0 - 0.12 * pow(abs(across - 0.5) * 2.0, 2.0);
    vec3 panel = mix(vec3(0.98, 0.98, 0.97) * shade, vec3(0.03) + 0.04 * shade, clamp(p_color, 0.0, 1.0));
    // Brilho fino na borda da frente.
    float glow = exp(-abs(x - lead) / (0.012 + s)) * 0.35 * (1.0 - clamp(p_color, 0.0, 1.0));
    vec3 c = mix(scene.rgb, panel, bar) + glow;
    return vec4(clamp(c, 0.0, 1.0), 1.0);
}
