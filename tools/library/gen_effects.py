"""Appends ~480 named effect variants (built on 30 new shaders) to effects.json."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import shaders, validate

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "editor-engine/src/main/assets/editor")
ENGINE = {"lib_tint": "COLOR_LUT", "lib_duotone": "COLOR_LUT", "lib_grade": "COLOR_LUT", "lib_split": "RGB_CHANNEL", "lib_dirblur": "BLUR",
          "lib_zoomblur": "BLUR", "lib_vig": "COMPOSITOR", "lib_pixel": "CREATIVE", "lib_halftone": "CREATIVE", "lib_scan": "FILM_NOISE",
          "lib_slice": "DISTORTION_UV", "lib_wave": "DISTORTION_UV", "lib_swirl": "DISTORTION_UV", "lib_kaleido": "CREATIVE",
          "lib_fold": "CREATIVE", "lib_pulse": "LIGHT_FLASH", "lib_leak": "LIGHT_FLASH", "lib_grain": "FILM_NOISE", "lib_poster": "CREATIVE",
          "lib_edge": "CREATIVE", "lib_bokeh": "BLUR", "lib_punch": "TRANSFORM_MOTION", "lib_camshake": "SHAKE", "lib_bars": "COMPOSITOR",
          "lib_rays": "GLOW_BLOOM", "lib_rgbwave": "RGB_CHANNEL", "lib_crt": "FILM_NOISE", "lib_haze": "DISTORTION_UV",
          "lib_sparkle": "PARTICLE", "lib_weather": "PARTICLE"}
COST = {"lib_dirblur": "MEDIUM", "lib_zoomblur": "MEDIUM", "lib_edge": "MEDIUM", "lib_bokeh": "MEDIUM", "lib_weather": "MEDIUM"}

V = []  # (shader, suffix, name, category, overrides)


def v(shader, suffix, name, cat, **ov):
    V.append((shader, suffix, name, cat, ov))


def slug(text):
    table = {"á": "a", "à": "a", "ã": "a", "â": "a", "é": "e", "ê": "e", "í": "i", "ó": "o", "ô": "o", "õ": "o", "ú": "u", "ç": "c", "&": "e"}
    out = "".join(table.get(c, c) for c in text.lower())
    return "".join(c if c.isalnum() else "_" for c in out).strip("_")


def rgb(h):
    h = h.lstrip("#")
    return {"r": round(int(h[0:2], 16) / 255, 3), "g": round(int(h[2:4], 16) / 255, 3), "b": round(int(h[4:6], 16) / 255, 3)}


COLORS = {"vermelho": "#FF3B3B", "laranja": "#FF8A3D", "âmbar": "#FFB347", "amarelo": "#FFE14D", "lima": "#B6FF3D", "verde": "#3DDC6A",
          "menta": "#4DFFC3", "turquesa": "#2AE6D4", "ciano": "#34D7F0", "azul claro": "#5CA8FF", "azul": "#3D5BFF", "índigo": "#5B3DFF",
          "violeta": "#9B5BFF", "roxo": "#B04DFF", "magenta": "#FF3DD0", "rosa": "#FF6FA8", "coral": "#FF6F61", "pêssego": "#FFB59B",
          "dourado": "#E8B64A", "bronze": "#B87333", "prata": "#C8CCD6", "vinho": "#8E1B3A", "petróleo": "#1B6F7F", "oliva": "#8A9A3A"}

# Color: tints (24 colors x 2)
for name, hx in COLORS.items():
    v("lib_tint", f"tom_{slug(name)}_suave", f"Tom {name} suave", "Color", amount=.35, **rgb(hx))
    v("lib_tint", f"tom_{slug(name)}_forte", f"Tom {name} forte", "Color", amount=.8, **rgb(hx))

# Color: duotones (30)
DUO = {"Ametista": ("#1B0F3A", "#E5B8FF"), "Sunset": ("#2B0F4A", "#FFB347"), "Oceano": ("#04152B", "#7FE3FF"), "Floresta": ("#0A2414", "#C6F5A0"),
       "Cereja": ("#2A0612", "#FF8FA8"), "Sépia": ("#2B1A0C", "#F3D9A8"), "Neon night": ("#12002B", "#00F0FF"), "Pôr de sol rosa": ("#3A0F2E", "#FFC4A8"),
       "Gelo": ("#08203A", "#E8FAFF"), "Ouro negro": ("#0C0A06", "#E8B64A"), "Menta": ("#06242A", "#B8FFE6"), "Lava": ("#2A0600", "#FFD23F"),
       "Vaporwave": ("#2A0A5E", "#FF71CE"), "Cyberpunk": ("#0A0A2E", "#FF2E97"), "Matrix": ("#001A06", "#6BFF8F"), "Vinho": ("#1F0510", "#E88AA6"),
       "Cobre": ("#1F0F08", "#F0A878"), "Aurora": ("#041A24", "#8AFFD0"), "Lavanda": ("#1E1636", "#E3D4FF"), "Pêssego": ("#3A1A14", "#FFD9C0"),
       "Esmeralda": ("#02221A", "#7AF0C0"), "Safira": ("#050E3A", "#8AB4FF"), "Rubi": ("#2A0008", "#FF6F7F"), "Café": ("#1A0F08", "#E0C09A"),
       "Algodão doce": ("#3A1E5A", "#FFD0F0"), "Tóxico": ("#0A1A00", "#E0FF3D"), "Noir azul": ("#05070F", "#C8D4F0"), "Papel": ("#2A2418", "#FFF8E6"),
       "Chama": ("#1A0000", "#FFA03D"), "Galáxia": ("#0A0224", "#9B87FF")}
for name, (a, b) in DUO.items():
    sa, sb = rgb(a), rgb(b)
    v("lib_duotone", "duo_" + slug(name), f"Duotone {name}", "Color", sr=sa["r"], sg=sa["g"], sb=sa["b"], hr=sb["r"], hg=sb["g"], hb=sb["b"], amount=.95)

# Color: grades (40 named looks)
GR = {"Cinema quente": dict(temp=.5, contrast=1.15, sat=1.05, lift=.02), "Cinema frio": dict(temp=-.5, contrast=1.15, sat=1.0, lift=.02),
      "Teal & laranja": dict(temp=.25, tint=-.3, contrast=1.2, sat=1.2), "Filme antigo": dict(temp=.3, contrast=.9, sat=.8, lift=.06, gamma=1.1),
      "Matte suave": dict(lift=.09, contrast=.85, sat=.9), "Alto contraste": dict(contrast=1.5, sat=1.1, gamma=.9), "Vívido": dict(sat=1.6, contrast=1.15),
      "Dessaturado": dict(sat=.45, contrast=1.1), "P&B suave": dict(sat=0, contrast=1.05), "P&B dramático": dict(sat=0, contrast=1.5, gamma=.85),
      "Hora dourada": dict(temp=.8, gain=1.1, sat=1.2, lift=.02), "Madrugada": dict(temp=-.7, gain=.85, sat=.8, contrast=1.1), "Verão": dict(temp=.4, sat=1.35, gain=1.08),
      "Inverno": dict(temp=-.45, sat=.75, gain=1.05), "Pele radiante": dict(temp=.2, gain=1.1, gamma=1.15, sat=1.05), "Terra": dict(temp=.35, tint=.2, sat=.9, contrast=1.1),
      "Cyber": dict(temp=-.3, tint=.5, sat=1.5, contrast=1.25), "Pastel": dict(lift=.1, sat=.7, gain=1.1, contrast=.85), "Sombrio": dict(gain=.75, contrast=1.3, sat=.8, gamma=.85),
      "Brilhante": dict(gain=1.25, gamma=1.2, sat=1.1), "Verde floresta": dict(tint=.7, temp=-.1, sat=1.1), "Rosado": dict(tint=-.6, temp=.1, sat=1.1),
      "Azulado": dict(temp=-.8, sat=1.0), "Alaranjado": dict(temp=.9, sat=1.1), "Lavado": dict(lift=.12, contrast=.75, sat=.7), "Crocante": dict(contrast=1.35, sat=1.25, gamma=1.05),
      "Sonho": dict(lift=.07, gain=1.15, sat=.9, contrast=.88, temp=.15), "Documentário": dict(sat=.7, contrast=1.15, temp=-.1, gamma=.95), "Moda": dict(contrast=1.3, sat=.65, gain=1.05),
      "Gamer": dict(contrast=1.3, sat=1.4, temp=-.2, tint=.3), "Praia": dict(temp=.3, sat=1.3, gain=1.12, contrast=1.05), "Neve": dict(temp=-.3, gain=1.2, sat=.8, gamma=1.2),
      "Deserto": dict(temp=.6, sat=.85, gain=1.1, contrast=1.1), "Noturno neon": dict(temp=-.4, tint=.4, sat=1.5, gain=.9, contrast=1.3), "Retrô 80": dict(temp=.2, tint=-.4, sat=1.3, lift=.05, contrast=1.1),
      "Polaroid": dict(temp=.25, lift=.08, sat=.85, contrast=.95, tint=.1), "Kodak": dict(temp=.35, sat=1.15, contrast=1.1, lift=.03), "Fuji": dict(temp=-.15, tint=.3, sat=1.1, contrast=1.1),
      "Bleach": dict(sat=.5, contrast=1.45, gamma=.95), "Cross process": dict(temp=-.2, tint=-.5, sat=1.4, contrast=1.3)}
CINEMATIC_LOOKS = ("Cinema", "Teal", "Filme", "Documentário", "Matte", "Kodak", "Fuji")
for name, ov in GR.items():
    v("lib_grade", "grade_" + slug(name), f"Look {name}", "Cinematic" if any(k in name for k in CINEMATIC_LOOKS) else "Color", **ov)

# RGB (20)
for ang in (0, 45, 90, 135):
    for amt, an in ((.004, "sutil"), (.01, "médio"), (.022, "forte")):
        v("lib_split", f"rgb_{ang}_{slug(an)}", f"RGB {an} {ang}°", "RGB", angle=float(ang), amount=amt)
for ang in (0, 90, 45, 135):
    v("lib_split", f"rgb_tremendo_{ang}", f"RGB tremendo {ang}°", "RGB", angle=float(ang), amount=.014, wobble=1.0)
for i, (a, f, s) in enumerate([(.004, 10, 2), (.008, 20, 4), (.012, 30, 6), (.02, 14, 3), (.03, 40, 8), (.006, 50, 10), (.016, 8, 1.5), (.025, 24, 5)]):
    v("lib_rgbwave", f"rgb_onda_{i+1}", f"RGB ondulado {i+1}", "RGB", amount=a, freq=float(f), speed=float(s))

# Blur (18 directional + 15 radial + 8 bokeh)
for ang in (0, 30, 60, 90, 120, 150):
    for amt, an in ((.01, "leve"), (.03, "médio"), (.07, "forte")):
        v("lib_dirblur", f"blur_dir_{ang}_{slug(an)}", f"Desfoque {ang}° {an}", "Blur", angle=float(ang), amount=amt)
for cn, (cx, cy) in {"centro": (.5, .5), "esquerda": (.25, .5), "direita": (.75, .5), "cima": (.5, .25), "baixo": (.5, .75)}.items():
    for amt, an in ((.06, "leve"), (.14, "médio"), (.26, "forte")):
        v("lib_zoomblur", f"blur_zoom_{cn}_{slug(an)}", f"Desfoque radial {cn} {an}", "Blur", cx=cx, cy=cy, amount=amt)
for i, (r, b, a) in enumerate([(6, .3, 1), (10, .6, 1), (14, 1.0, 1), (18, 1.4, 1), (24, 1.8, 1), (30, 1.0, .8), (36, 2.0, .9), (12, 0, 1)]):
    v("lib_bokeh", f"bokeh_{i+1}", f"Bokeh {i+1}", "Blur", radius=float(r), bright=float(b), amount=float(a))

# Light: vignettes (12 colors x 2), pulses (12), leaks (24), rays (8)
VIG = {"preta": "#000000", "azul noite": "#050A24", "vinho": "#2A0510", "verde escuro": "#031A0C", "sépia": "#2B1A0C", "roxa": "#1E0A3A",
       "branca": "#FFFFFF", "rosa": "#FF8FC4", "dourada": "#E8B64A", "ciano": "#34D7F0", "vermelha": "#7A0A0A", "laranja": "#FF8A3D"}
for name, hx in VIG.items():
    v("lib_vig", f"vinheta_{slug(name)}_suave", f"Vinheta {name} suave", "Light", amount=.6, size=.75, soft=.7, **rgb(hx))
    v("lib_vig", f"vinheta_{slug(name)}_forte", f"Vinheta {name} forte", "Light", amount=.95, size=.5, soft=.5, **rgb(hx))
for i, (a, r, sh, w) in enumerate([(.3, 1, 3, 0), (.5, 2, 4, 0), (.8, 3, 6, 0), (.6, 4, 8, 0), (1.0, 1.5, 10, 0), (.4, 6, 2, 0),
                                    (.3, 1, 3, 1), (.5, 2, 4, 1), (.8, 3, 6, 1), (.6, 4, 8, 1), (1.0, 1.5, 10, 1), (.4, 6, 2, 1)]):
    v("lib_pulse", f"flash_{i+1}", f"Pulso de luz {i+1}" + (" quente" if w else ""), "Light", amount=a, rate=float(r), sharp=float(sh), warm=float(w))
LEAKS = {"laranja": "#FF8A3D", "vermelho": "#FF3B3B", "rosa": "#FF6FA8", "dourado": "#FFC857", "amarelo": "#FFE14D", "roxo": "#9B5BFF",
         "azul": "#3D8BFF", "ciano": "#34D7F0", "verde": "#3DDC6A", "magenta": "#FF3DD0", "branco": "#FFFFFF", "pêssego": "#FFB59B"}
for name, hx in LEAKS.items():
    v("lib_leak", f"vazamento_{slug(name)}_a", f"Vazamento {name} diagonal", "Film", amount=.85, angle=35.0, width=.55, drift=.8, **rgb(hx))
    v("lib_leak", f"vazamento_{slug(name)}_b", f"Vazamento {name} lateral", "Film", amount=.7, angle=180.0, width=.4, drift=1.4, **rgb(hx))
for i, (c, hx) in enumerate([("branco", "#FFFFFF"), ("dourado", "#FFD27A"), ("rosa", "#FF9AC8"), ("ciano", "#7FE3FF"), ("laranja", "#FF9A4D"),
                              ("violeta", "#B58CFF"), ("verde", "#8AFFB0"), ("vermelho", "#FF5B5B")]):
    v("lib_rays", f"raios_{c}", f"Raios de luz {c}", "Light", amount=.7 + (i % 3) * .2, count=float(8 + i * 3), cx=.5 if i % 2 else .25,
      cy=.2 + (i % 4) * .1, speed=.5 + (i % 3) * .5, **rgb(hx))

# Glitch / Distortion / Creative
for i, (a, r, rt, c) in enumerate([(.04, 12, 4, 0), (.08, 20, 6, .004), (.12, 30, 10, .008), (.16, 8, 3, .012), (.2, 40, 14, .006), (.06, 60, 20, .01),
                                    (.1, 16, 8, .02), (.14, 24, 2, 0), (.03, 6, 5, .003), (.18, 50, 24, .016), (.09, 10, 12, .01), (.12, 70, 6, .0),
                                    (.05, 20, 18, .014), (.15, 14, 9, .005), (.07, 36, 3, .018), (.2, 80, 28, .02), (.11, 5, 7, .008), (.13, 28, 15, .012)]):
    v("lib_slice", f"glitch_{i+1}", f"Glitch fatiado {i+1}", "Glitch", amount=a, rows=float(r), rate=float(rt), rgb=c)
for i, (a, f, s, d) in enumerate([(.01, 6, 1, 0), (.02, 10, 2, 0), (.03, 16, 3, 0), (.05, 8, 4, 0), (.04, 24, 6, 0), (.015, 30, 2, 0), (.06, 4, 3, 0), (.025, 40, 8, 0),
                                   (.01, 6, 1, 1), (.02, 10, 2, 1), (.03, 16, 3, 1), (.05, 8, 4, 1), (.04, 24, 6, 1), (.015, 30, 2, 1), (.06, 4, 3, 1), (.025, 40, 8, 1)]):
    v("lib_wave", f"onda_{i+1}", f"Ondulação {'vertical' if d else 'horizontal'} {i % 8 + 1}", "Distortion", amp=a, freq=float(f), speed=float(s), dir=float(d))
for i, (ang, rad, sp, cx, cy) in enumerate([(2, .5, 0, .5, .5), (-2, .5, 0, .5, .5), (4, .7, 0, .5, .5), (-4, .7, 0, .5, .5), (6, .9, .5, .5, .5),
                                              (3, .4, 1, .3, .5), (-3, .4, 1, .7, .5), (5, .6, 2, .5, .3), (-5, .6, 2, .5, .7), (8, 1, 1.5, .5, .5)]):
    v("lib_swirl", f"redemoinho_{i+1}", f"Redemoinho {i+1}", "Distortion", angle=float(ang), radius=rad, spin=float(sp), cx=cx, cy=cy)
for i, (a, s, sp, w) in enumerate([(.003, 10, 1, 0), (.006, 14, 2, .2), (.01, 20, 3, .4), (.015, 8, 2, .5), (.02, 26, 4, .6), (.008, 30, 1, .8), (.012, 12, 5, 1), (.025, 18, 3, .3)]):
    v("lib_haze", f"miragem_{i+1}", f"Miragem de calor {i+1}", "Distortion", amount=a, scale=float(s), speed=float(sp), warm=w)
for i, (sg, z, sp) in enumerate([(3, 1, 0), (4, 1, .3), (5, 1.2, .5), (6, 1, 0), (6, 1.6, .8), (8, 1, .4), (8, 2, 1), (10, 1.4, .6), (12, 1, 1.2), (16, 1.8, 0)]):
    v("lib_kaleido", f"caleidoscopio_{i+1}", f"Caleidoscópio {sg} lados · {i+1}", "Creative", segs=float(sg), zoom=float(z), spin=float(sp))
for m, mn in enumerate(["esquerda para direita", "direita para esquerda", "cima para baixo", "baixo para cima"]):
    for p, pn in ((.5, "meio"), (.35, "terço")):
        v("lib_fold", f"espelho_{m}_{int(p*100)}", f"Espelho {mn} ({pn})", "Creative", mode=float(m), pos=p, blend=1.0)
for c in (10, 16, 24, 32, 48, 64, 96, 140, 200, 80):
    v("lib_pixel", f"pixel_{c}", f"Pixel {c} blocos", "Creative", cells=float(c), mixv=1.0)
for i, (c, s, a) in enumerate([(30, .2, 1), (40, .4, 1), (60, .3, 1), (80, .5, 1), (110, .3, 1), (140, .4, .8), (50, .8, 1), (70, .2, .6)]):
    v("lib_halftone", f"reticula_{i+1}", f"Retícula {i+1}", "Creative", cells=float(c), soft=s, amount=float(a))
for i, (l, s) in enumerate([(2, 1), (3, 1.2), (4, 1.3), (5, 1.4), (6, 1.0), (8, 1.5), (10, 1.2), (12, .6)]):
    v("lib_poster", f"posterizar_{i+1}", f"Posterizar {int(l)} níveis", "Creative", levels=float(l), sat=float(s), amount=1.0)
EDGE = {"ciano": "#34D7F0", "magenta": "#FF3DD0", "branco": "#FFFFFF", "verde": "#6BFF8F", "amarelo": "#FFE14D", "laranja": "#FF8A3D"}
for name, hx in EDGE.items():
    v("lib_edge", f"contorno_{name}", f"Contorno neon {name}", "Creative", strength=3.0, base=.35, **rgb(hx))
    v("lib_edge", f"desenho_{name}", f"Desenho de linhas {name}", "Creative", strength=4.5, base=0.0, **rgb(hx))

# Motion / Camera
for i, a in enumerate([.08, .14, .2, .3, .4, .5]):
    for r, rn in ((1.0, "lento"), (2.0, "médio"), (3.0, "rápido")):
        v("lib_punch", f"zoom_batida_{i+1}_{slug(rn)}", f"Zoom na batida {int(a*100)}% {rn}", "Motion", amount=a, rate=r, sharp=4.0 + i)
for i, (a, s, r) in enumerate([(.004, 4, .005), (.008, 8, .01), (.012, 14, .02), (.02, 20, .03), (.03, 28, .05), (.045, 36, .07), (.015, 6, 0), (.025, 12, 0),
                                (.035, 24, .02), (.06, 40, .08), (.01, 3, .01), (.05, 18, .04)]):
    v("lib_camshake", f"camera_{i+1}", f"Câmera na mão {i+1}", "Camera", amount=a, speed=float(s), rot=r)

# Cinematic bars, Retro / VHS / Film
BARS = {"preto": "#000000", "branco": "#FFFFFF", "vinho": "#2A0510", "azul": "#050A24"}
for name, hx in BARS.items():
    for size, sn in ((.08, "fina"), (.14, "cinema"), (.22, "larga")):
        v("lib_bars", f"barras_{name}_{sn}", f"Barras {name} {sn}", "Cinematic", size=size, soft=.004, side=0.0, **rgb(hx))
for name, hx in {"preto": "#000000", "branco": "#FFFFFF"}.items():
    v("lib_bars", f"barras_laterais_{name}", f"Barras laterais {name}", "Cinematic", size=.12, soft=.004, side=1.0, **rgb(hx))
for i, (l, st, ro, fl) in enumerate([(200, .4, 0, 0), (320, .6, 0, 0), (480, .8, 0, 0), (700, .5, 0, .2), (300, .5, .5, .3), (420, .7, 1, .5)]):
    v("lib_scan", f"linhas_{i+1}", f"Linhas de TV {i+1}", "Retro", lines=float(l), strength=st, roll=float(ro), flick=fl)
for i, (l, st, ro, fl) in enumerate([(240, .6, 1, .6), (360, .8, .7, .8), (180, .5, .4, 1), (520, .9, 1, .4), (300, .7, .3, 1), (260, .5, .8, .2)]):
    v("lib_scan", f"vhs_{i+1}", f"Fita VHS {i+1}", "VHS", lines=float(l), strength=st, roll=ro, flick=fl)
for i, (g, d, f) in enumerate([(.2, 0, 0), (.4, .2, .1), (.6, .4, .2), (.8, .6, .3), (1.0, .8, .4), (.5, 1.0, .5), (.3, .3, 0), (.9, .1, .6),
                               (.7, .7, .7), (1.0, 1.0, 1.0), (.15, .5, .2), (.45, .9, .3)]):
    v("lib_grain", f"granulado_{i+1}", f"Filme 16mm {i+1}", "Film", grain=float(g), dust=float(d), flick=float(f))
for i, (c, sc, mk, vg) in enumerate([(.1, .3, .2, .3), (.18, .5, .4, .5), (.25, .7, .5, .6), (.3, .9, .7, .8), (.12, .2, 0, .4), (.35, 1.0, 1.0, 1.0),
                                      (.2, .6, .3, .2), (.05, .4, .6, .5)]):
    v("lib_crt", f"crt_{i+1}", f"Tela CRT {i+1}", "Retro", curve=c, scan=sc, mask=float(mk), vig=float(vg))

# Particles
SPK = {"dourado": "#FFD27A", "branco": "#FFFFFF", "rosa": "#FF9AC8", "ciano": "#7FE3FF", "violeta": "#B58CFF", "verde": "#8AFFB0", "laranja": "#FF9A4D"}
for name, hx in SPK.items():
    v("lib_sparkle", f"brilho_{name}_fino", f"Brilhos {name} finos", "Particles", density=22.0, size=.18, speed=3.0, amount=1.0, **rgb(hx))
    v("lib_sparkle", f"brilho_{name}_grande", f"Brilhos {name} grandes", "Particles", density=10.0, size=.4, speed=2.0, amount=1.2, **rgb(hx))
WEA = {"neve leve": dict(density=14, speed=.25, size=.3, slant=.1, stretch=0, amount=.9, r=1, g=1, b=1),
       "neve densa": dict(density=30, speed=.4, size=.3, slant=.2, stretch=0, amount=1.2, r=1, g=1, b=1),
       "tempestade de neve": dict(density=40, speed=.9, size=.28, slant=.7, stretch=.2, amount=1.3, r=.95, g=.97, b=1),
       "chuva fina": dict(density=36, speed=1.6, size=.2, slant=.15, stretch=1, amount=.7, r=.8, g=.9, b=1),
       "chuva forte": dict(density=48, speed=2.4, size=.22, slant=.3, stretch=1, amount=1.1, r=.8, g=.9, b=1),
       "chuva de lado": dict(density=44, speed=2.2, size=.2, slant=.8, stretch=1, amount=1.0, r=.8, g=.9, b=1),
       "poeira dourada": dict(density=18, speed=.15, size=.2, slant=-.1, stretch=0, amount=.8, r=1, g=.82, b=.45),
       "cinzas": dict(density=20, speed=.2, size=.25, slant=-.3, stretch=0, amount=.8, r=.7, g=.65, b=.6),
       "pétalas": dict(density=12, speed=.3, size=.4, slant=-.4, stretch=0, amount=1.0, r=1, g=.6, b=.75),
       "bolhas": dict(density=10, speed=.3, size=.45, slant=0, stretch=0, amount=.8, r=.7, g=.95, b=1),
       "chuva neon": dict(density=44, speed=2.0, size=.2, slant=.2, stretch=1, amount=1.2, r=.2, g=.95, b=1),
       "chuva de fogo": dict(density=40, speed=1.8, size=.22, slant=.15, stretch=.8, amount=1.2, r=1, g=.5, b=.15),
       "confete verde": dict(density=14, speed=.5, size=.35, slant=.3, stretch=.1, amount=1.1, r=.4, g=1, b=.5),
       "confete rosa": dict(density=14, speed=.5, size=.35, slant=-.3, stretch=.1, amount=1.1, r=1, g=.4, b=.7),
       "vaga-lumes": dict(density=8, speed=.1, size=.5, slant=0, stretch=0, amount=1.3, r=.9, g=1, b=.4),
       "matrix": dict(density=50, speed=2.5, size=.2, slant=0, stretch=1, amount=1.3, r=.2, g=1, b=.4),
       "meteoros": dict(density=16, speed=3.0, size=.2, slant=.9, stretch=1, amount=1.4, r=1, g=.95, b=.8),
       "granizo": dict(density=26, speed=2.0, size=.35, slant=.5, stretch=.3, amount=1.2, r=.9, g=.95, b=1)}
for name, ov in WEA.items():
    v("lib_weather", "clima_" + slug(name), name.capitalize(), "Particles", **{k: float(x) for k, x in ov.items()})

DEFAULTS = {k: {p[0]: (p[2] + p[3]) / 2 for p in params} for k, (params, _) in shaders.S.items()}
# Neutral defaults where the range midpoint would look odd.
DEFAULTS["lib_grade"].update(lift=0, gamma=1, gain=1, sat=1, temp=0, tint=0, contrast=1)
DEFAULTS["lib_tint"].update(r=1, g=1, b=1, amount=.5)
DEFAULTS["lib_split"].update(wobble=0)
DEFAULTS["lib_fold"].update(mode=0, pos=.5, blend=1)
DEFAULTS["lib_swirl"].update(spin=0, cx=.5, cy=.5)
DEFAULTS["lib_zoomblur"].update(cx=.5, cy=.5)
DEFAULTS["lib_rays"].update(cx=.5, cy=.3, r=1, g=.9, b=.7)
DEFAULTS["lib_pulse"].update(warm=0)
DEFAULTS["lib_edge"].update(base=.5, r=1, g=1, b=1)
DEFAULTS["lib_bars"].update(side=0, soft=.004)


def entries():
    out = []
    for shader, suffix, name, cat, ov in V:
        params, _ = shaders.S[shader]
        unknown = set(ov) - {p[0] for p in params}
        assert not unknown, (shader, suffix, unknown)
        plist = []
        for pid, pname, lo, hi in params:
            default = ov.get(pid, DEFAULTS[shader][pid])
            assert lo <= default <= hi, (shader, suffix, pid, default, lo, hi)
            plist.append({"id": pid, "name": pname, "type": "float", "min": float(lo), "max": float(hi), "default": round(float(default), 4)})
        out.append({"id": "recly_" + suffix, "version": 1, "engineVersion": 1, "type": "EFFECT", "name": name, "category": cat,
                    "engine": ENGINE[shader], "shader": shader.replace("lib_", "recly_lib_") + ".frag", "license": "Recly original",
                    "cost": COST.get(shader, "LOW"), "parameters": plist})
    return out


def main():
    path = os.path.join(ASSETS, "effects.json")
    catalog = json.load(open(path))
    base = [e for e in catalog if "recly_lib_" not in e.get("shader", "")]
    new = entries()
    ids = [e["id"] for e in base] + [e["id"] for e in new]
    dupes = sorted({i for i in ids if ids.count(i) > 1})
    assert not dupes, dupes[:5]
    for e in new:
        assert len(json.dumps(e)) < 32768
        assert len(e["id"]) <= 64 and len(e["parameters"]) <= 16
    json.dump(base + new, open(path, "w"), ensure_ascii=False, indent=2)
    effects_dir = os.path.join(ASSETS, "effects")
    for key, (params, body) in shaders.S.items():
        validate.shader(body, key)
        open(os.path.join(effects_dir, key.replace("lib_", "recly_lib_") + ".frag"), "w").write(body + "\n")
    print(len(base), "existing +", len(new), "new effects;", len(shaders.S), "shaders")


main()
