"""GLSL ES 1.0 bodies for the generated effect library. Parameters are p_<id> floats (declared by the host)."""

HELPERS = """float rnd(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
"""

S = {}  # id -> (params[(id,name,min,max)], glsl)

S["lib_tint"] = ([("r", "Vermelho", 0, 1), ("g", "Verde", 0, 1), ("b", "Azul", 0, 1), ("amount", "Força", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float l = dot(s.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 t = vec3(p_r, p_g, p_b);
    vec3 c = s.rgb * mix(vec3(1.0), t * 2.0, p_amount);
    c += t * p_amount * 0.08 * (1.0 - l);
    return vec4(clamp(c, 0.0, 1.0), s.a);
}""")

S["lib_duotone"] = ([("sr", "Sombra R", 0, 1), ("sg", "Sombra G", 0, 1), ("sb", "Sombra B", 0, 1),
                     ("hr", "Luz R", 0, 1), ("hg", "Luz G", 0, 1), ("hb", "Luz B", 0, 1), ("amount", "Força", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float l = dot(s.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 duo = mix(vec3(p_sr, p_sg, p_sb), vec3(p_hr, p_hg, p_hb), smoothstep(0.05, 0.95, l));
    return vec4(mix(s.rgb, duo, p_amount), s.a);
}""")

S["lib_grade"] = ([("lift", "Sombras", -0.2, 0.2), ("gamma", "Gama", 0.4, 2.2), ("gain", "Brilho", 0.5, 1.6),
                   ("sat", "Saturação", 0, 2), ("temp", "Temperatura", -1, 1), ("tint", "Matiz", -1, 1), ("contrast", "Contraste", 0.5, 1.8)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    vec3 c = s.rgb;
    c = pow(max(c, vec3(0.0)), vec3(1.0 / p_gamma));
    c = c * p_gain + vec3(p_lift);
    c = (c - 0.5) * p_contrast + 0.5;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, p_sat);
    c.r += p_temp * 0.08;
    c.b -= p_temp * 0.08;
    c.g += p_tint * 0.05;
    c.r -= p_tint * 0.02;
    c.b -= p_tint * 0.02;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}""")

S["lib_split"] = ([("amount", "Distância", 0, 0.05), ("angle", "Ângulo", 0, 360), ("wobble", "Oscilação", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    float a = radians(p_angle);
    vec2 d = vec2(cos(a), sin(a) * uResolution.x / uResolution.y) * p_amount * (1.0 + p_wobble * sin(uTime * 8.0));
    float r = texture2D(uTexSampler, uv + d).r;
    vec4 g = texture2D(uTexSampler, uv);
    float b = texture2D(uTexSampler, uv - d).b;
    return vec4(r, g.g, b, g.a);
}""")

S["lib_dirblur"] = ([("amount", "Distância", 0, 0.12), ("angle", "Ângulo", 0, 360)], """vec4 reclyEffect(vec2 uv) {
    float a = radians(p_angle);
    vec2 d = vec2(cos(a), sin(a) * uResolution.x / uResolution.y) * p_amount;
    vec4 c = texture2D(uTexSampler, uv) * 0.2;
    c += texture2D(uTexSampler, uv + d * 0.33) * 0.16;
    c += texture2D(uTexSampler, uv - d * 0.33) * 0.16;
    c += texture2D(uTexSampler, uv + d * 0.66) * 0.11;
    c += texture2D(uTexSampler, uv - d * 0.66) * 0.11;
    c += texture2D(uTexSampler, uv + d) * 0.13;
    c += texture2D(uTexSampler, uv - d) * 0.13;
    return c;
}""")

S["lib_zoomblur"] = ([("amount", "Força", 0, 0.3), ("cx", "Centro X", 0, 1), ("cy", "Centro Y", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec2 d = (uv - vec2(p_cx, p_cy)) * p_amount;
    vec4 c = texture2D(uTexSampler, uv) * 0.25;
    c += texture2D(uTexSampler, uv - d * 0.25) * 0.2;
    c += texture2D(uTexSampler, uv - d * 0.5) * 0.17;
    c += texture2D(uTexSampler, uv - d * 0.75) * 0.14;
    c += texture2D(uTexSampler, uv - d) * 0.12;
    c += texture2D(uTexSampler, uv - d * 1.25) * 0.08;
    c += texture2D(uTexSampler, uv - d * 1.5) * 0.04;
    return c;
}""")

S["lib_vig"] = ([("amount", "Força", 0, 1), ("size", "Tamanho", 0.2, 1.4), ("soft", "Suavidade", 0.05, 1),
                 ("r", "Vermelho", 0, 1), ("g", "Verde", 0, 1), ("b", "Azul", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float d = distance(uv, vec2(0.5)) * 1.4142;
    float v = smoothstep(p_size, p_size + p_soft, d);
    return vec4(mix(s.rgb, vec3(p_r, p_g, p_b), v * p_amount), s.a);
}""")

S["lib_pixel"] = ([("cells", "Blocos", 8, 220), ("mixv", "Mistura", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec2 g = vec2(p_cells, p_cells * uResolution.y / uResolution.x);
    vec2 q = (floor(uv * g) + 0.5) / g;
    vec4 s = texture2D(uTexSampler, uv);
    vec4 p = texture2D(uTexSampler, q);
    return vec4(mix(s.rgb, p.rgb, p_mixv), s.a);
}""")

S["lib_halftone"] = ([("cells", "Pontos", 20, 160), ("soft", "Suavidade", 0.05, 1), ("amount", "Força", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    vec2 g = vec2(p_cells, p_cells * uResolution.y / uResolution.x);
    vec2 id = floor(uv * g);
    vec2 f = fract(uv * g) - 0.5;
    vec4 c = texture2D(uTexSampler, (id + 0.5) / g);
    float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    float radius = sqrt(l) * 0.72;
    float m = smoothstep(radius, radius - 0.25 * p_soft - 0.02, length(f));
    return vec4(mix(s.rgb, c.rgb * m, p_amount), s.a);
}""")

S["lib_scan"] = ([("lines", "Linhas", 80, 900), ("strength", "Força", 0, 1), ("roll", "Rolagem", 0, 1), ("flick", "Tremida", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    float y = uv.y + p_roll * 0.05 * sin(uTime * 1.3 + uv.y * 4.0);
    vec4 s = texture2D(uTexSampler, vec2(uv.x, fract(y)));
    float line = 0.5 + 0.5 * sin(uv.y * p_lines * 3.14159);
    float band = smoothstep(0.0, 1.0, fract(uv.y - uTime * 0.12 * p_roll));
    float f = 1.0 + (rnd(vec2(floor(uTime * 30.0), 1.0)) - 0.5) * 0.12 * p_flick;
    vec3 c = s.rgb * (1.0 - p_strength * 0.45 * (1.0 - line)) * mix(1.0, 0.85 + 0.3 * band, p_roll) * f;
    return vec4(c, s.a);
}""")

S["lib_slice"] = ([("amount", "Deslocamento", 0, 0.2), ("rows", "Faixas", 4, 80), ("rate", "Velocidade", 1, 30), ("rgb", "Cor separada", 0, 0.03)], """vec4 reclyEffect(vec2 uv) {
    float row = floor(uv.y * p_rows);
    float t = floor(uTime * p_rate);
    float on = step(0.72, rnd(vec2(row, t)));
    float shift = (rnd(vec2(row, t + 7.0)) - 0.5) * p_amount * on;
    vec2 u = vec2(fract(uv.x + shift), uv.y);
    float r = texture2D(uTexSampler, u + vec2(p_rgb * on, 0.0)).r;
    vec4 g = texture2D(uTexSampler, u);
    float b = texture2D(uTexSampler, u - vec2(p_rgb * on, 0.0)).b;
    return vec4(r, g.g, b, g.a);
}""")

S["lib_wave"] = ([("amp", "Altura", 0, 0.08), ("freq", "Frequência", 1, 50), ("speed", "Velocidade", 0, 12), ("dir", "Direção", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec2 u = uv;
    u.x += sin(uv.y * p_freq + uTime * p_speed) * p_amp * (1.0 - p_dir);
    u.y += sin(uv.x * p_freq + uTime * p_speed) * p_amp * p_dir;
    return texture2D(uTexSampler, clamp(u, 0.0, 1.0));
}""")

S["lib_swirl"] = ([("angle", "Giro", -8, 8), ("radius", "Raio", 0.1, 1), ("spin", "Rotação", 0, 3), ("cx", "Centro X", 0, 1), ("cy", "Centro Y", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec2 c = vec2(p_cx, p_cy);
    vec2 d = (uv - c) * vec2(uResolution.x / uResolution.y, 1.0);
    float r = length(d);
    float k = smoothstep(p_radius, 0.0, r);
    float a = k * k * p_angle + uTime * p_spin * k;
    float cs = cos(a);
    float sn = sin(a);
    d = vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
    vec2 u = c + d * vec2(uResolution.y / uResolution.x, 1.0);
    return texture2D(uTexSampler, clamp(u, 0.0, 1.0));
}""")

S["lib_kaleido"] = ([("segs", "Segmentos", 2, 16), ("zoom", "Zoom", 0.5, 3), ("spin", "Rotação", 0, 2)], """vec4 reclyEffect(vec2 uv) {
    vec2 p = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
    float a = atan(p.y, p.x) + uTime * p_spin * 0.3;
    float r = length(p) / p_zoom;
    float seg = 6.2831853 / floor(p_segs + 0.5);
    a = abs(mod(a, seg) - seg * 0.5);
    vec2 u = 0.5 + r * vec2(cos(a), sin(a)) * vec2(uResolution.y / uResolution.x, 1.0);
    return texture2D(uTexSampler, abs(mod(u, 2.0) - 1.0) * -1.0 + 1.0);
}""")

S["lib_fold"] = ([("mode", "Modo", 0, 3), ("pos", "Posição", 0.2, 0.8), ("blend", "Mistura", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec2 u = uv;
    float m = floor(p_mode + 0.5);
    if (m < 0.5) u.x = mix(uv.x, 2.0 * p_pos - uv.x, step(p_pos, uv.x));
    else if (m < 1.5) u.x = mix(uv.x, 2.0 * p_pos - uv.x, step(uv.x, p_pos));
    else if (m < 2.5) u.y = mix(uv.y, 2.0 * p_pos - uv.y, step(p_pos, uv.y));
    else u.y = mix(uv.y, 2.0 * p_pos - uv.y, step(uv.y, p_pos));
    vec4 s = texture2D(uTexSampler, uv);
    vec4 f = texture2D(uTexSampler, clamp(u, 0.0, 1.0));
    return vec4(mix(s.rgb, f.rgb, p_blend), s.a);
}""")

S["lib_pulse"] = ([("amount", "Força", 0, 1), ("rate", "Batidas por s", 0.5, 8), ("sharp", "Agudeza", 1, 12), ("warm", "Calor", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float p = pow(max(0.0, sin(uTime * p_rate * 6.2831853)), p_sharp);
    vec3 tint = mix(vec3(1.0), vec3(1.0, 0.78, 0.48), p_warm);
    return vec4(clamp(s.rgb + tint * p * p_amount * 0.7, 0.0, 1.0), s.a);
}""")

S["lib_leak"] = ([("r", "Vermelho", 0, 1), ("g", "Verde", 0, 1), ("b", "Azul", 0, 1), ("amount", "Força", 0, 1),
                  ("angle", "Ângulo", 0, 360), ("width", "Largura", 0.1, 1), ("drift", "Deriva", 0, 2)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float a = radians(p_angle);
    float t = dot(uv - 0.5, vec2(cos(a), sin(a))) + 0.5 + sin(uTime * p_drift) * 0.18;
    float leak = smoothstep(0.0, p_width, 1.0 - abs(t - 0.5) * 2.0);
    leak = leak * leak;
    vec3 col = vec3(p_r, p_g, p_b);
    vec3 c = 1.0 - (1.0 - s.rgb) * (1.0 - col * leak * p_amount);
    return vec4(c, s.a);
}""")

S["lib_grain"] = ([("grain", "Granulado", 0, 1), ("dust", "Poeira", 0, 1), ("flick", "Cintilação", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float n = rnd(uv * uResolution + fract(uTime) * 91.7) - 0.5;
    float d = step(0.9992, rnd(floor(uv * vec2(180.0, 100.0)) + floor(uTime * 12.0)));
    float f = 1.0 + (rnd(vec2(floor(uTime * 24.0), 3.0)) - 0.5) * 0.25 * p_flick;
    vec3 c = s.rgb * f + n * 0.22 * p_grain + d * p_dust * 0.6;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}""")

S["lib_poster"] = ([("levels", "Níveis", 2, 14), ("sat", "Saturação", 0, 2), ("amount", "Força", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float l = dot(s.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 c = mix(vec3(l), s.rgb, p_sat);
    vec3 q = floor(c * p_levels + 0.5) / p_levels;
    return vec4(mix(s.rgb, clamp(q, 0.0, 1.0), p_amount), s.a);
}""")

S["lib_edge"] = ([("strength", "Contorno", 0, 6), ("r", "Vermelho", 0, 1), ("g", "Verde", 0, 1), ("b", "Azul", 0, 1), ("base", "Imagem", 0, 1)], """float lumaAt(vec2 u) {
    return dot(texture2D(uTexSampler, u).rgb, vec3(0.2126, 0.7152, 0.0722));
}
vec4 reclyEffect(vec2 uv) {
    vec2 px = 1.0 / uResolution;
    float tl = lumaAt(uv + vec2(-px.x, -px.y));
    float t = lumaAt(uv + vec2(0.0, -px.y));
    float tr = lumaAt(uv + vec2(px.x, -px.y));
    float l = lumaAt(uv + vec2(-px.x, 0.0));
    float r = lumaAt(uv + vec2(px.x, 0.0));
    float bl = lumaAt(uv + vec2(-px.x, px.y));
    float b = lumaAt(uv + vec2(0.0, px.y));
    float br = lumaAt(uv + vec2(px.x, px.y));
    float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
    float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    float e = clamp(length(vec2(gx, gy)) * p_strength, 0.0, 1.0);
    vec4 s = texture2D(uTexSampler, uv);
    return vec4(clamp(s.rgb * p_base + vec3(p_r, p_g, p_b) * e, 0.0, 1.0), s.a);
}""")

S["lib_bokeh"] = ([("radius", "Raio", 0, 40), ("bright", "Brilho", 0, 2), ("amount", "Força", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec2 d = p_radius / uResolution;
    vec4 s = texture2D(uTexSampler, uv);
    vec3 c = s.rgb;
    c += texture2D(uTexSampler, uv + d * vec2(1.0, 0.0)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(-1.0, 0.0)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(0.0, 1.0)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(0.0, -1.0)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(0.7, 0.7)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(-0.7, 0.7)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(0.7, -0.7)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(-0.7, -0.7)).rgb;
    c /= 9.0;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c += c * smoothstep(0.55, 1.0, l) * p_bright;
    return vec4(mix(s.rgb, clamp(c, 0.0, 1.0), p_amount), s.a);
}""")

S["lib_punch"] = ([("amount", "Zoom", 0, 0.5), ("rate", "Batidas por s", 0.5, 6), ("sharp", "Agudeza", 1, 10)], """vec4 reclyEffect(vec2 uv) {
    float p = pow(abs(sin(uTime * p_rate * 3.14159265)), p_sharp);
    vec2 u = 0.5 + (uv - 0.5) / (1.0 + p_amount * p);
    return texture2D(uTexSampler, u);
}""")

S["lib_camshake"] = ([("amount", "Força", 0, 0.08), ("speed", "Velocidade", 1, 50), ("rot", "Rotação", 0, 0.12)], """vec4 reclyEffect(vec2 uv) {
    float t = uTime * p_speed;
    vec2 off = vec2(sin(t * 1.7) + sin(t * 3.1) * 0.5, cos(t * 2.3) + cos(t * 4.7) * 0.5) * p_amount * 0.5;
    float a = sin(t * 1.9) * p_rot;
    vec2 p = (uv - 0.5) * (1.0 - p_amount * 1.6);
    p = vec2(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a));
    return texture2D(uTexSampler, clamp(p + 0.5 + off, 0.0, 1.0));
}""")

S["lib_bars"] = ([("size", "Tamanho", 0, 0.3), ("r", "Vermelho", 0, 1), ("g", "Verde", 0, 1), ("b", "Azul", 0, 1),
                  ("soft", "Suavidade", 0, 0.05), ("side", "Laterais", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    float e = mix(abs(uv.y - 0.5), abs(uv.x - 0.5), p_side);
    float m = smoothstep(0.5 - p_size - p_soft, 0.5 - p_size, e);
    return vec4(mix(s.rgb, vec3(p_r, p_g, p_b), m), s.a);
}""")

S["lib_rays"] = ([("amount", "Força", 0, 1.5), ("count", "Raios", 4, 40), ("cx", "Centro X", 0, 1), ("cy", "Centro Y", 0, 1),
                  ("r", "Vermelho", 0, 1), ("g", "Verde", 0, 1), ("b", "Azul", 0, 1), ("speed", "Giro", 0, 3)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    vec2 p = (uv - vec2(p_cx, p_cy)) * vec2(uResolution.x / uResolution.y, 1.0);
    float a = atan(p.y, p.x);
    float ray = pow(0.5 + 0.5 * sin(a * p_count + uTime * p_speed), 3.0);
    float fall = exp(-length(p) * 2.2);
    vec3 c = s.rgb + vec3(p_r, p_g, p_b) * ray * fall * p_amount;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}""")

S["lib_rgbwave"] = ([("amount", "Força", 0, 0.05), ("freq", "Frequência", 2, 60), ("speed", "Velocidade", 0, 12)], """vec4 reclyEffect(vec2 uv) {
    float o = sin(uv.y * p_freq + uTime * p_speed) * p_amount;
    float r = texture2D(uTexSampler, vec2(uv.x + o, uv.y)).r;
    vec4 g = texture2D(uTexSampler, uv);
    float b = texture2D(uTexSampler, vec2(uv.x - o, uv.y)).b;
    return vec4(r, g.g, b, g.a);
}""")

S["lib_crt"] = ([("curve", "Curvatura", 0, 0.4), ("scan", "Linhas", 0, 1), ("mask", "Máscara RGB", 0, 1), ("vig", "Vinheta", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec2 p = uv * 2.0 - 1.0;
    p *= 1.0 + dot(p, p) * p_curve * 0.25;
    vec2 u = p * 0.5 + 0.5;
    float inside = step(0.0, u.x) * step(u.x, 1.0) * step(0.0, u.y) * step(u.y, 1.0);
    vec4 s = texture2D(uTexSampler, clamp(u, 0.0, 1.0));
    float line = 0.5 + 0.5 * sin(u.y * uResolution.y * 1.5);
    float tri = mod(floor(uv.x * uResolution.x), 3.0);
    vec3 m = vec3(step(tri, 0.5), step(0.5, tri) * step(tri, 1.5), step(1.5, tri));
    vec3 c = s.rgb * (1.0 - p_scan * 0.4 * (1.0 - line));
    c = mix(c, c * (0.6 + 0.8 * m), p_mask * 0.5);
    c *= 1.0 - p_vig * 0.6 * dot(p, p) * 0.5;
    return vec4(c * inside, s.a);
}""")

S["lib_haze"] = ([("amount", "Força", 0, 0.03), ("scale", "Escala", 2, 40), ("speed", "Velocidade", 0, 6), ("warm", "Calor", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    float n = sin(uv.y * p_scale + uTime * p_speed) * sin(uv.x * p_scale * 0.7 - uTime * p_speed * 0.8);
    vec2 u = uv + vec2(n, cos(uv.x * p_scale + uTime * p_speed)) * p_amount;
    vec4 s = texture2D(uTexSampler, clamp(u, 0.0, 1.0));
    s.rgb += vec3(0.08, 0.03, -0.04) * p_warm;
    return s;
}""")

S["lib_sparkle"] = ([("density", "Densidade", 4, 36), ("size", "Tamanho", 0.05, 0.5), ("speed", "Velocidade", 0, 8), ("amount", "Força", 0, 1.5),
                     ("r", "Vermelho", 0, 1), ("g", "Verde", 0, 1), ("b", "Azul", 0, 1)], """vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    vec2 g = vec2(p_density, p_density * uResolution.y / uResolution.x);
    vec2 id = floor(uv * g);
    vec2 f = fract(uv * g) - 0.5;
    float h = rnd(id);
    vec2 pos = vec2(rnd(id + 3.1), rnd(id + 7.7)) - 0.5;
    pos *= 0.6;
    float tw = max(0.0, sin(uTime * p_speed + h * 6.2831853));
    tw = tw * tw * step(0.55, h);
    vec2 d = abs(f - pos);
    float star = max(0.0, 1.0 - (d.x / (p_size * 0.35) + d.y / (p_size * 0.35)) ) * 0.4;
    star += max(0.0, 1.0 - d.x / (p_size * 0.07 + 0.001) - d.y / p_size) + max(0.0, 1.0 - d.y / (p_size * 0.07 + 0.001) - d.x / p_size);
    vec3 c = s.rgb + vec3(p_r, p_g, p_b) * star * tw * p_amount;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}""")

S["lib_weather"] = ([("density", "Densidade", 6, 60), ("speed", "Velocidade", 0, 5), ("size", "Tamanho", 0.05, 0.5), ("slant", "Inclinação", -1, 1),
                     ("stretch", "Alongar", 0, 1), ("amount", "Força", 0, 1.5), ("r", "Vermelho", 0, 1), ("g", "Verde", 0, 1), ("b", "Azul", 0, 1)], """float flake(vec2 p, float density, float seed) {
    vec2 g = vec2(p.x * density, p.y * density * mix(1.0, 0.22, p_stretch));
    vec2 id = floor(g);
    vec2 f = fract(g) - 0.5;
    float h = rnd(id + seed);
    f.x += (h - 0.5) * 0.7;
    float on = step(0.55, rnd(id + seed + 11.0));
    float shape = 1.0 - smoothstep(p_size * 0.5, p_size, length(f * vec2(1.0, mix(1.0, 0.18, p_stretch))));
    return shape * on;
}
vec4 reclyEffect(vec2 uv) {
    vec4 s = texture2D(uTexSampler, uv);
    vec2 p = vec2(uv.x * uResolution.x / uResolution.y, uv.y);
    p.x += p.y * p_slant;
    float f = flake(p + vec2(0.0, uTime * p_speed), p_density, 0.0);
    f += flake(p * 1.7 + vec2(0.3, uTime * p_speed * 1.4), p_density, 5.0) * 0.7;
    f += flake(p * 2.6 + vec2(0.6, uTime * p_speed * 1.9), p_density, 9.0) * 0.5;
    vec3 c = s.rgb + vec3(p_r, p_g, p_b) * f * p_amount;
    return vec4(clamp(c, 0.0, 1.0), s.a);
}""")

# Shaders whose source uses rnd() get the shared helper prepended.
for key, (params, body) in list(S.items()):
    if "rnd(" in body and "float rnd(" not in body:
        S[key] = (params, HELPERS + body)
