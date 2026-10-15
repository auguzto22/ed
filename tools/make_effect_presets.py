"""Builds assets/editor/effect_presets.json: the 20 original presets + new presets that combine
catalog effects (headshot-style impacts, cinema looks, glitch, neon, social, horror, nature...).

Every effect and parameter is checked against effects.json (ids resolved with/without the
"recly_" prefix, values clamped to the declared range), so a preset can never point at
something that does not exist.  Run from the repo root:  python3 tools/make_effect_presets.py
"""
import json, re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / "editor-engine/src/main/assets/editor"
CATALOG = {e["id"]: e for e in json.loads((ROOT / "effects.json").read_text())}
KEEP = [p for p in json.loads((ROOT / "effect_presets.json").read_text()) if not p.get("generated")]

def n(effect, intensity=.7, **values):
    return (effect, intensity, values)

NEW = []  # (id, name, category, durationMs|None, [nodes])
def P(pid, name, category, nodes, ms=None):
    NEW.append((pid, name, category, ms, nodes))

# ───────────────────────── Gaming / impactos (curtos, aplicados no ponto do cursor) ─────────────────────────
G = "Gaming"
P("headshot_critico", "Headshot Crítico", G, [n("punch_zoom", .9, amount=.3, attack=.03, release=.18), n("impact_shake", .75, distance=20, rotation=3), n("flash", .55, amount=.8), n("vinheta_vermelha_forte", .6)], 300)
P("headshot_slowmo", "Headshot Slow", G, [n("zoom_in", .8, amount=.35, speed=1.4), n("radial_blur", .55, amount=1.1), n("chromatic", .6, amount=9), n("vinheta_vermelha_suave", .6)], 500)
P("hitmarker", "Hitmarker", G, [n("flash", .6, amount=.9), n("micro_shake", .7), n("chromatic", .5, amount=6)], 160)
P("critical_hit", "Critical Hit", G, [n("punch_zoom", .85, amount=.25), n("strobe", .5, amount=.7, frequency=14), n("rgb", .7, distance=14), n("heavy_shake", .6)], 280)
P("one_tap", "One Tap", G, [n("punch_zoom", .8, amount=.22, attack=.02), n("flash", .5, amount=.7), n("blur_zoom_centro_medio", .6)], 220)
P("ace_clutch", "Ace Clutch", G, [n("elastic_zoom", .85, amount=.8), n("light_trail", .6), n("bloom", .55, gain=1.2), n("vinheta_dourada_suave", .5)], 600)
P("quad_kill", "Quad Kill", G, [n("zoom_pulse", .9, amount=.14, frequency=6), n("beat_shake", .6), n("color_flash", .55, amount=.7), n("rgb", .6, distance=10)], 600)
P("boss_hit", "Boss Hit", G, [n("heavy_shake", .9, distance=34, rotation=6), n("flash", .6, amount=.9), n("vinheta_preta_forte", .7)], 400)
P("combo_break", "Combo Break", G, [n("glitch", .8), n("rgb", .8, distance=18), n("tv_static", .45, amount=.5), n("shake", .6)], 350)
P("final_blow", "Final Blow", G, [n("punch_zoom", .95, amount=.4, attack=.02, release=.3), n("speed_blur", .6), n("flash", .7, amount=1.0), n("impact_shake", .8)], 450)
P("level_up", "Level Up", G, [n("sparkles", .9), n("bloom", .6, gain=1.3), n("zoom_pulse", .7, amount=.1), n("brilho_dourado_grande", .7)], 900)
P("power_up", "Power Up", G, [n("energy_aura", .9), n("glow", .6, radius=14), n("zoom_in", .55, amount=.18), n("rgb_onda_1", .5)], 800)
P("game_over", "Game Over", G, [n("tom_vermelho_forte", .6), n("vinheta_preta_forte", .85), n("crt_1", .6), n("tv_static", .5, amount=.55)], 900)
P("low_hp", "Low HP", G, [n("vinheta_vermelha_forte", .95, amount=1.0), n("zoom_pulse", .6, amount=.05, frequency=1.6), n("gaussian", .25, radius=2)], 1200)
P("rage_mode", "Rage Mode", G, [n("tom_vermelho_forte", .55), n("heavy_shake", .6), n("rgb", .7, distance=12), n("vinheta_vermelha_forte", .8), n("fire", .5)], 900)
P("dash", "Dash", G, [n("speed_blur", .85, amount=1.5), n("zoom_blur", .7), n("rgb_0_forte", .6)], 300)
P("parry", "Parry", G, [n("flash", .7, amount=1.0), n("sparks", .85), n("micro_shake", .7), n("chromatic", .5, amount=5)], 220)
P("perfect_dodge", "Perfect Dodge", G, [n("motion_trail", .7), n("rgb_trail", .6), n("speed_blur", .6), n("grade_cinema_frio", .45)], 450)
P("knockout", "Knockout", G, [n("punch_zoom", 1.0, amount=.45, attack=.015, release=.35), n("heavy_shake", .9, distance=40), n("flash", .8, amount=1.0), n("vinheta_branca_forte", .6)], 500)
P("explosao_tela", "Explosão de Tela", G, [n("flash", .9, amount=1.0), n("fire", .7), n("heavy_shake", .8), n("radial_blur", .7, amount=1.4), n("film_burn", .5)], 600)
P("raio_lightning", "Raio", G, [n("lightning", .9), n("strobe", .55, frequency=10), n("vinheta_azul_noite_forte", .6)], 450)
P("slash", "Slash", G, [n("flash", .7, amount=.8), n("blur_dir_30_forte", .75), n("micro_shake", .6), n("rgb_45_forte", .6)], 200)
P("revive", "Revive", G, [n("glow", .7, radius=16), n("sparkles", .8), n("vinheta_branca_suave", .5), n("zoom_out", .5, amount=.15)], 900)
P("loot_epico", "Loot Épico", G, [n("brilho_dourado_grande", .9), n("lens_flare", .7), n("bloom", .6, gain=1.4), n("tom_dourado_suave", .5)], 1000)

