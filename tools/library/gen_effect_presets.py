"""Gera editor/effect_presets.json: os 20 presets originais (intactos) + 280 presets novos.

Os presets novos usam TODOS os efeitos de effects.json (cobertura verificada no fim do script).
Cada preset empilha de 1 a 5 efeitos; valores omitidos usam o padrao do efeito.

Regras de runtime respeitadas (ver EffectPresets.kt, EffectTools.kt):
  - ids [a-z][a-z0-9_]{0,63}, unicos; nome nao vazio; no maximo 5 nos por preset (a pilha do clipe aceita 12);
  - intensidade em [0,1]; valores dentro de [min,max] do parametro; no maximo 16 valores por no;
  - categoria "Gaming" so para impactos curtos (a galeria aplica esses presets em ~350 ms);
  - custo: no maximo 3 efeitos MEDIUM e 2 temporais por preset, para a previa continuar fluida.

Uso: python3 tools/library/gen_effect_presets.py
"""
import colorsys
import json
import math
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "editor-engine/src/main/assets/editor")
PRESETS_FILE = os.path.join(ASSETS, "effect_presets.json")

ORIGINAL_IDS = [
    "cyber_signal", "impact_combo", "soft_dream", "broken_tape", "neon_arcade", "liquid_lens", "clean_film", "film_35mm",
    "vintage_film", "dark_cinema", "warm_cinema", "cold_cinema", "kill_impact", "headshot", "damage", "explosion",
    "speed", "beat_zoom", "victory", "film_look",
]
TARGET_TOTAL = 300
MAX_NODES = 5
MAX_MEDIUM = 3
MAX_TEMPORAL = 2

FX = {e["id"]: e for e in json.load(open(os.path.join(ASSETS, "effects.json"), encoding="utf-8"))}
DEFAULTS = {i: {p["id"]: p["default"] for p in e["parameters"]} for i, e in FX.items()}
GENERATED = []  # (name, category, [(asset, intensity, values)])


def N(asset, intensity=.7, **values):
    return (asset, intensity, values)


def add(name, category, nodes):
    GENERATED.append((name, category, list(nodes)))


def slug(text):
    table = {"á": "a", "à": "a", "ã": "a", "â": "a", "é": "e", "ê": "e", "í": "i", "ó": "o", "ô": "o", "õ": "o", "ú": "u", "ç": "c", "&": "e", "º": ""}
    out = "".join(table.get(c, c) for c in text.lower())
    return re.sub(r"_+", "_", "".join(c if c.isalnum() else "_" for c in out)).strip("_")


def ids(prefix, suffixes=None, count=None, start=1):
    if suffixes is not None:
        return [f"{prefix}{s}" for s in suffixes]
    return [f"{prefix}{i}" for i in range(start, start + count)]


# ── Efeitos de apoio (acabamento) ────────────────────────────────────────────
def vignette(i=.35, amount=.4, softness=.45): return N("recly_vignette", i, amount=amount, softness=softness)
def grain(i=.25, amount=.06, size=1.8, mono=1.0): return N("recly_film_grain", i, amount=amount, size=size, monochrome=mono)
def glow(i=.3, radius=8, threshold=.5): return N("recly_glow", i, radius=radius, threshold=threshold)
def halation(i=.28, radius=9, threshold=.62, amount=.5, warmth=.6): return N("recly_halation", i, radius=radius, threshold=threshold, amount=amount, warmth=warmth)
def bloom(i=.3, radius=10, threshold=.6, gain=.7): return N("recly_bloom", i, radius=radius, threshold=threshold, gain=gain)
def sharpen(i=.4, amount=.3): return N("recly_sharpen", i, amount=amount)
def noise(i=.2, amount=.06, speed=10): return N("recly_noise", i, amount=amount, speed=speed)
def flicker(i=.2, amount=.05, speed=1.5): return N("recly_flicker", i, amount=amount, speed=speed)
def chromatic(i=.3, amount=5): return N("recly_chromatic", i, amount=amount)


SUPPORT_CYCLE = [
    lambda: vignette(.35, .4, .45), lambda: grain(.25, .05, 1.6), lambda: glow(.3, 8, .55), lambda: halation(.28, 9, .65, .45, .55),
    lambda: sharpen(.4, .3), lambda: noise(.2, .05, 8), lambda: bloom(.28, 10, .62, .6), lambda: flicker(.18, .04, 1.4),
    lambda: vignette(.45, .55, .4), lambda: grain(.3, .08, 2.2),
]


