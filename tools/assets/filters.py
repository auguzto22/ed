import json
ED = '/home/claude/ed/editor-engine/src/main/assets/editor'
G = [  # id, nome, exposure, contrast, saturation, vibrance, temperature, tint, gamma, sh_hue, sh_tint, hi_hue, hi_tint, fade, vignette
 ('teal_orange', 'Teal & Orange', 0, .15, .1, .2, .15, 0, 1, .52, .8, .08, .7, 0, .2),
 ('cinema_cold', 'Cinema Frio', -.05, .2, -.15, 0, -.45, .05, 1, .58, .5, .55, .2, .05, .3),
 ('cinema_warm', 'Cinema Quente', 0, .15, .05, .15, .45, .05, 1, .05, .3, .1, .4, .05, .25),
 ('golden_hour', 'Hora Dourada', .1, .05, .15, .25, .6, .1, 1.05, .07, .3, .12, .5, 0, .15),
 ('blue_hour', 'Hora Azul', -.05, .1, 0, .1, -.55, .1, 1, .62, .6, .58, .2, .05, .2),
 ('vintage_70s', 'Anos 70', 0, -.1, -.15, 0, .35, .1, 1.05, .1, .3, .14, .3, .35, .3),
 ('faded_film', 'Filme Lavado', .05, -.2, -.25, 0, .1, 0, 1.1, .55, .2, .1, .2, .5, .15),
 ('kodak_like', 'Retrato Kodak', .05, .1, .1, .2, .2, -.05, 1, .5, .2, .09, .35, .1, .1),
 ('fuji_like', 'Verde Fuji', 0, .1, .05, .1, -.1, -.2, 1, .45, .35, .15, .2, .1, .1),
 ('moody', 'Moody', -.2, .25, -.3, 0, -.1, 0, .95, .6, .4, .1, .2, .15, .45),
 ('noir', 'Noir', -.05, .45, -1, 0, 0, 0, 1, 0, 0, 0, 0, 0, .5),
 ('mono_soft', 'P&B Suave', .05, -.1, -1, 0, 0, 0, 1.1, 0, 0, 0, 0, .25, .1),
 ('mono_warm', 'P&B Quente', 0, .15, -1, 0, 0, 0, 1, .08, .35, .1, .25, .1, .2),
 ('vibrant', 'Vibrante', .05, .15, .35, .4, .05, 0, 1, 0, 0, 0, 0, 0, 0),
 ('pastel', 'Pastel', .15, -.25, -.1, .2, .05, .1, 1.15, .85, .25, .55, .2, .3, 0),
 ('cyberpunk', 'Cyberpunk', 0, .25, .2, .3, -.2, .35, 1, .75, .8, .52, .6, 0, .35),
 ('miami', 'Miami', .05, .1, .25, .3, .1, .3, 1, .85, .5, .5, .5, .05, .15),
 ('matrix', 'Código Verde', -.05, .2, -.4, 0, -.1, -.6, 1, .33, .7, .33, .4, .05, .35),
 ('desert', 'Deserto', .1, .1, -.05, .1, .55, .05, 1.05, .07, .4, .1, .3, .1, .15),
 ('arctic', 'Ártico', .1, .05, -.3, 0, -.6, 0, 1.05, .57, .4, .55, .3, .1, .1),
 ('forest', 'Floresta', -.05, .15, .05, .2, -.05, -.3, 1, .38, .4, .2, .2, .05, .25),
 ('sunset_pop', 'Pôr do Sol', .05, .15, .25, .3, .4, .2, 1, .9, .4, .08, .5, 0, .2),
 ('rose', 'Rosé', .1, -.05, -.05, .1, .15, .3, 1.05, .92, .3, .95, .25, .15, .1),
 ('lavender', 'Lavanda', .05, -.05, -.15, .1, -.1, .25, 1.05, .75, .35, .8, .25, .15, .1),
 ('food', 'Comida', .1, .1, .2, .35, .25, -.05, 1, 0, 0, .1, .2, 0, 0),
 ('portrait', 'Retrato Suave', .1, -.05, -.05, .1, .15, .05, 1.08, .05, .15, .08, .2, .1, .15),
 ('street', 'Rua', -.05, .3, -.35, 0, -.05, 0, .95, .55, .3, .1, .15, .05, .35),
 ('bleach_soft', 'Prata Suave', 0, .3, -.45, 0, 0, 0, 1, .55, .1, .1, .1, 0, .2),
 ('analog_summer', 'Verão Analógico', .1, 0, .1, .2, .35, -.1, 1.05, .45, .2, .12, .35, .2, .1),
 ('night_city', 'Cidade à Noite', -.1, .2, .1, .2, -.35, .2, .95, .7, .5, .1, .4, 0, .3),
 ('dramatic', 'Dramático', -.15, .5, -.1, .1, 0, 0, .95, .6, .3, .1, .3, 0, .5),
 ('clean_bright', 'Limpo e Claro', .2, .05, .05, .15, 0, 0, 1.1, 0, 0, 0, 0, 0, 0),
 ('cross_film', 'Película Cruzada', 0, .2, .1, .1, -.1, -.15, 1, .3, .5, .15, .5, .1, .2),
 ('sepia_soft', 'Sépia Suave', .05, 0, -.7, 0, .5, .1, 1.05, .08, .4, .1, .4, .2, .2),
 ('cold_steel', 'Aço', -.05, .25, -.5, 0, -.35, 0, 1, .58, .3, .55, .1, 0, .3),
 ('gaming_neon', 'Neon Gamer', .05, .25, .4, .4, -.15, .25, 1, .78, .6, .48, .5, 0, .25),
 ('lofi', 'Lo-fi', 0, -.15, -.2, 0, .2, .15, 1.1, .8, .3, .12, .3, .4, .25),
 ('hdr_pop', 'HDR Pop', .05, .35, .2, .45, 0, 0, .95, 0, 0, 0, 0, 0, .15),
]
S = [  # id, nome, mode, hue_a, hue_b, amount, levels
 ('duotone_blue_pink', 'Duotone Azul e Rosa', 0, .62, .92, .5, 6),
 ('duotone_purple_lime', 'Duotone Roxo e Lima', 0, .78, .22, .5, 6),
 ('duotone_red_cream', 'Duotone Vermelho', 0, .0, .12, .5, 6),
 ('posterize', 'Posterizar', 1, 0, 0, .5, 5),
 ('sepia', 'Sépia', 2, 0, 0, .5, 6),
 ('color_splash_red', 'Só o Vermelho', 3, .0, 0, .5, 6),
 ('color_splash_blue', 'Só o Azul', 3, .6, 0, .5, 6),
 ('halftone', 'Retícula', 4, 0, .58, .4, 6),
 ('sketch', 'Esboço', 5, 0, 0, .5, 6),
 ('infrared', 'Infravermelho', 6, 0, 0, .5, 6),
 ('thermal', 'Câmera Térmica', 7, 0, 0, .5, 6),
 ('cross_process', 'Revelação Cruzada', 8, 0, 0, .5, 6),
 ('bleach_bypass', 'Bleach Bypass', 9, 0, 0, .5, 6),
 ('night_vision', 'Visão Noturna', 10, 0, 0, .5, 6),
 ('cyanotype', 'Cianótipo', 11, 0, 0, .5, 6),
 ('pop_art', 'Pop Art', 12, .95, .15, .5, 6),
 ('neon_edges', 'Bordas Neon', 13, .5, .85, .5, 6),
 ('gradient_map_sunset', 'Mapa Pôr do Sol', 14, .9, .12, .5, 6),
 ('gradient_map_ocean', 'Mapa Oceano', 14, .66, .45, .5, 6),
 ('high_contrast_bw', 'P&B Alto Contraste', 15, 0, 0, .5, 6),
]
def P(i, n, lo, hi, d): return {'id': i, 'name': n, 'type': 'float', 'min': lo, 'max': hi, 'default': d}
def grade(g):
    _, _, ex, co, sa, vi, te, ti, ga, shh, sht, hih, hit, fa, vg = g
    return [P('exposure', 'Exposição', -2, 2, ex), P('contrast', 'Contraste', -1, 1, co), P('saturation', 'Saturação', -1, 1, sa),
            P('vibrance', 'Vibrância', -1, 1, vi), P('temperature', 'Temperatura', -1, 1, te), P('tint', 'Tinta', -1, 1, ti),
            P('gamma', 'Gama', .4, 2.2, ga), P('shadow_hue', 'Matiz das sombras', 0, 1, shh), P('shadow_tint', 'Cor nas sombras', 0, 1, sht),
            P('highlight_hue', 'Matiz das luzes', 0, 1, hih), P('highlight_tint', 'Cor nas luzes', 0, 1, hit), P('fade', 'Desbotar', 0, 1, fa),
            P('vignette', 'Vinheta', 0, 1, vg)]