# ───────────────────────── Cinema ─────────────────────────
C = "Cinema"
P("blockbuster", "Blockbuster", C, [n("grade_teal_e_laranja", .85), n("barras_preto_cinema", 1.0), n("film_grain", .3, amount=.05), n("vignette", .35)])
P("noir_dramatico", "Noir Dramático", C, [n("tom_azul_suave", .25), n("grade_documentario", .6), n("vinheta_preta_forte", .8), n("film_grain", .45, amount=.12), n("barras_preto_fina", 1.0)])
P("western_poeirento", "Western", C, [n("tom_ambar_forte", .55), n("grade_filme_antigo", .6), n("dust_particles", .5), n("vinheta_sepia_forte", .7), n("barras_preto_cinema", 1.0)])
P("sci_fi_frio", "Sci-Fi Frio", C, [n("grade_cinema_frio", .85), n("bloom", .4), n("chromatic", .3, amount=3), n("barras_preto_larga", 1.0)])
P("thriller", "Thriller", C, [n("tom_verde_suave", .3), n("grade_documentario", .7), n("vinheta_verde_escuro_forte", .75), n("film_grain", .3)])
P("romance_dourado", "Romance Dourado", C, [n("grade_cinema_quente", .75), n("glow", .4, radius=14), n("vazamento_dourado_a", .55), n("vinheta_dourada_suave", .45)])
P("drama_matte", "Drama Matte", C, [n("grade_matte_suave", .85), n("vignette", .4), n("film_grain", .25, amount=.05), n("barras_preto_fina", 1.0)])
P("kodak_portra", "Kodak Portra", C, [n("grade_kodak", .85), n("halation", .4), n("film_grain", .3), n("vignette", .25)])
P("fuji_verde", "Fuji Verde", C, [n("grade_fuji", .85), n("film_grain", .3), n("vignette", .25)])
P("documentario", "Documentário", C, [n("grade_documentario", .8), n("sharpen", .3), n("vignette", .2)])
P("anamorfico", "Anamórfico", C, [n("barras_preto_cinema", 1.0), n("lens_flare", .4, amount=.7), n("chromatic", .3, amount=3), n("grade_teal_e_laranja", .55)])
P("golden_hour", "Golden Hour", C, [n("tom_dourado_suave", .7), n("vazamento_laranja_a", .55), n("bloom", .5, gain=1.0), n("vinheta_laranja_suave", .45)])
P("blue_hour", "Blue Hour", C, [n("tom_azul_suave", .6), n("grade_cinema_frio", .6), n("glow", .35), n("vinheta_azul_noite_suave", .55)])
P("trailer_epico", "Trailer Épico", C, [n("grade_teal_e_laranja", .8), n("barras_preto_cinema", 1.0), n("zoom_in", .4, amount=.12, speed=.5), n("vinheta_preta_suave", .6), n("film_grain", .25)])
P("pelicula_preta_branca", "Preto e Branco", C, [n("tom_azul_suave", .01), n("grade_documentario", .6), n("film_grain", .45, amount=.12), n("vinheta_preta_suave", .55)])

