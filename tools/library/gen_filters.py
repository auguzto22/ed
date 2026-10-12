"""Generates ~300 colour filters and appends them to the VideoFilter enum (between the GENERATED markers).

The enum ordinal is persisted in saved projects, so generation is append-only and deterministic: entries are
emitted in a fixed order and the first 32 constants are never touched.
"""
import colorsys, os, re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PROJECT = os.path.join(ROOT, "editor-engine/src/main/java/com/termex/replay15/editor/domain/Project.kt")

F = []  # (group, label, brightness, contrast, saturation, hue, lightness, r, g, b)
clamp = lambda v, lo, hi: max(lo, min(hi, v))


def add(group, label, b=0.0, c=0.0, s=0.0, h=0.0, l=0.0, r=1.0, g=1.0, bl=1.0):
    F.append((group, label, clamp(b, -.3, .3), clamp(c, -.5, .6), clamp(s, -100, 80), clamp(h, -40, 40), clamp(l, -20, 20),
              clamp(r, .6, 1.4), clamp(g, .6, 1.4), clamp(bl, .6, 1.4)))


def tint_gains(hue_deg, k):
    """Channel gains that push the image toward a hue while keeping overall exposure roughly neutral."""
    r, g, b = colorsys.hsv_to_rgb(hue_deg / 360.0, 1.0, 1.0)
    m = (r + g + b) / 3
    return 1 + k * (r - m) * 1.5, 1 + k * (g - m) * 1.5, 1 + k * (b - m) * 1.5


# ── Cores: 18 matizes x 4 intensidades ───────────────────────────────────────
HUES = [("Vermelho", 0), ("Coral", 12), ("Laranja", 28), ("Âmbar", 40), ("Amarelo", 55), ("Lima", 80), ("Verde", 120), ("Menta", 150),
        ("Turquesa", 175), ("Ciano", 190), ("Azul claro", 205), ("Azul", 225), ("Índigo", 250), ("Violeta", 270), ("Roxo", 285),
        ("Magenta", 310), ("Rosa", 335), ("Carmim", 350)]
LEVELS = [("suave", .25, 4), ("médio", .45, 10), ("forte", .7, 18), ("intenso", .95, 26)]
for name, hue in HUES:
    for lvl, k, sat in LEVELS:
        r, g, b = tint_gains(hue, k)
        add("Cores", f"{name} {lvl}", b=0, c=.04 * k, s=sat, l=0, r=r, g=g, bl=b)

# ── P&B: 24 ──────────────────────────────────────────────────────────────────
BW = [("Prata", 0, 0, 0), ("Grafite", -.04, .2, 0), ("Alto contraste", 0, .45, 0), ("Suave", .05, -.12, 0), ("Noir fosco", -.08, .35, 0),
      ("Papel", .1, -.2, 0), ("Newsprint", .06, .15, 0), ("Dramático", -.1, .5, 0)]
for name, b, c, _ in BW:
    add("P&B", f"P&B {name}", b=b, c=c, s=-100)
for name, hue in (("sépia", 38), ("azulado", 215), ("esverdeado", 130), ("rosado", 335), ("dourado", 45), ("violeta", 270), ("ciano", 190), ("vermelho", 5)):
    r, g, b = tint_gains(hue, .5)
    add("P&B", f"P&B {name}", c=.12, s=-100, r=r, g=g, bl=b)
for name, (b, c, hue) in {"Cinza frio": (0, .1, 215), "Cinza quente": (0, .1, 30), "Película": (.04, -.1, 40), "Tinta": (-.06, .4, 230),
                           "Neblina": (.12, -.25, 200), "Carvão": (-.12, .3, 20), "Giz": (.1, .25, 50), "Cinema mudo": (0, .2, 35)}.items():
    r, g, bl = tint_gains(hue, .2)
    add("P&B", f"P&B {name}", b=b, c=c, s=-100, r=r, g=g, bl=bl)