cat = json.load(open(f'{ED}/effects.json'))
ids = {e['id'] for e in cat}
added = []
for g in G:
    e = {'id': 'color_' + g[0], 'version': 1, 'engineVersion': 1, 'type': 'EFFECT', 'name': g[1], 'category': 'Color',
         'engine': 'COMPOSITOR', 'shader': 'recly_color_grade.frag', 'license': 'Recly original', 'cost': 'LOW', 'parameters': grade(g)}
    assert e['id'] not in ids; cat.append(e); added.append(e['id'])
for st in S:
    _, name, mode, ha, hb, am, lv = st
    e = {'id': 'style_' + st[0], 'version': 1, 'engineVersion': 1, 'type': 'EFFECT', 'name': name, 'category': 'Color',
         'engine': 'COMPOSITOR', 'shader': 'recly_color_style.frag', 'license': 'Recly original', 'cost': 'LOW',
         'parameters': [P('mode', 'Estilo', 0, 15, float(mode)), P('hue_a', 'Cor A', 0, 1, ha), P('hue_b', 'Cor B', 0, 1, hb),
                        P('amount', 'Força', 0, 1, am), P('levels', 'Níveis', 2, 16, float(lv))]}
    assert e['id'] not in ids; cat.append(e); added.append(e['id'])
json.dump(cat, open(f'{ED}/effects.json', 'w'), indent=1, ensure_ascii=False)
json.dump(added, open('/tmp/claude-0/-home-claude-ed/4241a4ef-8c73-5cc8-916f-1296d60f6d03/scratchpad/gen/added_filters.json', 'w'))
print(len(added), 'filtros novos; efeitos total', len(cat))