# ───────────────────────── Film / Retro / VHS ─────────────────────────
F = "Film"
P("super8_familia", "Super 8 Família", F, [n("film_8mm", .8), n("vazamento_laranja_b", .45), n("dust_scratches", .5), n("vinheta_sepia_suave", .5)])
P("filme_queimado", "Filme Queimado", F, [n("film_burn", .8), n("vazamento_vermelho_a", .5), n("granulado_2", .5), n("halation", .4)])
P("polaroid", "Polaroid", F, [n("grade_matte_suave", .7), n("tom_pessego_suave", .5), n("vignette", .35), n("granulado_1", .4)])
P("memoria_antiga", "Memória Antiga", F, [n("sepia_film", .85), n("old_film", .6), n("vinheta_sepia_forte", .7), n("gaussian", .15, radius=2)])
P("camcorder_90s", "Camcorder 90s", F, [n("retro_camcorder", .85), n("vhs_2", .55), n("rgb_0_sutil", .5), n("tom_amarelo_suave", .25)])
P("fita_k7", "Fita Cassete", F, [n("vhs_4", .75), n("linhas_2", .5), n("chromatic", .4, amount=4), n("tom_ambar_suave", .3)])
P("vhs_rewind", "VHS Rewind", F, [n("vhs_6", .9), n("tv_static", .45), n("rgb_0_forte", .5), n("linhas_5", .5)], None)
P("tv_antiga", "TV Antiga", F, [n("crt_3", .85), n("scanlines", .4), n("vhs_1", .5), n("vinheta_preta_suave", .5)])
P("crt_arcade", "CRT Arcade", F, [n("crt_6", .9), n("bloom", .35), n("rgb_0_sutil", .4), n("posterize", .25, levels=14)])
P("filme_16mm", "Filme 16mm", F, [n("super_16_look", .8), n("granulado_3", .5), n("grade_filme_antigo", .55), n("halation", .35)])
P("poeira_e_riscos", "Poeira e Riscos", F, [n("dust_scratches", .8), n("old_film", .5), n("granulado_1", .45)])
P("vazamento_rosa_sonho", "Vazamento Rosa", F, [n("vazamento_rosa_a", .75), n("vazamento_roxo_b", .45), n("glow", .35), n("granulado_2", .3)])
P("vazamento_azul_noite", "Vazamento Azul", F, [n("vazamento_azul_a", .75), n("vazamento_ciano_b", .4), n("halation", .3), n("granulado_2", .3)])
P("anos_70", "Anos 70", F, [n("tom_laranja_suave", .5), n("grade_filme_antigo", .65), n("vazamento_amarelo_a", .45), n("granulado_3", .5), n("vinheta_sepia_suave", .5)])
P("anos_80_neon", "Anos 80", F, [n("tom_magenta_suave", .4), n("vhs_3", .55), n("neon_edge", .35), n("rgb_45_medio", .5), n("scanlines", .3)])
P("fotografia_instantanea", "Instantânea", F, [n("flash", .35, amount=.5, frequency=.4), n("grade_kodak", .65), n("vignette", .4), n("granulado_1", .35)])