# ── Cinema: 40 (teal/orange, cold, warm, bleach, ...) ────────────────────────
for i, amt in enumerate((.3, .5, .7, .9)):
    for name, warm, cool in (("Teal & laranja", 28, 190), ("Âmbar & azul", 40, 220), ("Dourado & petróleo", 45, 180), ("Rosa & ciano", 340, 185), ("Magenta & verde", 310, 140)):
        r1, g1, b1 = tint_gains(warm, amt * .5)
        r2, g2, b2 = tint_gains(cool, amt * .5)
        add("Cinema", f"{name} {['leve', 'médio', 'forte', 'extremo'][i]}", c=.1 + .06 * i, s=8 + 6 * i, l=-3,
            r=(r1 + r2) / 2 + (r1 - r2) * .25, g=(g1 + g2) / 2 + (g1 - g2) * .25, bl=(b1 + b2) / 2 + (b1 - b2) * .25)
CIN = {"Blockbuster": (-.02, .3, 15, 1.08, 1.0, .92), "Thriller": (-.07, .35, -25, .94, 1.0, 1.06), "Épico": (-.03, .28, 12, 1.1, 1.0, .9), "Noite azul": (-.12, .25, -10, .85, .95, 1.18),
       "Sci-fi": (-.04, .3, -5, .92, 1.03, 1.12), "Guerra": (-.06, .38, -45, 1.0, .98, .92), "Western": (.03, .22, -8, 1.18, 1.0, .78), "Neo noir": (-.1, .45, -60, .95, .98, 1.08),
       "Drama": (-.05, .3, -15, 1.04, 1.0, .98), "Romance": (.07, -.05, 6, 1.1, .96, 1.02), "Terror": (-.12, .4, -35, .9, 1.05, .98), "Aventura": (.02, .26, 22, 1.08, 1.04, .88),
       "Fantasia": (.05, .12, 20, 1.02, .98, 1.12), "Documentário": (-.02, .2, -45, 1.0, 1.0, 1.0), "Cinema indie": (.04, .1, -10, 1.06, 1.02, .94), "Anamórfico": (-.02, .24, 10, .96, 1.0, 1.1),
       "Sunset film": (.04, .2, 18, 1.15, .98, .84), "Inverno nórdico": (.04, .16, -30, .9, 1.0, 1.14), "Verão italiano": (.06, .14, 24, 1.12, 1.04, .9), "Filme europeu": (.02, .18, -20, 1.0, 1.02, .98)}
for name, (b, c, s, r, g, bl) in CIN.items():
    add("Cinema", name, b=b, c=c, s=s, l=-b * 20, r=r, g=g, bl=bl)

# ── Vintage: 40 ──────────────────────────────────────────────────────────────
for era, (b, c, s, hue, k) in {"Anos 50": (.05, -.16, -35, 40, .35), "Anos 60": (.06, -.1, -15, 35, .4), "Anos 70": (.05, -.06, 10, 30, .55), "Anos 80": (.04, .05, 25, 320, .35),
                                "Anos 90": (.03, .1, 8, 200, .25), "Y2K": (.06, .18, 20, 195, .3)}.items():
    for lvl, m in (("leve", .6), ("médio", 1.0), ("forte", 1.5)):
        r, g, bl = tint_gains(hue, k * m)
        add("Vintage", f"{era} {lvl}", b=b * m, c=c * m, s=s * m, l=5 * m, r=r, g=g, bl=bl)