def support(index, count=1):
    return [SUPPORT_CYCLE[(index + k * 3) % len(SUPPORT_CYCLE)]() for k in range(count)]


# ── COR: 168 efeitos de cor, agrupados por matiz ────────────────────────────
def temp_tint_hue(temp, tint):
    parts = [(30, max(temp, 0)), (215, max(-temp, 0)), (300, max(tint, 0)), (130, max(-tint, 0))]
    total = sum(w for _, w in parts)
    if total < .12:
        return None
    x = sum(w * math.cos(math.radians(h)) for h, w in parts)
    y = sum(w * math.sin(math.radians(h)) for h, w in parts)
    return math.degrees(math.atan2(y, x)) % 360


def rgb_hue(r, g, b):
    h, s, _ = colorsys.rgb_to_hsv(r, g, b)
    return None if s < .2 else h * 360


def color_hue(asset_id):
    d = DEFAULTS[asset_id]
    if asset_id.startswith("recly_tom_"):
        return rgb_hue(d["r"], d["g"], d["b"])
    if asset_id.startswith("recly_duo_"):
        return rgb_hue(d["hr"], d["hg"], d["hb"])
    if asset_id.startswith("recly_grade_"):
        return None if d["sat"] < .3 and abs(d["temp"]) < .3 else temp_tint_hue(d["temp"], d["tint"])
    if asset_id.startswith("color_"):
        if d["saturation"] <= -.85:
            return None
        if d["highlight_tint"] >= .4:
            return d["highlight_hue"] * 360
        return temp_tint_hue(d["temperature"], d["tint"])
    if asset_id.startswith("style_"):
        return d["hue_a"] * 360 if d["hue_a"] > 0 else None
    raise KeyError(asset_id)


TYPE_ORDER = {"color_": 0, "recly_grade_": 1, "style_": 2, "recly_duo_": 3, "recly_tom_": 4}


def color_type(asset_id):
    return next(prefix for prefix in TYPE_ORDER if asset_id.startswith(prefix))


HEAVY = ("color_", "recly_grade_")  # looks completos: empilhar varios sobreprocessa a imagem
HEAVY_INTENSITY = {1: .85, 2: .62, 3: .5}


def chroma(asset_id):
    """Quanta cor o efeito deixa: usado para agrupar os neutros (P&B juntos, vivos juntos)."""
    d = DEFAULTS[asset_id]
    kind = color_type(asset_id)
    if kind == "recly_grade_":
        return d["sat"]
    if kind == "color_":
        return 1 + d["saturation"]
    if kind == "recly_duo_":
        return .65
    if kind == "style_":
        return 9.0  # estilizacoes (termica, sketch...) ficam juntas no fim, longe dos looks de cor
    return .5


def color_intensity(asset_id, heavy_count, tint_count):
    kind = color_type(asset_id)
    if kind in HEAVY:
        return HEAVY_INTENSITY[max(1, min(3, heavy_count))]
    if kind == "style_":
        return .45 if heavy_count == 0 else .35
    base = .5 if kind == "recly_duo_" else (.45 if asset_id.endswith("_suave") else .38)
    if tint_count >= 2:
        base *= .8
    if heavy_count >= 2:
        base *= .85
    return base


def clean_name(asset_id):
    name = FX[asset_id]["name"]
    for prefix in ("Duotone ", "Tom ", "Grade ", "Estilo "):
        if name.startswith(prefix):
            name = name[len(prefix):]
    return name[:1].upper() + name[1:]


