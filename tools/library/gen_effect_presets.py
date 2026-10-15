"""Gera os presets de efeito (effect_presets.json) combinando os efeitos de effects.json.

Mantém os presets feitos à mão (os que não começam com "p_") e acrescenta combinações temáticas
geradas de forma determinística (GENERATED combinações, além dos feitos à mão). Cada combinação usa só efeitos que
existem no catálogo, com valores dentro do min/max de cada parâmetro.

    python3 tools/library/gen_effect_presets.py
"""
import itertools
import json
import os
import random
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "editor-engine/src/main/assets/editor")
# Quantos presets gerados (os feitos à mão somam a isso; antes eram 1000 no total com 20 à mão).
GENERATED = 980
GENERATED_PREFIX = "p_"

# Parâmetros de "força": recebem uma pequena variação para cada preset ter cara própria.
# Cor, modo, ângulo, centro, contagem de linhas etc. ficam no padrão do efeito (é a identidade dele).
JITTER = {"amount", "radius", "distance", "strength", "noise", "jitter", "grain", "size", "threshold", "decay"}
CATEGORY_LABELS = {"Distorsao": "Distorção", "Particulas": "Partículas"}
NAME_PREFIXES = ("Look ", "Tom ", "Color ", "Style ")


def load():
    with open(os.path.join(ASSETS, "effects.json"), encoding="utf-8") as f:
        effects = {e["id"]: e for e in json.load(f)}
    with open(os.path.join(ASSETS, "effect_presets.json"), encoding="utf-8") as f:
        handmade = [p for p in json.load(f) if not p["id"].startswith(GENERATED_PREFIX)]
    for p in handmade:  # nomes de categoria viram aba na galeria: com acento
        p["category"] = CATEGORY_LABELS.get(p["category"], p["category"])
    return effects, handmade


def pool(effects, *, ids=(), regex=None, category=None, engine=None):
    out = [i for i in ids if i in effects]
    for eid, e in effects.items():
        if regex and not re.fullmatch(regex, eid):
            continue
        if category and e["category"] != category:
            continue
        if engine and e["engine"] != engine:
            continue
        if regex or category or engine:
            out.append(eid)
    seen = set()
    result = [i for i in out if not (i in seen or seen.add(i))]
    if not result:
        raise SystemExit(f"pool vazio: ids={ids} regex={regex} category={category} engine={engine}")
    return result


def short(effect):
    name = effect["name"]
    for prefix in NAME_PREFIXES:
        if name.startswith(prefix):
            name = name[len(prefix):]
    return name[:1].upper() + name[1:]


def node(rng, effect, intensity_range):
    values = {}
    for p in effect["parameters"]:
        if p["id"] not in JITTER or p["max"] <= p["min"]:
            continue
        value = p["default"] * rng.uniform(.85, 1.15)
        values[p["id"]] = round(min(p["max"], max(p["min"], value)), 3)
    out = {"assetId": effect["id"], "intensity": round(rng.uniform(*intensity_range), 2)}
    if effect.get("version", 1) != 1:
        out["version"] = effect["version"]
    if values:
        out["values"] = values
    return out