for name, (b, c, s, hue, k) in {"Polaroid": (.07, -.12, -12, 45, .35), "Kodachrome": (.02, .2, 22, 25, .4), "Fujifilm": (.02, .14, 12, 150, .25), "Agfa": (.04, .1, 5, 40, .4),
                                 "Cinestill": (-.02, .16, 6, 195, .35), "Ektar": (.02, .22, 30, 20, .2), "Portra": (.06, .02, -4, 30, .3), "Lomo": (-.04, .35, 28, 280, .3),
                                 "Foto lavada": (.1, -.3, -30, 40, .2), "Cartão postal": (.08, -.08, 15, 30, .45), "Super 8": (.04, .1, -8, 35, .5), "Daguerreótipo": (.03, .25, -90, 40, .5),
                                 "Slide": (-.02, .3, 30, 20, .2), "Fita cassete": (.05, -.1, -10, 320, .3), "Revista antiga": (.1, -.18, -22, 42, .4), "Álbum de família": (.08, -.14, -18, 38, .45),
                                 "Sol de verão 78": (.07, .02, 14, 35, .6), "Diner": (.05, .12, 10, 190, .4)}.items():
    r, g, bl = tint_gains(hue, k)
    add("Vintage", name, b=b, c=c, s=s, l=4, r=r, g=g, bl=bl)

# ── Retrato: 30 ──────────────────────────────────────────────────────────────
for name, (b, c, s, hue, k) in {"Pele suave": (.1, -.08, -8, 25, .15), "Pele dourada": (.06, .06, 6, 35, .3), "Pele rosada": (.07, -.02, 4, 345, .25), "Pele radiante": (.12, .02, 8, 30, .2),
                                 "Pele bronzeada": (.04, .12, 12, 28, .45), "Pele fresca": (.08, -.04, -2, 200, .12), "Porcelana": (.14, -.12, -14, 340, .1), "Contra-luz": (.1, .04, 5, 40, .3),
                                 "Natural": (.04, .02, 0, 30, .08), "Editorial": (-.02, .24, -25, 25, .15), "Estúdio": (.05, .1, -6, 40, .1), "Sorriso": (.1, .0, 14, 30, .2),
                                 "Olhos vivos": (.03, .14, 18, 200, .15), "Suave pastel": (.12, -.2, -10, 330, .2), "Boudoir": (.02, .08, -4, 350, .35), "Golden retrato": (.07, .08, 10, 40, .5),
                                 "Luz de janela": (.1, -.06, -8, 210, .1), "Close-up": (.04, .16, 6, 25, .12), "Casamento": (.12, -.1, -12, 40, .15), "Bebê": (.14, -.16, -10, 30, .1),
                                 "Moda clean": (.06, .18, -20, 215, .1), "Beauty": (.09, .08, 4, 340, .18), "Selfie": (.1, .0, 6, 30, .16), "Barba e cabelo": (-.02, .22, -10, 25, .15),
                                 "Maquiagem": (.05, .12, 20, 345, .25), "Lábios vivos": (.03, .14, 22, 350, .3), "Pele tom escuro": (.06, .1, 6, 28, .2), "Pele clara": (.1, -.02, -4, 30, .12),
                                 "Hora azul": (.02, .1, -6, 220, .4), "Retrato noite": (-.04, .2, -10, 220, .25)}.items():
    r, g, bl = tint_gains(hue, k)
    add("Retrato", name, b=b, c=c, s=s, l=b * 30, r=r, g=g, bl=bl)

# ── Natureza: 12 cenas x 3 ───────────────────────────────────────────────────
for name, (b, c, s, hue, k) in {"Floresta": (-.02, .16, 18, 125, .35), "Praia": (.07, .1, 22, 190, .3), "Montanha": (.0, .24, 8, 215, .25), "Deserto": (.05, .14, 6, 35, .5),
                                 "Neve": (.1, .06, -14, 210, .2), "Campo": (.04, .14, 24, 90, .3), "Outono": (.03, .14, 20, 28, .5), "Primavera": (.07, .06, 28, 110, .25),
                                 "Oceano profundo": (-.06, .2, 12, 205, .45), "Pôr do sol": (.04, .18, 26, 20, .55), "Nascer do sol": (.06, .1, 20, 35, .5), "Tempestade": (-.1, .3, -22, 220, .3)}.items():
    for lvl, m in (("suave", .6), ("médio", 1.0), ("forte", 1.5)):
        r, g, bl = tint_gains(hue, k * m)
        add("Natureza", f"{name} {lvl}", b=b * m, c=c * m, s=s * m, r=r, g=g, bl=bl)