# ───────────────────────── Glitch / Cyber ─────────────────────────
Y = "Glitch"
P("cyberpunk", "Cyberpunk", Y, [n("tom_magenta_suave", .45), n("tom_ciano_suave", .3), n("neon_edge", .4), n("rgb_45_medio", .6), n("bloom", .5)])
P("glitch_digital", "Glitch Digital", Y, [n("glitch_3", .8), n("rgb", .65, distance=14), n("block_glitch", .5)])
P("sinal_perdido", "Sinal Perdido", Y, [n("signal_error", .85), n("tv_static", .55), n("vhs_glitch", .6), n("chromatic", .5, amount=8)])
P("datamosh", "Datamosh", Y, [n("datamosh_style", .85), n("pixel_sort_style", .55), n("glitch_8", .5)])
P("hacker_matrix", "Hacker", Y, [n("tom_verde_forte", .5), n("clima_matrix", .75), n("scanlines", .45), n("glitch_1", .4), n("vinheta_verde_escuro_forte", .6)])
P("rgb_tremor", "RGB Tremor", Y, [n("rgb_tremendo_0", .85), n("rgb_tremendo_90", .55), n("micro_shake", .5)])
P("onda_rgb", "Onda RGB", Y, [n("rgb_onda_3", .8), n("wave", .4), n("bloom", .35)])
P("pixel_quebrado", "Pixel Quebrado", Y, [n("pixel_64", .7), n("block_glitch", .65), n("posterize", .3, levels=10)])
P("erro_404", "Erro 404", Y, [n("glitch_12", .85), n("tv_static", .6), n("tom_vermelho_suave", .35), n("scanlines", .4)])
P("neon_glitch", "Neon Glitch", Y, [n("glitch_5", .7), n("neon_edge", .5), n("rgb_135_medio", .6), n("glow", .45)])
P("holograma", "Holograma", Y, [n("tom_ciano_forte", .5), n("scanlines", .5), n("rgb_0_medio", .5), n("glow", .5), n("flicker", .35)])
P("transmissao_alien", "Transmissão Alien", Y, [n("tom_lima_suave", .45), n("turbulence_distortion", .5), n("signal_error", .6), n("chromatic", .5, amount=6)])

# ───────────────────────── Neon / Luz / Sonho ─────────────────────────
N = "Neon"
P("noite_neon", "Noite Neon", N, [n("tom_roxo_suave", .45), n("neon_edge", .4), n("bloom", .65, gain=1.2), n("vinheta_roxa_forte", .6)])
P("synthwave", "Synthwave", N, [n("tom_magenta_forte", .4), n("tom_ciano_suave", .3), n("bloom", .6), n("scanlines", .3), n("vinheta_rosa_suave", .5)])
P("sonho_rosa", "Sonho Rosa", N, [n("tom_rosa_suave", .55), n("glow", .6, radius=16), n("gaussian", .2, radius=3), n("brilho_rosa_fino", .6)])
P("aura_dourada", "Aura Dourada", N, [n("tom_dourado_suave", .55), n("bloom", .6, gain=1.3), n("brilho_dourado_fino", .7), n("halation", .4)])
P("brilho_magico", "Brilho Mágico", N, [n("sparkles", .85), n("brilho_branco_grande", .6), n("glow", .5), n("tom_violeta_suave", .35)])
P("festa_laser", "Festa Laser", N, [n("strobe", .5, frequency=8), n("color_flash", .6), n("lens_flare", .6), n("beat_shake", .45)])
P("club_noite", "Club", N, [n("zoom_pulse", .8, amount=.1, frequency=3.5), n("color_flash", .5), n("vinheta_roxa_forte", .6), n("bloom", .5)])
P("lente_suja", "Lente Suja", N, [n("lens_flare", .6), n("bloom", .55), n("halation", .5), n("chromatic", .3, amount=3)])
P("nevoa_suave", "Névoa Suave", N, [n("gaussian", .3, radius=4), n("glow", .6, radius=18, threshold=.3), n("vinheta_branca_suave", .35)])
P("luz_de_vela", "Luz de Vela", N, [n("tom_ambar_forte", .55), n("flicker", .4), n("vinheta_laranja_forte", .75), n("glow", .4)])
P("ciano_eletrico", "Ciano Elétrico", N, [n("tom_ciano_forte", .5), n("energy_aura", .55), n("bloom", .5), n("vinheta_ciano_suave", .5)])
P("fogo_cena", "Fogo na Cena", N, [n("tom_laranja_forte", .5), n("fire", .6), n("heat_haze", .4), n("vinheta_vermelha_suave", .5)])