def themes(fx):
    """(categoria, prefixo_id, palavras do nome, quantidade, slots). Slot = (pool, faixa de intensidade, opcional)."""
    grades = pool(fx, category="Cinematic", engine="COLOR_LUT") + pool(fx, regex=r"color_(teal_orange|cinema_cold|cinema_warm|moody|dramatic|kodak_like|fuji_like|night_city|cold_steel|noir)")
    bars = pool(fx, regex=r"recly_barras_.*")
    dark_vig = pool(fx, regex=r"recly_vinheta_preta_.*|recly_vinheta_azul_noite_.*") + ["recly_vignette"]
    grain = pool(fx, regex=r"recly_granulado_\d+") + ["recly_film_grain"]
    leaks = pool(fx, regex=r"recly_vazamento_.*") + ["light_leak"]
    soft_light = pool(fx, ids=["recly_bloom", "recly_halation", "recly_glow"])
    film_main = pool(fx, ids=["old_film", "dust_scratches", "film_burn", "film_8mm", "super_16_look", "sepia_film", "recly_halation", "recly_flicker"])
    film_tone = pool(fx, regex=r"color_(vintage_70s|faded_film|analog_summer|sepia_soft|cross_film|mono_warm|lofi)|recly_grade_(filme_antigo|kodak|fuji|polaroid|matte_suave)|recly_duo_(sepia|papel|cafe)")
    warm_vig = pool(fx, regex=r"recly_vinheta_(sepia|dourada|preta)_.*")
    crt = pool(fx, category="Retro") + ["recly_crt", "retro_camcorder"]
    retro_tone = pool(fx, regex=r"recly_grade_retro_80|recly_duo_(vaporwave|algodao_doce|por_de_sol_rosa|sunset)|color_(miami|vintage_70s|lofi)|style_(gradient_map_sunset|duotone_blue_pink)")
    rgb_soft = pool(fx, regex=r"recly_rgb_\d+_sutil|recly_rgb_\d+_medio") + ["recly_chromatic"]
    vhs = pool(fx, category="VHS") + ["recly_vhs", "vhs_glitch", "retro_camcorder"]
    rgb_shake = pool(fx, regex=r"recly_rgb_tremendo_\d+|recly_rgb_onda_\d+") + ["recly_chromatic"]
    glitch_main = pool(fx, regex=r"recly_glitch_\d+") + pool(fx, category="Glitch", engine="RGB_CHANNEL")
    glitch_accent = pool(fx, ids=["recly_scanlines", "tv_static", "recly_flash", "strobe", "recly_noise"]) + pool(fx, regex=r"recly_linhas_\d")
    neon_tone = pool(fx, regex=r"recly_duo_(neon_night|cyberpunk|vaporwave|galaxia|toxico|matrix|noir_azul)|recly_grade_(cyber|noturno_neon|gamer)|color_(cyberpunk|gaming_neon|night_city|miami)")
    neon_light = pool(fx, ids=["recly_glow", "recly_neon_edge", "edge_glow", "style_neon_edges"]) + pool(fx, regex=r"recly_raios_(ciano|violeta|rosa|verde)|recly_contorno_(ciano|magenta|verde)")
    neon_fx = pool(fx, ids=["recly_clima_chuva_neon", "recly_clima_matrix"]) + pool(fx, regex=r"recly_brilho_(ciano|violeta|rosa|verde)_.*|recly_rgb_\d+_sutil")
    punch = pool(fx, ids=["recly_punch_zoom", "elastic_zoom", "recly_zoom_pulse", "zoom_rotate"]) + pool(fx, regex=r"recly_zoom_batida_\d_(medio|rapido)")
    hit_shake = pool(fx, ids=["impact_shake", "heavy_shake", "beat_shake", "bounce_shake", "rotation_shake", "recly_shake", "vibration"])
    flash = pool(fx, regex=r"recly_flash_\d+") + pool(fx, ids=["recly_flash", "color_flash", "strobe"])
    hit_extra = pool(fx, ids=["recly_rgb", "recly_chromatic", "recly_zoom_blur", "radial_blur", "speed_blur"]) + pool(fx, regex=r"recly_blur_zoom_centro_.*")
    dream_blur = pool(fx, regex=r"recly_bokeh_\d") + pool(fx, ids=["recly_gaussian", "lens_blur", "background_blur", "tilt_shift"])
    dream_light = pool(fx, ids=["recly_glow", "recly_bloom", "recly_halation"]) + pool(fx, regex=r"recly_raios_(branco|dourado|rosa|violeta)")
    dream_tone = pool(fx, regex=r"recly_grade_(sonho|pastel|rosado|brilhante|lavado|pele_radiante)|recly_duo_(lavanda|algodao_doce|pessego|aurora)|color_(pastel|rose|lavender|clean_bright)|recly_tom_\w+_suave")
    tints = pool(fx, category="Color", engine="COLOR_LUT")
    color_vig = pool(fx, category="Light", engine="COMPOSITOR")
    color_extra = pool(fx, ids=["recly_film_grain", "recly_glow", "recly_sharpen", "recly_halation"]) + pool(fx, regex=r"recly_granulado_[1-6]")
    rays = pool(fx, regex=r"recly_raios_.*") + pool(fx, ids=["recly_bloom", "lens_flare", "edge_glow"])
    sparkle = pool(fx, regex=r"recly_brilho_.*") + pool(fx, ids=["sparkles", "recly_clima_poeira_dourada", "recly_clima_vaga_lumes"])
    warp = pool(fx, category="Distortion")
    warp_extra = pool(fx, regex=r"recly_rgb_\d+_sutil|recly_blur_dir_\d+_leve") + pool(fx, ids=["recly_chromatic", "recly_glow"])
    move = pool(fx, ids=["zoom_in", "zoom_out", "zoom_3d", "smooth_zoom", "dolly_zoom", "zoom_rotate", "zoom_blur_motion"]) + pool(fx, regex=r"recly_camera_\d+") + ["handheld_camera"]
    move_blur = pool(fx, regex=r"recly_blur_dir_\d+_(medio|forte)|recly_blur_zoom_\w+_medio") + pool(fx, ids=["motion_blur", "speed_blur", "radial_blur"])
    trails = pool(fx, category="Trail")
    particles = pool(fx, category="Particles")
    weather_tone = pool(fx, regex=r"recly_grade_(inverno|neve|verao|deserto|praia|madrugada|terra|hora_dourada|sombrio|noturno_neon)|color_(arctic|desert|forest|golden_hour|blue_hour|sunset_pop|moody)")
    creative = pool(fx, category="Creative")
    creative_tone = pool(fx, regex=r"recly_duo_.*|style_(duotone_\w+|pop_art|gradient_map_\w+|posterize|halftone|infrared|thermal|cyanotype)")
    mono = pool(fx, regex=r"color_(noir|mono_soft|mono_warm)|recly_grade_peb_\w+|style_high_contrast_bw|recly_grade_bleach|style_bleach_bypass")

    S = lambda p, lo, hi, optional=False: (p, (lo, hi), optional)
    return [
        ("Cinema", "cine", ["Cine", "Épico", "Trailer", "Longa", "Drama", "Cena"], 55,
         [S(grades, .7, .9), S(bars, .8, 1.0), S(dark_vig + grain, .3, .55, True)]),
        ("Cinema", "cinelux", ["Brilho", "Aura", "Tela", "Estreia"], 45,
         [S(grades, .65, .85), S(leaks, .3, .55), S(soft_light, .3, .5, True)]),
        ("Cinema", "noir", ["Noir", "Sombra", "Contraste", "Clássico"], 30,
         [S(mono, .75, .95), S(grain, .35, .6), S(dark_vig + bars, .5, .8, True)]),
        ("Film", "film", ["Película", "Analógico", "Rolo", "Super 8", "Arquivo", "Memória"], 60,
         [S(film_main, .55, .8), S(grain, .3, .55), S(film_tone + warm_vig, .45, .7, True)]),
        ("Film", "leak", ["Luz de filme", "Vazado", "Revelado", "Câmara"], 35,
         [S(film_tone, .6, .8), S(leaks, .35, .6), S(grain, .25, .45)]),
        ("Retro", "retro", ["Anos 80", "Fliperama", "Retrô", "Synth", "Disco"], 50,
         [S(crt, .5, .75), S(retro_tone, .55, .8), S(rgb_soft, .3, .5, True)]),
        ("Retro", "vhs", ["Fita", "VHS", "Gravado", "Rebobina", "Caseiro"], 40,
         [S(vhs, .55, .8), S(rgb_shake, .3, .5), S(film_tone + retro_tone, .4, .6, True)]),
        ("Glitch", "glitch", ["Bug", "Erro", "Sinal", "Pane", "Corrompido", "Falha"], 70,
         [S(glitch_main, .55, .85), S(rgb_soft + rgb_shake, .35, .6), S(glitch_accent, .25, .45, True)]),
        ("Neon", "neon", ["Neon", "Cyber", "Noite", "Elétrico", "Laser"], 65,
         [S(neon_tone, .65, .85), S(neon_light, .4, .65), S(neon_fx, .3, .55, True)]),
        ("Gaming", "hit", ["Impacto", "Combo", "Golpe", "Kill", "Clutch", "Ultimate", "Boom"], 90,
         [S(punch, .65, .85), S(hit_shake, .4, .7), S(flash, .3, .55), S(hit_extra, .3, .5, True)]),
        ("Sonho", "dream", ["Sonho", "Suave", "Nuvem", "Etéreo", "Delicado"], 60,
         [S(dream_blur, .35, .6), S(dream_light, .4, .65), S(dream_tone, .5, .75)]),
        ("Cor", "cor", ["Tom", "Paleta", "Matiz", "Cor", "Tinta"], 75,
         [S(tints, .55, .85), S(color_vig, .4, .7), S(color_extra, .2, .4, True)]),
        ("Luz", "luz", ["Luz", "Raio", "Brilho", "Reflexo", "Sol"], 50,
         [S(leaks + rays, .4, .65), S(rays, .35, .6), S(sparkle, .3, .55, True)]),
        ("Distorção", "warp", ["Onda", "Miragem", "Vórtice", "Líquido", "Torção"], 60,
         [S(warp, .5, .8), S(warp_extra, .3, .5), S(tints, .3, .5, True)]),
        ("Movimento", "move", ["Movimento", "Câmera", "Travelling", "Velocidade", "Giro"], 65,
         [S(move, .55, .8), S(move_blur, .35, .6), S(trails, .3, .5, True)]),
        ("Partículas", "clima", ["Clima", "Atmosfera", "Céu", "Ar", "Estação"], 65,
         [S(particles, .55, .85), S(weather_tone, .5, .75), S(color_vig, .3, .5, True)]),
        ("Criativo", "arte", ["Arte", "Pop", "Gráfico", "Colagem", "Ilustra"], 65,
         [S(creative, .6, .85), S(creative_tone, .45, .7), S(color_extra, .2, .4, True)]),
    ]