def build_color(count):
    pool = [i for i, e in FX.items() if e["category"] == "Color"]
    assert len(pool) == 168, len(pool)
    chromatic_items = sorted((i for i in pool if color_hue(i) is not None), key=lambda i: (color_hue(i), TYPE_ORDER[color_type(i)], i))
    neutral_items = sorted((i for i in pool if color_hue(i) is None), key=lambda i: (round(chroma(i), 2), TYPE_ORDER[color_type(i)], i))
    ordered = chromatic_items + neutral_items
    base, extra = divmod(len(ordered), count)
    sizes = [base] * count
    # base+1 nos extras espalhados de forma uniforme (extra pode ser 0)
    for k in range(extra):
        sizes[(k * count) // extra] += 1
    assert sum(sizes) == len(ordered)
    used_names = set()
    cursor = 0
    for index, size in enumerate(sizes):
        chunk = ordered[cursor:cursor + size]
        cursor += size
        chunk.sort(key=lambda i: (TYPE_ORDER[color_type(i)], i))
        tints = sum(1 for i in chunk if color_type(i) in ("recly_tom_", "recly_duo_"))
        heavy = sum(1 for i in chunk if color_type(i) in HEAVY)
        nodes = [N(i, round(color_intensity(i, heavy, tints), 2)) for i in chunk]
        nodes += support(index, min(2, MAX_NODES - len(nodes)))
        core = clean_name(chunk[0])
        name = core
        if name in used_names and len(chunk) > 1:
            name = f"{core} · {clean_name(chunk[1]).removeprefix('Look ')}"
        n = 2
        while name in used_names:
            name = f"{core} {n}"
            n += 1
        used_names.add(name)
        add(name, "Cor", nodes)


# ── CINEMA ───────────────────────────────────────────────────────────────────
def build_cinema():
    grades = ["recly_grade_cinema_quente", "recly_grade_cinema_frio", "recly_grade_teal_e_laranja", "recly_grade_filme_antigo",
              "recly_grade_matte_suave", "recly_grade_documentario", "recly_grade_kodak", "recly_grade_fuji"]
    bars = []
    for color, label in (("preto", "Preto"), ("branco", "Branco"), ("vinho", "Vinho"), ("azul", "Azul")):
        for size, size_label in (("fina", "Fino"), ("cinema", ""), ("larga", "Largo")):
            bars.append((f"recly_barras_{color}_{size}", f"Cinema {label} {size_label}".strip()))
    bars += [("recly_barras_laterais_preto", "Pillarbox Preto"), ("recly_barras_laterais_branco", "Pillarbox Branco")]
    extras = [lambda: bloom(.3, 10, .62, .6), lambda: halation(.3, 9, .64, .5, .6), lambda: vignette(.35, .4, .45), lambda: grain(.25, .05, 1.6)]
    for index, (bar, name) in enumerate(bars):
        add(name, "Cinema", [N(bar, .95), N(grades[index % len(grades)], .7), extras[index % len(extras)](), extras[(index + 2) % len(extras)]()])
    add("Noite Americana", "Cinema", [N("recly_grade_madrugada", .8), vignette(.5, .6, .4), bloom(.3, 12, .6, .7), grain(.25, .06, 2)])
    add("Blockbuster", "Cinema", [N("recly_grade_teal_e_laranja", .85), bloom(.3, 10, .6, .6), sharpen(.4, .35), vignette(.3, .35, .5)])
    add("Thriller Frio", "Cinema", [N("recly_grade_cinema_frio", .85), chromatic(.3, 4), grain(.3, .07, 2), vignette(.45, .5, .4)])
    add("Épico Dourado", "Cinema", [N("recly_grade_hora_dourada", .8), halation(.35, 11, .6, .6, .8), bloom(.3, 12, .58, .7), vignette(.35, .4, .45)])


# ── FILME ────────────────────────────────────────────────────────────────────
def build_film():
    colors = [("laranja", "Laranja"), ("vermelho", "Vermelho"), ("rosa", "Rosa"), ("dourado", "Dourado"), ("amarelo", "Amarelo"), ("roxo", "Roxo"),
              ("azul", "Azul"), ("ciano", "Ciano"), ("verde", "Verde"), ("magenta", "Magenta"), ("branco", "Branco"), ("pessego", "Pêssego")]
    extras = [lambda: flicker(.2, .05, 1.4), lambda: vignette(.3, .35, .5), lambda: halation(.28, 9, .66, .45, .6), lambda: grain(.2, .04, 1.6)]
    for index, (color, label) in enumerate(colors):
        add(f"Vazamento {label}", "Film", [N(f"recly_vazamento_{color}_a", .6), N(f"recly_vazamento_{color}_b", .45),
                                           N(f"recly_granulado_{index + 1}", .4), extras[index % len(extras)]()])
    add("Super 16", "Film", [N("super_16_look", .65), grain(.3, .09, 2.4), vignette(.35, .4, .45)])
    add("Película 8mm", "Film", [N("film_8mm", .7), flicker(.22, .06, 1.3), vignette(.4, .45, .42)])
    add("Filme Antigo", "Film", [N("old_film", .7), vignette(.4, .5, .4), grain(.25, .06, 2)])
    add("Poeira e Riscos", "Film", [N("dust_scratches", .65), noise(.2, .05, 8), vignette(.3, .35, .5)])
    add("Filme Queimado", "Film", [N("film_burn", .6), halation(.35, 10, .6, .6, .8), grain(.25, .06, 2)])
    add("Sépia Clássico", "Film", [N("sepia_film", .7), vignette(.45, .55, .4), grain(.25, .05, 1.8)])
    add("Camcorder 90s", "Film", [N("retro_camcorder", .7), N("recly_vhs", .4, noise=.08, jitter=4, speed=5), noise(.2, .06, 10)])
    add("Tela da Memória", "Film", [N("recly_crt", .55), vignette(.35, .4, .45), grain(.2, .05, 1.6)])
    add("Halação Suave", "Film", [halation(.45, 12, .56, .7, .7), grain(.2, .05, 1.8), vignette(.25, .3, .5)])
    add("Ruído Analógico", "Film", [noise(.3, .09, 14), grain(.3, .09, 2.4), flicker(.2, .05, 1.8)])
    add("Vinheta Clássica", "Film", [vignette(.55, .7, .38), grain(.2, .05, 1.8), halation(.22, 8, .68, .4, .55)])
    add("Sonho em 8mm", "Film", [N("film_8mm", .5), N("super_16_look", .4), vignette(.35, .4, .45)])


# ── GAMING: impactos curtos ─────────────────────────────────────────────────
def build_gaming():
    levels = ["Pulso", "Toque", "Soco", "Pancada", "Estouro", "Colapso"]
    speeds = [("lento", "Lento", "Lenta"), ("medio", "Médio", "Média"), ("rapido", "Rápido", "Rápida")]
    feminine = {"Pancada"}
    shakes = [["micro_shake", "vibration", "jitter"], ["handheld_camera", "recly_shake", "beat_shake"], ["bounce_shake", "rotation_shake", "recly_shake"],
              ["impact_shake", "bounce_shake", "jitter"], ["heavy_shake", "impact_shake", "rotation_shake"], ["heavy_shake", "impact_shake", "beat_shake"]]
    index = 0
    for level, name in enumerate(levels, start=1):
        for s, (speed, masculine, female) in enumerate(speeds):
            label = female if name in feminine else masculine
            flash_n = index % 12 + 1
            nodes = [N(f"recly_zoom_batida_{level}_{speed}", .8), N(f"recly_flash_{flash_n}", .3 + .04 * level),
                     N(shakes[level - 1][s], .3 + .05 * level)]
            if level >= 4:
                nodes.append(N(["recly_rgb_0_forte", "recly_rgb_45_forte", "recly_rgb_90_forte"][s], .45))
            add(f"{name} {label}", "Gaming", nodes)
            index += 1


# ── CÂMERA ───────────────────────────────────────────────────────────────────
def build_camera():
    names = ["Respiração", "Mão Leve", "Passo Calmo", "Caminhada", "Corrida Leve", "Corrida", "Deriva Horizontal", "Balanço Lateral",
             "Trote", "Sprint", "Flutuar", "Estrada Irregular"]
    for index, name in enumerate(names, start=1):
        add(name, "Camera", [N(f"recly_camera_{index}", .75)] + support(index, 2)[:2])


# ── RETRÔ ────────────────────────────────────────────────────────────────────
def build_retro():
    vhs = ["Fita Gasta", "Locadora", "Gravação Caseira", "Replay 90", "Sinal Fraco", "Tracking Torto"]
    for index, name in enumerate(vhs, start=1):
        add(name, "Retro", [N(f"recly_vhs_{index}", .7), N(f"recly_linhas_{index}", .5), noise(.2, .05, 8) if index % 2 else chromatic(.25, 4)])
    crt = ["TV de Tubo", "Tubo Suave", "Tubo Curvo", "Monitor Antigo", "Tela Fosca", "Arcade", "Tubo Extremo", "Broadcast"]
    for index, name in enumerate(crt, start=1):
        add(name, "Retro", [N(f"recly_crt_{index}", .75), N("recly_scanlines", .25, spacing=3, speed=6) if index % 2 else vignette(.3, .35, .5)])


# ── DISTORÇÃO ────────────────────────────────────────────────────────────────
def build_distortion():
    waves = ["Ondulação Calma", "Maré Leve", "Ondas Médias", "Vaga Larga", "Correnteza", "Seda", "Maremoto", "Vibração Aquática"]
    for index, name in enumerate(waves, start=1):
        add(name, "Distorsao", [N(f"recly_onda_{index}", .6), N(f"recly_onda_{index + 8}", .45), chromatic(.25, 3) if index % 2 else glow(.25, 6, .6)])
    spirals = ["Espiral Suave", "Espiral Reversa", "Giro Amplo", "Giro Reverso", "Vórtice", "Redemoinho Esquerdo", "Redemoinho Direito",
               "Vórtice Superior", "Vórtice Inferior", "Buraco Negro"]
    for index, name in enumerate(spirals, start=1):
        nodes = [N(f"recly_redemoinho_{index}", .65)]
        if index <= 8:
            nodes.append(N(f"recly_miragem_{index}", .45))
        nodes.append(vignette(.3, .4, .45) if index > 8 else glow(.2, 6, .6))
        add(name, "Distorsao", nodes)
    add("Lente Peixe", "Distorsao", [N("recly_fisheye", .55), N("recly_lens", .35), N("bulge", .4)])
    add("Aperta e Gira", "Distorsao", [N("pinch", .5), N("twirl", .5), N("turbulence_distortion", .35)])
    add("Calor e Água", "Distorsao", [N("heat_haze", .5), N("water_distortion", .45), N("recly_ripple", .35), N("recly_wave", .3)])


# ── GLITCH ───────────────────────────────────────────────────────────────────
def build_glitch():
    base_glitch = ["recly_rgb", "recly_chromatic", "recly_glitch", "vhs_glitch", "datamosh_style", "tv_static", "recly_scanlines",
                   "pixel_sort_style", "block_glitch", "signal_error"]
    rgb_assets = sorted((i for i, e in FX.items() if e["category"] == "RGB"), key=lambda i: (DEFAULTS[i]["amount"], i))
    glitches = sorted(ids("recly_glitch_", count=18), key=lambda i: (DEFAULTS[i]["amount"], DEFAULTS[i]["rate"], i))
    names = ["Falha Mínima", "Chiado", "Interferência", "Quebra Rápida", "Linhas Tortas", "Sinal Instável", "Pulo de Fita", "Erro de Leitura",
             "Corrupção Leve", "Tela Rachada", "Ruído Digital", "Colapso de Pixels", "Falha Longa", "Corte Seco", "Estática Viva",
             "Sobrecarga", "Pane Total", "Apagão Digital"]
    for index, (glitch, name) in enumerate(zip(glitches, names)):
        base = base_glitch[index % len(base_glitch)]
        nodes = [N(glitch, .65), N(base, .3 + .02 * (index % 5)), N(rgb_assets[index], .5)]
        add(name, "Glitch", nodes)
    tail = rgb_assets[18:]
    assert len(tail) == 6
    add("Sinal Perdido", "Glitch", [N("tv_static", .5), N("signal_error", .5)] + [N(i, .45) for i in tail[:3]])
    add("Corrupção Digital", "Glitch", [N("block_glitch", .5), N("datamosh_style", .45)] + [N(i, .45) for i in tail[3:]])


# ── LUZ ──────────────────────────────────────────────────────────────────────
def build_light():
    pairs = [("dourado", "dourada", "Dourados"), ("rosa", "rosa", "Rosa"), ("ciano", "ciano", "Ciano"), ("laranja", "laranja", "Laranja"),
             ("vermelho", "vermelha", "Vermelhos"), ("branco", "branca", "Brancos"), ("violeta", "roxa", "Violeta"), ("verde", "verde_escuro", "Verdes")]
    for index, (ray, vig, label) in enumerate(pairs):
        add(f"Raios {label}", "Luz", [N(f"recly_raios_{ray}", .6), N(f"recly_vinheta_{vig}_suave", .5), N(f"recly_vinheta_{vig}_forte", .3),
                                     glow(.25, 8, .55)])
    for index, (vig, label) in enumerate([("preta", "Preta"), ("azul_noite", "Azul Noite"), ("vinho", "Vinho"), ("sepia", "Sépia")]):
        add(f"Vinheta {label}", "Luz", [N(f"recly_vinheta_{vig}_suave", .55), N(f"recly_vinheta_{vig}_forte", .35)] + support(index + 4, 1))
    add("Brilho Suave", "Luz", [glow(.5, 12, .45), bloom(.4, 14, .55, .8)])
    add("Neon Contorno", "Luz", [N("recly_neon_edge", .6, radius=2, gain=3), N("edge_glow", .55)])
    add("Vazamento de Luz", "Luz", [N("light_leak", .6), N("lens_flare", .45)])
    add("Flash Colorido", "Luz", [N("color_flash", .55), N("recly_flash", .35, amount=.6, frequency=2)])
    add("Estrobo", "Luz", [N("strobe", .5), flicker(.3, .1, 3)])


# ── MOVIMENTO ────────────────────────────────────────────────────────────────
def build_motion():
    add("Zoom Suave", "Movimento", [N("zoom_in", .6), N("smooth_zoom", .45)])
    add("Zoom Dolly", "Movimento", [N("dolly_zoom", .6), N("zoom_out", .4)])
    add("Zoom 3D", "Movimento", [N("zoom_3d", .6), vignette(.3, .35, .5)])
    add("Giro e Zoom", "Movimento", [N("zoom_rotate", .55), N("elastic_zoom", .4)])
    add("Pulso de Batida", "Movimento", [N("recly_zoom_pulse", .6), N("recly_punch_zoom", .5)])
    add("Zoom Veloz", "Movimento", [N("zoom_blur_motion", .55), N("zoom_in", .4), glow(.2, 6, .6)])


# ── BLUR ─────────────────────────────────────────────────────────────────────
def build_blur():
    angles = [(0, "Horizontal"), (30, "30°"), (60, "60°"), (90, "Vertical"), (120, "120°"), (150, "150°")]
    for angle, label in angles:
        add(f"Rastro {label}", "Blur", [N(f"recly_blur_dir_{angle}_leve", .35), N(f"recly_blur_dir_{angle}_medio", .5), N(f"recly_blur_dir_{angle}_forte", .65)])
    for key, label in (("centro", "Centro"), ("esquerda", "Esquerda"), ("direita", "Direita"), ("cima", "Cima"), ("baixo", "Baixo")):
        add(f"Zoom Blur {label}", "Blur", [N(f"recly_blur_zoom_{key}_leve", .35), N(f"recly_blur_zoom_{key}_medio", .5), N(f"recly_blur_zoom_{key}_forte", .65)])
    for index, label in enumerate(["Bokeh Sutil", "Bokeh Médio", "Bokeh Grande", "Bokeh Cremoso"]):
        add(label, "Blur", [N(f"recly_bokeh_{2 * index + 1}", .55), N(f"recly_bokeh_{2 * index + 2}", .45)])
    add("Desfoque Direcional", "Blur", [N("recly_gaussian", .35, radius=4), N("recly_directional_blur", .5)])
    add("Movimento Automático", "Blur", [N("motion_blur", .55), N("recly_motion_blur_vector", .5)])
    add("Explosão Radial", "Blur", [N("recly_zoom_blur", .5), N("radial_blur", .45)])
    add("Fundo Desfocado", "Blur", [N("background_blur", .6), N("lens_blur", .35)])
    add("Miniatura em Foco", "Blur", [N("tilt_shift", .6), N("focus_pull", .35), N("speed_blur", .3)])


# ── CRIATIVO ─────────────────────────────────────────────────────────────────
def build_creative():
    def group(prefix, groups, names, category="Criativo", intensities=(.65, .5, .4, .35)):
        for chunk, name in zip(groups, names):
            add(name, category, [N(f"{prefix}{item}", intensities[k]) for k, item in enumerate(chunk)])
    group("recly_caleidoscopio_", [(1, 2, 3), (4, 5, 6), (7, 8), (9, 10)], ["Triângulo Mágico", "Cristal", "Mandala", "Vitral"])
    group("recly_espelho_", [("0_50", "0_35", "1_50"), ("1_35", "2_50", "2_35"), ("3_50", "3_35")], ["Espelho A", "Espelho B", "Espelho C"])
    group("recly_pixel_", [(10, 16, 24, 32), (48, 64, 80), (96, 140, 200)], ["Pixel Gigante", "Pixel Médio", "Pixel Fino"])
    group("recly_reticula_", [(1, 2, 3), (4, 5, 6), (7, 8)], ["Meio-Tom Largo", "Meio-Tom Médio", "Meio-Tom Suave"])
    group("recly_posterizar_", [(1, 2, 3), (4, 5, 6), (7, 8)], ["Pôster Extremo", "Pôster Forte", "Pôster Suave"])
    for color, label in (("ciano", "Ciano"), ("magenta", "Magenta"), ("branco", "Branco"), ("verde", "Verde"), ("amarelo", "Amarelo"), ("laranja", "Laranja")):
        add(f"Contorno {label}", "Criativo", [N(f"recly_contorno_{color}", .65), N(f"recly_desenho_{color}", .4)])
    add("Caleidoscópio Prisma", "Criativo", [N("recly_mirror", .5), N("kaleidoscope", .5), N("prism", .4)])
    add("Vidro e Duplo", "Criativo", [N("glass_distortion", .5), N("split_screen", .45), N("double_exposure", .45)])
    add("Pixel Pôster", "Criativo", [N("recly_posterize", .55), N("recly_pixel", .45), N("recly_mosaic", .4)])
    add("Relevo e Bordas", "Criativo", [N("edge_detection", .5), N("emboss_relief", .45), sharpen(.4, .4)])


# ── PARTÍCULAS ───────────────────────────────────────────────────────────────
def build_particles():
    for index, (color, label) in enumerate([("dourado", "Dourado"), ("branco", "Branco"), ("rosa", "Rosa"), ("ciano", "Ciano"),
                                            ("violeta", "Violeta"), ("verde", "Verde"), ("laranja", "Laranja")]):
        add(f"Brilho {label}", "Particulas", [N(f"recly_brilho_{color}_fino", .6), N(f"recly_brilho_{color}_grande", .45)] + support(index, 1))
    pairs = [("clima_neve_leve", "clima_neve_densa", "Neve Intensa"), ("clima_tempestade_de_neve", "clima_granizo", "Gelo e Vento"),
             ("clima_chuva_fina", "clima_chuva_forte", "Chuva Cinematográfica"), ("clima_chuva_de_lado", "clima_chuva_neon", "Chuva Neon"),
             ("clima_chuva_de_fogo", "clima_meteoros", "Chuva de Fogo"), ("clima_poeira_dourada", "clima_cinzas", "Cinzas Douradas"),
             ("clima_petalas", "clima_confete_rosa", "Pétalas em Festa"), ("clima_bolhas", "clima_vaga_lumes", "Vaga-lumes e Bolhas"),
             ("clima_confete_verde", "clima_matrix", "Confete Digital")]
    for a, b, label in pairs:
        add(label, "Particulas", [N(f"recly_{a}", .6), N(f"recly_{b}", .5)])
    add("Fogo e Fumaça", "Particulas", [N("fire", .6), N("smoke", .5)])
    add("Tempestade Elétrica", "Particulas", [N("sparks", .55), N("lightning", .5)])
    add("Clima Misto", "Particulas", [N("rain", .55), N("snow", .45)])
    add("Poeira Mágica", "Particulas", [N("dust_particles", .5), N("sparkles", .55)])
    add("Aura de Energia", "Particulas", [N("energy_aura", .6), N("floating_bubbles", .45)])


# ── RASTRO ───────────────────────────────────────────────────────────────────
def build_trail():
    pairs = [("motion_trail", "ghost_trail", "Rastro Fantasma"), ("frame_echo", "rgb_trail", "Eco Cromático"),
             ("light_trail", "long_exposure_trail", "Longa Exposição"), ("clone_trail", "shadow_trail", "Clones de Sombra"),
             ("smear_trail", "afterimage", "Imagem Residual")]
    for index, (a, b, label) in enumerate(pairs):
        add(label, "Rastro", [N(a, .55), N(b, .4), glow(.2, 6, .6) if index % 2 else vignette(.25, .3, .5)])


BUILDERS_FIXED = [build_cinema, build_film, build_gaming, build_camera, build_retro, build_distortion, build_glitch, build_light,
                  build_motion, build_blur, build_creative, build_particles, build_trail]


def compact(preset):
    return json.dumps(preset, ensure_ascii=False, separators=(",", ":"))


def to_json(entry_id, name, category, nodes):
    out = []
    for asset, intensity, values in nodes:
        node = {"assetId": asset, "intensity": round(intensity, 2)}
        if values:
            node["values"] = {k: (round(v, 4) if isinstance(v, float) else v) for k, v in values.items()}
        out.append(node)
    return {"schemaVersion": 1, "id": entry_id, "name": name, "category": category, "nodes": out}


def validate(presets):
    seen = set()
    names = set()
    used = set()
    for p in presets:
        assert re.fullmatch(r"[a-z][a-z0-9_]{0,63}", p["id"]), f"id invalido: {p['id']}"
        assert p["id"] not in seen, f"id duplicado: {p['id']}"
        assert p["name"].casefold() not in names, f"nome repetido na galeria: {p['name']}"
        seen.add(p["id"])
        names.add(p["name"].casefold())
        assert p["name"].strip() and len(p["name"]) <= 80 and p["category"].strip(), p["id"]
        assert 1 <= len(p["nodes"]) <= MAX_NODES or p["id"] in ORIGINAL_IDS, f"{p['id']}: {len(p['nodes'])} nos"
        medium = temporal = 0
        for node in p["nodes"]:
            asset = FX.get(node["assetId"])
            assert asset is not None, f"{p['id']}: efeito inexistente {node['assetId']}"
            assert asset["version"] == node.get("version", 1)
            assert 0 <= node["intensity"] <= 1, f"{p['id']}: intensidade {node['intensity']}"
            params = {q["id"]: q for q in asset["parameters"]}
            assert len(node.get("values", {})) <= 16
            for key, value in node.get("values", {}).items():
                assert key in params, f"{p['id']}/{node['assetId']}: parametro desconhecido {key}"
                assert params[key]["min"] <= value <= params[key]["max"], f"{p['id']}/{node['assetId']}.{key}={value} fora de [{params[key]['min']},{params[key]['max']}]"
            medium += asset["cost"] == "MEDIUM"
            temporal += bool(asset.get("temporal")) or asset["engine"] == "TEMPORAL_TRAIL"
            used.add(node["assetId"])
        if p["id"] not in ORIGINAL_IDS:
            assert medium <= MAX_MEDIUM, f"{p['id']}: {medium} efeitos MEDIUM"
            assert temporal <= MAX_TEMPORAL, f"{p['id']}: {temporal} efeitos temporais"
    missing = sorted(set(FX) - used)
    assert not missing, f"{len(missing)} efeitos fora dos presets: {missing[:20]}"
    return used


def main():
    existing = json.load(open(PRESETS_FILE, encoding="utf-8"))
    originals = {p["id"]: p for p in existing if p["id"] in ORIGINAL_IDS}
    assert list(originals) == ORIGINAL_IDS or set(originals) == set(ORIGINAL_IDS), "presets originais ausentes"
    originals = [originals[i] for i in ORIGINAL_IDS]

    for build in BUILDERS_FIXED:
        build()
    fixed = len(GENERATED)
    color_count = TARGET_TOTAL - len(ORIGINAL_IDS) - fixed
    assert 42 <= color_count <= 56 + 4, f"presets de cor fora da faixa: {color_count}"
    build_color(color_count)

    taken = set(ORIGINAL_IDS)
    out = list(originals)
    for name, category, nodes in GENERATED:
        base = slug(name) or "preset"
        entry_id, n = base[:60], 2
        while entry_id in taken:
            entry_id = f"{base[:56]}_{n}"
            n += 1
        taken.add(entry_id)
        out.append(to_json(entry_id, name, category, nodes))
    used = validate(out)
    assert len(out) == TARGET_TOTAL, f"{len(out)} presets"
    text = "[\n" + ",\n".join("  " + compact(p) for p in out) + "\n]\n"
    assert len(text) < 524_288
    with open(PRESETS_FILE, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    categories = {}
    for p in out:
        categories[p["category"]] = categories.get(p["category"], 0) + 1
    print(f"{len(out)} presets, {len(used)}/{len(FX)} efeitos cobertos, {len(text) / 1024:.0f} KB")
    print(", ".join(f"{c}: {n}" for c, n in categories.items()))


if __name__ == "__main__":
    sys.exit(main())