# ───────────────────────── Social / Viral ─────────────────────────
S = "Social"
P("beat_drop", "Beat Drop", S, [n("zoom_batida_2_rapido", .9), n("beat_shake", .6), n("color_flash", .5), n("rgb", .55, distance=10)])
P("zoom_batida_suave", "Zoom na Batida", S, [n("zoom_batida_1_medio", .85), n("glow", .35)])
P("viral_punch", "Viral Punch", S, [n("punch_zoom", .85, amount=.2), n("chromatic", .5, amount=6), n("flash", .35, amount=.5)])
P("tiktok_glow", "TikTok Glow", S, [n("rgb_45_sutil", .5), n("glow", .5), n("zoom_pulse", .6, amount=.06), n("sharpen", .3)])
P("reels_clean", "Reels Clean", S, [n("sharpen", .4), n("grade_matte_suave", .55), n("vignette", .2), n("glow", .25)])
P("vlog_vibrante", "Vlog Vibrante", S, [n("grade_kodak", .6), n("sharpen", .35), n("bloom", .3), n("vignette", .2)])
P("podcast_energia", "Podcast Energia", S, [n("zoom_batida_3_lento", .6), n("grade_documentario", .45), n("vignette", .3)])
P("transicao_whip", "Whip Pan", S, [n("blur_dir_0_forte", .85), n("speed_blur", .6), n("rgb_0_medio", .5)], 350)
P("transicao_glitch", "Corte Glitch", S, [n("glitch_6", .85), n("rgb", .7, distance=16), n("flash", .45, amount=.6)], 300)
P("transicao_flash", "Corte Flash", S, [n("flash", .9, amount=1.0), n("zoom_blur", .6)], 220)
P("transicao_zoom", "Corte Zoom", S, [n("blur_zoom_centro_forte", .85), n("punch_zoom", .7, amount=.2)], 300)
P("transicao_redemoinho", "Corte Redemoinho", S, [n("twirl", .6), n("zoom_rotate", .6), n("radial_blur", .6)], 450)
P("drift_cam", "Câmera na Mão", S, [n("handheld_camera", .55), n("sharpen", .25), n("vignette", .2)])
P("espelho_simetrico", "Espelho", S, [n("espelho_0_50", .9), n("glow", .3)])
P("caleidoscopio_festa", "Caleidoscópio", S, [n("caleidoscopio_3", .8), n("rgb_onda_2", .5), n("bloom", .4)])
P("pixel_censura", "Pixel Censura", S, [n("pixel_32", 1.0)])
P("dupla_exposicao", "Dupla Exposição", S, [n("double_exposure", .75), n("grade_matte_suave", .5), n("glow", .35)])
P("fantasma_eco", "Fantasma", S, [n("ghost_trail", .75), n("frame_echo", .5), n("tom_azul_suave", .3)])

# ───────────────────────── Terror / Suspense ─────────────────────────
H = "Terror"
P("terror_classico", "Terror Clássico", H, [n("tom_verde_suave", .3), n("grade_documentario", .65), n("vinheta_preta_forte", .9), n("film_grain", .45), n("flicker", .35)])
P("assombrado", "Assombrado", H, [n("ghost_trail", .6), n("tom_azul_forte", .3), n("vinheta_azul_noite_forte", .75), n("tv_static", .25, amount=.3)])
P("found_footage", "Found Footage", H, [n("retro_camcorder", .75), n("handheld_camera", .6), n("vhs_glitch", .45), n("vinheta_preta_forte", .7)])
P("pesadelo", "Pesadelo", H, [n("tom_vermelho_suave", .4), n("rgb_onda_5", .5), n("heat_haze", .35), n("vinheta_vermelha_forte", .85), n("film_grain", .35)])
P("tensao_lenta", "Tensão Lenta", H, [n("dolly_zoom", .6), n("vinheta_preta_forte", .8), n("grade_cinema_frio", .5)])
P("susto", "Susto", H, [n("punch_zoom", 1.0, amount=.45), n("flash", .85, amount=1.0), n("heavy_shake", .8), n("tv_static", .5)], 350)
P("floresta_noturna", "Floresta Noturna", H, [n("tom_verde_forte", .35), n("grade_cinema_frio", .5), n("clima_vaga_lumes", .5), n("vinheta_verde_escuro_forte", .8), n("clima_cinzas", .3)])
P("sangue_frio", "Sangue Frio", H, [n("tom_vermelho_forte", .45), n("grade_cinema_frio", .45), n("vinheta_vinho_forte", .8), n("film_grain", .3)])