# ── Neon: 30 ─────────────────────────────────────────────────────────────────
for name, hue in (("Ciano", 190), ("Magenta", 310), ("Verde ácido", 100), ("Laranja", 25), ("Azul elétrico", 230), ("Violeta", 275), ("Rosa choque", 330), ("Amarelo", 58), ("Turquesa", 170), ("Vermelho", 358)):
    for lvl, k, c in (("sutil", .3, .18), ("vivo", .55, .28), ("saturado", .85, .4)):
        r, g, bl = tint_gains(hue, k)
        add("Neon", f"Neon {name} {lvl}", b=-.04, c=c, s=45, l=-4, r=r, g=g, bl=bl)

# ── Criativos: 12 rotações de matiz x 3 ──────────────────────────────────────
for deg in (-40, -32, -24, -16, -8, 8, 16, 24, 32, 40):
    for lvl, s, c in (("natural", 8, .06), ("vívido", 30, .16), ("psicodélico", 60, .3)):
        add("Criativos", f"Matiz {'+' if deg > 0 else ''}{deg}° {lvl}", c=c, s=s, h=float(deg))
for name, (b, c, s, h, r, g, bl) in {"Inverso suave": (.1, -.3, -40, 40, 1.1, .9, .9), "Cross process": (-.02, .3, 30, -20, 1.14, .96, .8), "Infravermelho": (.1, .1, -50, -35, 1.3, .7, 1.1),
                                       "Alucinógeno": (.0, .3, 70, 30, 1.0, 1.1, 1.1), "Térmico": (.0, .4, 60, -30, 1.3, .8, .7), "Raio-X": (.05, .4, -100, 0, .8, 1.1, 1.3),
                                       "Holográfico": (.05, .2, 40, 25, .9, 1.1, 1.3), "Vaporwave": (.04, .18, 30, -20, 1.1, .9, 1.25), "Synthwave": (-.04, .3, 50, -25, 1.15, .8, 1.3),
                                       "Glitch frio": (.0, .3, 20, 20, .8, 1.1, 1.3)}.items():
    add("Criativos", name, b=b, c=c, s=s, h=float(h), r=r, g=g, bl=bl)


def const(label, group, taken):
    base = "LIB_" + group.upper().replace("&", "E").replace(" ", "_") + "_" + re.sub(r"[^A-Z0-9]+", "_", label.upper().translate(str.maketrans("ÁÀÃÂÉÊÍÓÔÕÚÇ", "AAAAEEIOOOUC")).replace("+", "MAIS").replace("-", "MENOS").replace("°", "")).strip("_")
    name, n = base, 2
    while name in taken:
        name, n = f"{base}_{n}", n + 1
    taken.add(name)
    return name


def main():
    taken, lines = set(), []
    for group, label, b, c, s, h, l, r, g, bl in F:
        # Display name carries the group so searches such as "Neon" or "Retrato" read naturally in the gallery.
        lines.append(f'    {const(label, group, taken)}("{label}", {b:.3f}f, {c:.3f}f, {s:.1f}f, {h:.1f}f, {l:.1f}f, {r:.3f}f, {g:.3f}f, {bl:.3f}f, "{group}"),')
    text = open(PROJECT, encoding="utf-8").read()
    start = text.index("    // BEGIN GENERATED FILTERS")
    start_end = text.index("\n", start) + 1
    end = text.index("    // END GENERATED FILTERS")
    text = text[:start_end] + "\n".join(lines) + "\n" + text[end:]
    open(PROJECT, "w", encoding="utf-8").write(text)
    groups = {}
    for f in F:
        groups[f[0]] = groups.get(f[0], 0) + 1
    print(len(F), "filters", groups)


main()