def main():
    fx, handmade = load()
    rng = random.Random(20261015)
    used_names = {p["name"] for p in handmade}
    used_sets = {tuple(n["assetId"] for n in p["nodes"]) for p in handmade}
    generated = []
    need = GENERATED
    plan = themes(fx)
    planned = sum(t[3] for t in plan)
    if planned != need:
        raise SystemExit(f"temas planejam {planned} presets, faltam {need - planned}")
    for category, prefix, words, count, slots in plan:
        combos = []
        for picks in itertools.product(*[s[0] for s in slots]):
            if len(set(picks)) == len(picks):
                combos.append(picks)
        rng.shuffle(combos)
        made = 0
        for picks in combos:
            if made >= count or len(generated) >= need:
                break
            # Slots opcionais entram em ~60% dos presets, para variar o número de passes.
            chosen = [eid for eid, slot in zip(picks, slots) if not slot[2] or rng.random() < .6]
            key = tuple(chosen)
            if key in used_sets:
                continue
            heavy = sum(fx[e].get("cost") == "MEDIUM" for e in chosen)
            temporal = sum(bool(fx[e].get("temporal")) for e in chosen)
            if heavy > 2 or temporal > 1:
                continue
            used_sets.add(key)
            made += 1
            base = f"{words[made % len(words)]} {short(fx[chosen[0]])}"
            name, n = base, 2
            while name in used_names:
                name, n = f"{base} {n}", n + 1
            used_names.add(name)
            nodes = [node(rng, fx[eid], slots[[s for s in picks].index(eid)][1]) for eid in chosen]
            generated.append({
                "schemaVersion": 1,
                "id": f"{GENERATED_PREFIX}{prefix}_{made:03d}",
                "name": name,
                "category": category,
                "nodes": nodes,
            })
        if made < count:
            raise SystemExit(f"tema {prefix}: só {made} de {count} combinações válidas")
    presets = handmade + generated
    assert len(presets) == len(handmade) + GENERATED, len(presets)
    validate(fx, presets)
    path = os.path.join(ASSETS, "effect_presets.json")
    with open(path, "w", encoding="utf-8") as f:
        # Um preset por linha: diff legível e arquivo pequeno para o app carregar.
        f.write("[\n" + ",\n".join(json.dumps(p, ensure_ascii=False, separators=(",", ":")) for p in presets) + "\n]\n")
    by_cat = {}
    for p in presets:
        by_cat[p["category"]] = by_cat.get(p["category"], 0) + 1
    print(f"{len(presets)} presets ({len(handmade)} feitos à mão), {os.path.getsize(path)} bytes")
    print(by_cat)


def validate(fx, presets):
    ids = [p["id"] for p in presets]
    assert len(ids) == len(set(ids)), "ids repetidos"
    assert len({p["name"] for p in presets}) == len(presets), "nomes repetidos"
    for p in presets:
        assert re.fullmatch(r"[a-z][a-z0-9_]{0,63}", p["id"]), p["id"]
        assert 1 <= len(p["nodes"]) <= 8, p["id"]
        for n in p["nodes"]:
            e = fx[n["assetId"]]
            assert 0 <= n["intensity"] <= 1, p["id"]
            params = {q["id"]: q for q in e["parameters"]}
            for k, v in n.get("values", {}).items():
                assert k in params and params[k]["min"] <= v <= params[k]["max"], (p["id"], k, v)


if __name__ == "__main__":
    main()