# ───────────────────────── Clima / Natureza ─────────────────────────
W = "Clima"
P("noite_chuvosa", "Noite Chuvosa", W, [n("clima_chuva_fina", .8), n("grade_cinema_frio", .55), n("vinheta_azul_noite_suave", .6), n("lens_blur", .15)])
P("tempestade", "Tempestade", W, [n("clima_chuva_forte", .85), n("lightning", .6), n("heavy_shake", .3, distance=6), n("grade_documentario", .6), n("vinheta_preta_forte", .6)])
P("nevasca", "Nevasca", W, [n("clima_tempestade_de_neve", .8), n("tom_azul_claro_suave", .4), n("vinheta_branca_suave", .45), n("glow", .3)])
P("neve_calma", "Neve Calma", W, [n("clima_neve_leve", .8), n("tom_azul_claro_suave", .3), n("glow", .35)])
P("chuva_neon", "Chuva Neon", W, [n("clima_chuva_neon", .8), n("neon_edge", .3), n("bloom", .5), n("vinheta_roxa_suave", .5)])
P("primavera_petalas", "Pétalas", W, [n("clima_petalas", .8), n("tom_rosa_suave", .35), n("glow", .4)])
P("fundo_do_mar", "Fundo do Mar", W, [n("clima_bolhas", .75), n("tom_turquesa_forte", .45), n("water_distortion", .35), n("vinheta_azul_noite_suave", .55)])
P("deserto_quente", "Deserto Quente", W, [n("tom_laranja_forte", .5), n("heat_haze", .5), n("clima_poeira_dourada", .6), n("vinheta_laranja_suave", .5)])
P("celebracao_confete", "Confete", W, [n("clima_confete_rosa", .75), n("clima_confete_verde", .6), n("zoom_pulse", .5, amount=.06)], 2000)
P("vaga_lumes", "Vaga-lumes", W, [n("clima_vaga_lumes", .85), n("tom_verde_suave", .25), n("vinheta_verde_escuro_suave", .55), n("glow", .35)])

# ───────────────────────── Distorção ─────────────────────────
D = "Distorsao"
P("olho_de_peixe", "Olho de Peixe", D, [n("fisheye", .75), n("sharpen", .25), n("vignette", .3)])
P("sob_a_agua", "Sob a Água", D, [n("water_distortion", .6), n("tom_turquesa_suave", .45), n("gaussian", .15, radius=2)])
P("miragem", "Miragem", D, [n("miragem_2", .7), n("heat_haze", .4), n("tom_ambar_suave", .3)])
P("ondas_psicodelicas", "Ondas Psicodélicas", D, [n("onda_6", .7), n("redemoinho_3", .45), n("rgb_onda_4", .5), n("tom_magenta_suave", .3)])
P("lente_bulbo", "Lente Bulbo", D, [n("bulge", .7), n("chromatic", .4, amount=4)])
P("tilt_miniatura", "Miniatura", D, [n("tilt_shift", .9), n("grade_kodak", .4), n("sharpen", .3)])

# ───────────────────────────────────────────────────────────────────────────

def resolve(name):
    for cand in (name, "recly_" + name):
        if cand in CATALOG:
            return CATALOG[cand]
    sys.exit(f"effect not found: {name}")

def build(pid, name, category, ms, nodes):
    assert re.fullmatch(r"[a-z][a-z0-9_]{0,63}", pid), pid
    out_nodes = []
    for effect, intensity, values in nodes:
        definition = resolve(effect)
        params = {p["id"]: p for p in definition["parameters"]}
        clean = {}
        for key, value in values.items():
            if key not in params:
                sys.exit(f"{pid}: {definition['id']} has no parameter '{key}' (has {list(params)})")
            p = params[key]
            clean[key] = round(min(max(float(value), p["min"]), p["max"]), 4)
        node = {"assetId": definition["id"], "intensity": round(intensity, 3)}
        if clean:
            node["values"] = clean
        out_nodes.append(node)
    assert 1 <= len(out_nodes) <= 8, pid
    preset = {"schemaVersion": 1, "id": pid, "name": name, "category": category, "nodes": out_nodes, "generated": True}
    if ms:
        preset["durationMs"] = ms
    return preset

presets = KEEP + [build(*p) for p in NEW]
ids = [p["id"] for p in presets]
dupes = {i for i in ids if ids.count(i) > 1}
if dupes:
    sys.exit(f"duplicate ids: {dupes}")
text = json.dumps(presets, ensure_ascii=False, separators=(",", ":"))
(ROOT / "effect_presets.json").write_text(text + "\n")
print(f"{len(KEEP)} kept + {len(NEW)} new = {len(presets)} presets, {len(text)} bytes")
