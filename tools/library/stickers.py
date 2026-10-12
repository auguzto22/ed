"""Original animated sticker designs for the Recly library (all vector, Lottie shape layers)."""
import math
import random
import zlib

from lottie_kit import (C, EASE_OUT, EASE_IN_OUT, LINEAR, anim, apply_motion, composition, ellipse, fill, group,
                        layer, path, polar, rect, star, static, stroke, trim)

PALETTE = {
    "Roxo": "#9B87FF", "Rosa": "#FF5CA8", "Laranja": "#FF8A3D", "Amarelo": "#FFD23F",
    "Verde": "#3DDC97", "Ciano": "#34D7F0", "Azul": "#3D7BFF", "Vermelho": "#FF4D5E",
    "Branco": "#FFFFFF", "Grafite": "#22222A",
}
CONFETTI_SETS = {
    "Festa": ["#FF5CA8", "#FFD23F", "#34D7F0", "#9B87FF", "#3DDC97"],
    "Ouro": ["#FFD23F", "#FFB52E", "#FFF1B8", "#E8A317"],
    "Neon": ["#34D7F0", "#FF5CA8", "#B6FF3D", "#9B87FF"],
    "Pastel": ["#FFC4DD", "#C9F2E3", "#D7D0FF", "#FFF1B8"],
    "Brasil": ["#3DDC97", "#FFD23F", "#3D7BFF", "#FFFFFF"],
}

stickers = []  # (id, name, category, comp, tags)


def add(sid, name, category, comp, tags):
    stickers.append((sid, name, category, comp, tags))


def single(name, shapes, motion_kind, op=60):
    lay = layer(shapes, op=op)
    apply_motion(lay, motion_kind, op)
    return composition(name, [lay], op)


def slug(text):
    table = str.maketrans("áàãâéêíóôõúçÁÀÃÂÉÊÍÓÔÕÚÇ &", "aaaaeeiooouc" + "aaaaeeiooouc" + "__")
    return "".join(ch for ch in text.translate(table).lower() if ch.isalnum() or ch == "_")


# ── Shapes ───────────────────────────────────────────────────────────────────
HEART = dict(
    vertices=[[0, -40], [90, -20], [0, 100], [-90, -20]],
    in_t=[[-30, -60], [10, -70], [40, 40], [-40, 40]],
    out_t=[[30, -60], [-40, 40], [-40, 40], [-10, -70]],
)


def heart_path(scale=1.0):
    s = scale
    return path([[v[0] * s, v[1] * s] for v in [[0, -45], [95, -15], [0, 105], [-95, -15]]], True,
                [[ -38 * s, -62 * s], [0, -95 * s * 0 - 55 * s], [55 * s, 45 * s], [0, 55 * s]],
                [[38 * s, -62 * s], [0, 55 * s], [-55 * s, 45 * s], [0, -55 * s]])


SHAPES = [
    ("Círculo", lambda c: [ellipse(190), fill(c)]),
    ("Anel", lambda c: [ellipse(170), stroke(c, 26)]),
    ("Quadrado", lambda c: [rect(170, 170), fill(c)]),
    ("Arredondado", lambda c: [rect(180, 180, radius=46), fill(c)]),
    ("Triângulo", lambda c: [star(3, 110, 0, polygon=True, outer_round=10), fill(c)]),
    ("Losango", lambda c: [star(4, 115, 0, polygon=True), fill(c)]),
    ("Hexágono", lambda c: [star(6, 105, 0, polygon=True, rotation=30), fill(c)]),
    ("Estrela", lambda c: [star(5, 115, 50, rotation=0), fill(c)]),
    ("Estrela 8", lambda c: [star(8, 110, 62, outer_round=0), fill(c)]),
    ("Coração", lambda c: [heart_path(1.0), fill(c)]),
]
SHAPE_MOTIONS = ["pop", "spin", "pulse", "jelly", "wobble", "float"]
for i, (shape_name, builder) in enumerate(SHAPES):
    for j, color_name in enumerate(["Roxo", "Rosa", "Amarelo", "Ciano", "Verde", "Vermelho"]):
        motion_kind = SHAPE_MOTIONS[(i + j) % len(SHAPE_MOTIONS)]
        comp = single(f"{shape_name} {color_name}", [group(builder(PALETTE[color_name]))], motion_kind)
        add(f"lib_shape_{slug(shape_name)}_{slug(color_name)}", f"{shape_name} {color_name}", "SHAPES", comp,
            ["forma", "shape", slug(shape_name), color_name.lower(), motion_kind])

# ── Arrows ───────────────────────────────────────────────────────────────────
ARROW_TYPES = {
    "Seta": lambda c: [path([[-20, -90], [20, -90], [20, 10], [60, 10], [0, 95], [-60, 10], [-20, 10]]), fill(c)],
    "Chevron": lambda c: [path([[-70, -30], [0, 40], [70, -30]], closed=False), stroke(c, 34)],
    "Chevron duplo": lambda c: [path([[-70, -70], [0, 0], [70, -70]], closed=False), path([[-70, 0], [0, 70], [70, 0]], closed=False), stroke(c, 28)],
    "Seta curva": lambda c: [path([[-60, -80], [40, 40]], closed=False, in_t=[[0, 0], [-90, 0]], out_t=[[0, 90], [0, 0]]),
                             path([[10, 10], [45, 45], [10, 75]], closed=False), stroke(c, 18)],
    "Seta fina": lambda c: [path([[0, -95], [0, 85]], closed=False), path([[-40, 45], [0, 90], [40, 45]], closed=False), stroke(c, 14)],
}
DIRECTIONS = {"baixo": 0, "esquerda": 90, "cima": 180, "direita": 270}
for t_index, (type_name, builder) in enumerate(ARROW_TYPES.items()):
    for d_index, (direction, angle) in enumerate(DIRECTIONS.items()):
        for c_index, color_name in enumerate(["Branco", "Amarelo"]):
            # Bounce along the arrow direction, expressed in the rotated group.
            dx, dy = polar(26, angle + 90)
            nudge = anim([(0, [C, C, 0]), (15, [C + dx, C + dy, 0]), (30, [C, C, 0]), (45, [C + dx, C + dy, 0]), (60, [C, C, 0])])
            lay = layer([group(builder(PALETTE[color_name]), rotation=angle)], pos=nudge)
            if c_index == 1:
                lay["ks"]["o"] = anim([(0, 100), (25, 100), (30, 35), (35, 100), (60, 100)], LINEAR)
            name = f"{type_name} {direction} {color_name.lower()}"
            add(f"lib_arrow_{slug(type_name)}_{direction}_{slug(color_name)}", name, "ARROWS", composition(name, [lay]),
                ["seta", "arrow", direction, "apontar", "clique"])

# ── Faces ────────────────────────────────────────────────────────────────────
FACE_COLORS = {"Amarelo": "#FFD23F", "Rosa": "#FF8FC4", "Ciano": "#7DE6F5"}
DARK = "#2A1E12"


def eyes_normal():
    return [group([ellipse(22, 34, (-40, -25)), ellipse(22, 34, (40, -25)), fill(DARK)])]


def eyes_closed():
    return [group([path([[-58, -22], [-40, -32], [-22, -22]], False), path([[22, -22], [40, -32], [58, -22]], False), stroke(DARK, 9)])]


def eyes_wink():
    return [group([ellipse(22, 34, (-40, -25)), fill(DARK)]), group([path([[22, -24], [40, -30], [58, -24]], False), stroke(DARK, 9)])]


def eyes_hearts():
    return [group([heart_path(0.32), fill("#FF4D5E")], pos=(-40, -28)), group([heart_path(0.32), fill("#FF4D5E")], pos=(40, -28))]


def eyes_glasses():
    return [group([rect(70, 40, (-40, -25), 12), rect(70, 40, (40, -25), 12), rect(30, 8, (0, -32)), fill("#16161C")])]


def eyes_wide():
    return [group([ellipse(40, (-40, -28)), ellipse(40, (40, -28)), fill("#FFFFFF")]),
            group([ellipse(16, (-40, -26)), ellipse(16, (40, -26)), fill(DARK)])]


def eyes_stars():
    return [group([star(5, 22, 10, (-40, -28)), star(5, 22, 10, (40, -28)), fill("#FF8A3D")])]


def mouth_smile():
    return [group([path([[-45, 25], [45, 25]], False, [[0, 0], [-10, 45]], [[10, 45], [0, 0]]), stroke(DARK, 10)])]


def mouth_grin():
    return [group([path([[-55, 18], [55, 18], [0, 70]], True, [[0, 0], [0, 0], [45, 0]], [[0, 0], [0, 0], [-45, 0]]), fill(DARK)])]


def mouth_o():
    return [group([ellipse(36, 44, (0, 40)), fill(DARK)])]


def mouth_sad():
    return [group([path([[-40, 55], [40, 55]], False, [[0, 0], [10, -35]], [[-10, -35], [0, 0]]), stroke(DARK, 10)])]


def mouth_flat():
    return [group([path([[-35, 45], [35, 45]], False), stroke(DARK, 10)])]


def mouth_kiss():
    return [group([heart_path(0.22), fill("#FF4D5E")], pos=(15, 42))]


def extras_tears():
    return [group([ellipse(18, 30, (-72, 5)), ellipse(18, 30, (72, 5)), fill("#5CC8FF")])]


def extras_angry():
    return [group([path([[-65, -62], [-20, -45]], False), path([[65, -62], [20, -45]], False), stroke(DARK, 10)])]


def extras_blush():
    return [group([ellipse(30, 16, (-62, 18)), ellipse(30, 16, (62, 18)), fill("#FF7AA8", 70)])]


def extras_zzz():
    return [group([path([[70, -90], [100, -90], [70, -60], [100, -60]], False), stroke("#FFFFFF", 7)])]


FACES = [
    ("Sorriso", eyes_normal, mouth_smile, None, "bounce"),
    ("Risada", eyes_closed, mouth_grin, extras_tears, "shake"),
    ("Piscadinha", eyes_wink, mouth_smile, extras_blush, "wobble"),
    ("Apaixonado", eyes_hearts, mouth_smile, None, "heartbeat"),
    ("Estiloso", eyes_glasses, mouth_smile, None, "swing"),
    ("Surpreso", eyes_wide, mouth_o, None, "pop"),
    ("Triste", eyes_normal, mouth_sad, None, "float"),
    ("Bravo", eyes_normal, mouth_flat, extras_angry, "shake"),
    ("Sonolento", eyes_closed, mouth_flat, extras_zzz, "float"),
    ("Encantado", eyes_stars, mouth_grin, None, "jelly"),
    ("Beijo", eyes_closed, mouth_kiss, extras_blush, "pulse"),
    ("Feliz", eyes_closed, mouth_smile, extras_blush, "bounce"),
]
for face_name, eyes, mouth, extras, motion_kind in FACES:
    for color_name, color in FACE_COLORS.items():
        parts = [group([ellipse(230), fill(color)])] + eyes() + mouth() + (extras() if extras else [])
        name = f"{face_name} {color_name.lower()}"
        add(f"lib_face_{slug(face_name)}_{slug(color_name)}", name, "EMOJIS", single(name, parts, motion_kind),
            ["emoji", "rosto", slug(face_name), "reacao"])

# ── Callouts ─────────────────────────────────────────────────────────────────


def bubble_round(c):
    return [group([ellipse(260, 190), path([[-40, 70], [-90, 125], [10, 85]]), fill(c)])]


def bubble_rect(c):
    return [group([rect(280, 170, (0, -10), 40), path([[30, 70], [80, 125], [80, 70]]), fill(c)])]


def bubble_thought(c):
    return [group([ellipse(250, 170), ellipse(46, (-90, 105)), ellipse(26, (-125, 140)), fill(c)])]


def typing_dots(dark):
    dots = []
    for k, x in enumerate((-55, 0, 55)):
        bounce = anim([(k * 5, [0, 0]), (k * 5 + 8, [0, -18]), (k * 5 + 16, [0, 0]), (60, [0, 0])])
        dots.append(group([ellipse(30), fill(dark)], pos=bounce))
    return dots


def exclaim(dark):
    return [group([rect(26, 80, (0, -25), 13), ellipse(28, (0, 45)), fill(dark)])]


def question(dark):
    return [group([path([[-35, -45], [35, -40], [0, 15]], False, [[0, 0], [0, -45], [0, -25]], [[0, -45], [0, 30], [0, 0]]),
                   stroke(dark, 22)]), group([ellipse(26, (0, 52)), fill(dark)])]


def inner_heart(_dark):
    return [group([heart_path(0.45), fill("#FF4D5E")], pos=(0, -8))]


CALLOUTS = [("Balão", bubble_round), ("Balão quadrado", bubble_rect), ("Pensamento", bubble_thought)]
CONTENTS = [("digitando", typing_dots), ("exclamação", exclaim), ("pergunta", question), ("coração", inner_heart)]
for b_name, bubble in CALLOUTS:
    for content_name, content in CONTENTS:
        for color_name in ("Branco", "Amarelo"):
            dark = "#16161C"
            parts = bubble(PALETTE[color_name]) + content(dark)
            name = f"{b_name} {content_name} {color_name.lower()}"
            lay = layer(parts)
            apply_motion(lay, "pop")
            add(f"lib_callout_{slug(b_name)}_{slug(content_name)}_{slug(color_name)}", name, "CALLOUTS", composition(name, [lay]),
                ["balao", "fala", "comentario", slug(content_name)])

# ── Social ───────────────────────────────────────────────────────────────────


def bell(c):
    body = group([path([[-70, 40], [-55, -30], [55, -30], [70, 40]], True, [[0, 0], [0, 40], [-40, -70], [0, 0]], [[0, -40], [40, -70], [0, 40], [0, 0]]),
                  rect(170, 22, (0, 50), 11), ellipse(38, (0, 82)), fill(c)])
    return [body]


def chat(c):
    return [group([rect(240, 170, (0, -10), 46), path([[-60, 70], [-90, 120], [-20, 75]]), fill(c)])] + typing_dots("#FFFFFF")


def play_button(c):
    return [group([rect(250, 180, radius=52), fill(c)]), group([star(3, 50, 0, polygon=True, rotation=90), fill("#FFFFFF")])]


def share(c):
    return [group([path([[-55, 0], [55, -55]], False), path([[-55, 0], [55, 55]], False), stroke(c, 16)]),
            group([ellipse(56, (-55, 0)), ellipse(56, (55, -55)), ellipse(56, (55, 55)), fill(c)])]


def follow(c):
    return [group([ellipse(190), fill(c)]), group([rect(26, 100, radius=13), rect(100, 26, radius=13), fill("#FFFFFF")])]


def verified(c):
    return [group([star(12, 110, 92, outer_round=0), fill(c)]),
            group([path([[-40, 0], [-10, 32], [45, -30]], False), stroke("#FFFFFF", 22)])]


def bookmark(c):
    return [group([path([[-60, -100], [60, -100], [60, 100], [0, 55], [-60, 100]]), fill(c)])]


def comment(c):
    return [group([ellipse(220, 180), path([[60, 50], [100, 110], [20, 80]]), fill(c)]),
            group([rect(110, 16, (0, -22), 8), rect(80, 16, (-15, 18), 8), fill("#FFFFFF")])]


def counter(c):
    pulse = anim([(0, [100, 100]), (10, [125, 125]), (20, [100, 100]), (60, [100, 100])])
    return [group([heart_path(0.9), fill("#FFFFFF")]), group([ellipse(80), fill(c)], pos=(70, -70), scale=pulse)]


def cursor_click(c):
    ripple = anim([(0, [20, 20]), (30, [140, 140]), (31, [20, 20]), (60, [140, 140])], LINEAR)
    fade = anim([(0, 90), (30, 0), (31, 90), (60, 0)], LINEAR)
    return [group([ellipse(90), stroke(c, 8)], pos=(-10, -10), scale=ripple, opacity=fade),
            group([path([[-10, -10], [80, 30], [35, 45], [70, 95], [50, 105], [15, 55], [-10, 85]]), fill("#FFFFFF"), stroke("#16161C", 6)])]


SOCIAL = [("Sino", bell, "swing"), ("Chat", chat, "pop"), ("Play", play_button, "pulse"), ("Compartilhar", share, "pop"),
          ("Seguir", follow, "heartbeat"), ("Verificado", verified, "spin"), ("Salvar", bookmark, "bounce"),
          ("Comentário", comment, "wobble"), ("Curtidas", counter, "pop"), ("Clique", cursor_click, "float")]
for s_name, builder, motion_kind in SOCIAL:
    for color_name in ("Vermelho", "Azul", "Roxo"):
        name = f"{s_name} {color_name.lower()}"
        add(f"lib_social_{slug(s_name)}_{slug(color_name)}", name, "SOCIAL", single(name, builder(PALETTE[color_name]), motion_kind),
            ["social", "rede", slug(s_name), "engajamento"])

# ── Gaming ───────────────────────────────────────────────────────────────────


def crosshair(c):
    return [group([ellipse(170), stroke(c, 12)]), group([rect(12, 70, (0, -95)), rect(12, 70, (0, 95)), rect(70, 12, (-95, 0)),
                                                          rect(70, 12, (95, 0)), ellipse(18), fill(c)])]


def bolt(c):
    return [group([path([[20, -110], [-55, 15], [-5, 15], [-25, 110], [55, -20], [5, -20]]), fill(c)])]


def coin(c):
    flip = anim([(0, [100, 100]), (15, [8, 100]), (30, [100, 100]), (45, [8, 100]), (60, [100, 100])], LINEAR)
    return [group([ellipse(190), fill(c), ], scale=flip), group([ellipse(130), stroke("#FFF1B8", 10)], scale=flip)]


def gem(c):
    return [group([path([[-80, -30], [-40, -80], [40, -80], [80, -30], [0, 95]]), fill(c)]),
            group([path([[-80, -30], [80, -30]], False), path([[-40, -80], [-20, -30], [0, 95]], False),
                   path([[40, -80], [20, -30], [0, 95]], False), stroke("#FFFFFF", 4, 70)])]


def trophy(c):
    return [group([path([[-70, -90], [70, -90], [50, 10], [-50, 10]], True, [[0, 0], [0, 0], [0, 0], [0, 0]], [[0, 0], [0, 0], [-20, 30], [0, 0]]),
                   rect(26, 50, (0, 35)), rect(110, 24, (0, 70), 6), fill(c)]),
            group([path([[-70, -70], [-105, -60], [-75, 5]], False), path([[70, -70], [105, -60], [75, 5]], False), stroke(c, 12)])]


def hearts_row(c):
    out = []
    for k, x in enumerate((-95, 0, 95)):
        beat = anim([(k * 6, [100, 100]), (k * 6 + 6, [125, 125]), (k * 6 + 12, [100, 100]), (60, [100, 100])])
        out.append(group([heart_path(0.42), fill(c)], pos=(x, 0), scale=beat))
    return out


def power_ring(c):
    return [group([ellipse(200), stroke(c, 18), trim(end=anim([(0, 0), (40, 100), (60, 100)], EASE_OUT))]),
            group([rect(18, 80, (0, -20), 9), fill(c)])]


def xp_bar(c):
    grow = anim([(0, [0, 100]), (40, [100, 100]), (60, [100, 100])], EASE_OUT)
    return [group([rect(300, 50, radius=25), stroke("#FFFFFF", 6)]),
            group([rect(284, 34, (142, 0), 17), fill(c)], pos=(-142, 0), scale=grow)]


def shield(c):
    return [group([path([[0, -105], [90, -70], [80, 30], [0, 105], [-80, 30], [-90, -70]]), fill(c)]),
            group([star(5, 45, 20), fill("#FFFFFF")])]


def target(c):
    return [group([ellipse(220), fill("#FFFFFF")]), group([ellipse(160), fill(c)]), group([ellipse(100), fill("#FFFFFF")]),
            group([ellipse(44), fill(c)])]


def boom(c):
    return [group([star(12, 130, 70), fill(c)]), group([star(12, 80, 45, rotation=15), fill("#FFF1B8")])]


GAMING = [("Mira", crosshair, "pulse"), ("Raio", bolt, "blink"), ("Moeda", coin, "float"), ("Gema", gem, "float"),
          ("Troféu", trophy, "pop"), ("Vidas", hearts_row, "float"), ("Power", power_ring, "pop"), ("XP", xp_bar, "zoom"),
          ("Escudo", shield, "swing"), ("Alvo", target, "pop"), ("Boom", boom, "jelly")]
GAMING_COLORS = {"Vermelho": "#FF4D5E", "Amarelo": "#FFD23F", "Ciano": "#34D7F0"}
for g_name, builder, motion_kind in GAMING:
    for color_name, color in GAMING_COLORS.items():
        name = f"{g_name} {color_name.lower()}"
        add(f"lib_game_{slug(g_name)}_{slug(color_name)}", name, "GAMING", single(name, builder(color), motion_kind),
            ["game", "gaming", slug(g_name), "jogo"])

# ── Celebration ──────────────────────────────────────────────────────────────


def confetti(colors, seed):
    rng = random.Random(seed)
    pieces = []
    for k in range(26):
        x = rng.uniform(-170, 170)
        delay = rng.randint(0, 20)
        start = [x, -230]
        end = [x + rng.uniform(-60, 60), 230]
        fall = anim([(delay, start), (delay + 38, end), (60, end)], LINEAR)
        spin = anim([(0, rng.uniform(0, 90)), (60, rng.uniform(270, 720))], LINEAR)
        shape = rect(rng.uniform(10, 18), rng.uniform(18, 30), radius=3) if k % 3 else ellipse(rng.uniform(12, 18))
        pieces.append(group([shape, fill(rng.choice(colors))], pos=fall, rotation=spin))
    return composition("Confete", [layer(pieces)])


def fireworks(colors, seed):
    rng = random.Random(seed)
    layers = []
    for burst in range(3):
        cx, cy = rng.uniform(-90, 90), rng.uniform(-90, 60)
        delay = burst * 12
        color = colors[burst % len(colors)]
        rays = []
        for a in range(0, 360, 30):
            p = polar(70, a)
            rays.append(path([[0, 0], p], False))
        grow = anim([(delay, [10, 10]), (delay + 20, [140, 140]), (60, [140, 140])], EASE_OUT)
        fade = anim([(delay, 0), (delay + 2, 100), (delay + 26, 0), (60, 0)], LINEAR)
        layers.append(layer([group(rays + [stroke(color, 8), trim(start=anim([(delay, 0), (delay + 26, 100), (60, 100)]))],
                                   pos=(cx, cy), scale=grow, opacity=fade)]))
    return composition("Fogos", layers)


def balloons(colors, seed):
    rng = random.Random(seed)
    items = []
    for k, x in enumerate((-90, 0, 90)):
        rise = anim([(0, [x, 120]), (60, [x + rng.uniform(-20, 20), -60])])
        items.append(group([ellipse(95, 120), path([[0, 60], [-8, 75], [8, 75]]), fill(colors[k % len(colors)])], pos=rise))
        items.append(group([path([[0, 75], [10, 130], [-5, 180]], False), stroke("#FFFFFF", 3, 70)], pos=rise))
    return composition("Balões", [layer(items)])


def sparkle_burst(colors, seed):
    rng = random.Random(seed)
    items = []
    for k in range(8):
        pos = polar(rng.uniform(50, 150), rng.uniform(0, 360))
        delay = rng.randint(0, 30)
        tw = anim([(delay, [0, 0]), (delay + 8, [110, 110]), (delay + 18, [0, 0]), (60, [0, 0])])
        items.append(group([star(4, 34, 8), fill(colors[k % len(colors)])], pos=pos, scale=tw))
    return composition("Brilho festa", [layer(items)])


def popper(colors, seed):
    items = []
    for k, angle in enumerate(range(-150, -20, 18)):
        end = polar(170, angle)
        items.append(group([path([[0, 0], end], False), stroke(colors[k % len(colors)], 10),
                            trim(start=anim([(4, 0), (30, 100), (60, 100)]), end=anim([(0, 0), (20, 100), (60, 100)]))]))
    items.append(group([star(3, 60, 0, polygon=True), fill("#FFD23F")], pos=(0, 60)))
    return composition("Lança confete", [layer(items, pos=static([C, C + 60, 0]))])


def streamers(colors, seed):
    items = []
    for k in range(4):
        y = -120 + k * 80
        wave = anim([(0, [0, y]), (30, [0, y + 18]), (60, [0, y])])
        items.append(group([path([[-180, 0], [-60, 0], [60, 0], [180, 0]], False, [[0, 0], [-40, -40], [-40, 40], [-40, -40]],
                                 [[40, -40], [40, 40], [40, -40], [0, 0]]), stroke(colors[k % len(colors)], 14)], pos=wave))
    return composition("Serpentina", [layer(items)])


CELEBRATIONS = [("Confete", confetti), ("Fogos", fireworks), ("Balões", balloons), ("Brilho festa", sparkle_burst),
                ("Lança confete", popper), ("Serpentina", streamers)]
for c_name, builder in CELEBRATIONS:
    for k, (set_name, colors) in enumerate(CONFETTI_SETS.items()):
        name = f"{c_name} {set_name.lower()}"
        add(f"lib_party_{slug(c_name)}_{slug(set_name)}", name, "CELEBRATION", builder(colors, zlib.crc32(name.encode()) & 0xFFFF),
            ["festa", "celebracao", "parabens", slug(c_name)])

# ── Badges ───────────────────────────────────────────────────────────────────


def seal(c):
    return [group([star(16, 120, 100), fill(c)]), group([ellipse(150), stroke("#FFFFFF", 6)]), group([star(5, 40, 18), fill("#FFFFFF")])]


def rosette(c):
    return [group([path([[-40, 40], [-70, 140], [-30, 115], [-5, 150], [10, 50]]), path([[40, 40], [70, 140], [30, 115], [5, 150], [-10, 50]]), fill(c)]),
            group([star(20, 100, 88), fill(c)]), group([ellipse(130), fill("#FFFFFF", 30)])]


def shield_badge(c):
    return [group([path([[0, -110], [95, -75], [85, 35], [0, 110], [-85, 35], [-95, -75]]), stroke(c, 14)]),
            group([path([[-40, 0], [-10, 30], [45, -30]], False), stroke(c, 18)])]


def hex_badge(c):
    return [group([star(6, 115, 0, polygon=True, rotation=30), fill(c)]), group([star(6, 85, 0, polygon=True, rotation=30), stroke("#FFFFFF", 6)])]


def ribbon(c):
    return [group([rect(300, 70, radius=10), path([[-150, -35], [-190, 0], [-150, 35]]), path([[150, -35], [190, 0], [150, 35]]), fill(c)])]


def check_badge(c):
    return [group([ellipse(200), fill(c)]), group([path([[-45, 0], [-12, 35], [50, -35]], False), stroke("#FFFFFF", 22),
                                                 trim(end=anim([(8, 0), (30, 100), (60, 100)], EASE_OUT))])]


BADGES = [("Selo", seal, "spin"), ("Roseta", rosette, "swing"), ("Escudo selo", shield_badge, "pop"), ("Hexágono selo", hex_badge, "pulse"),
          ("Faixa", ribbon, "zoom"), ("Check", check_badge, "pop")]
for b_name, builder, motion_kind in BADGES:
    for color_name in ("Amarelo", "Roxo", "Verde", "Azul", "Vermelho"):
        name = f"{b_name} {color_name.lower()}"
        add(f"lib_badge_{slug(b_name)}_{slug(color_name)}", name, "BADGES", single(name, builder(PALETTE[color_name]), motion_kind),
            ["selo", "badge", "premio", slug(b_name)])

# ── Sparkles ─────────────────────────────────────────────────────────────────


def twinkle_cluster(c):
    out = []
    for k, (pos, size) in enumerate([((0, 0), 90), ((-90, -70), 40), ((85, -60), 50), ((70, 80), 34), ((-75, 75), 28)]):
        tw = anim([(k * 6, [100, 100]), (k * 6 + 12, [20, 20]), (k * 6 + 24, [100, 100]), (60, [100, 100])])
        out.append(group([star(4, size, size * .22), fill(c)], pos=pos, scale=tw))
    return out


def glint(c):
    return [group([star(4, 110, 18), fill(c)], rotation=anim([(0, 0), (60, 90)], LINEAR)),
            group([star(4, 60, 10, rotation=45), fill(c, 60)])]


def glitter_rain(c):
    rng = random.Random(7)
    out = []
    for k in range(18):
        x = rng.uniform(-170, 170)
        d = rng.randint(0, 25)
        out.append(group([star(4, rng.uniform(10, 22), 4), fill(c)], pos=anim([(d, [x, -200]), (d + 34, [x, 200]), (60, [x, 200])], LINEAR)))
    return out


def halo(c):
    grow = anim([(0, [40, 40]), (40, [130, 130]), (60, [130, 130])], EASE_OUT)
    fade = anim([(0, 100), (40, 0), (60, 0)])
    return [group([ellipse(200), stroke(c, 10)], scale=grow, opacity=fade), group([star(4, 60, 14), fill(c)])]


def shine(c):
    sweep = anim([(0, [-200, 0]), (30, [200, 0]), (60, [200, 0])], EASE_IN_OUT)
    return [group([rect(40, 420), fill(c, 70)], pos=sweep, rotation=20), group([rect(14, 420), fill(c)], pos=sweep, rotation=20)]


SPARKLES = [("Cintilar", twinkle_cluster), ("Reflexo", glint), ("Chuva de brilho", glitter_rain), ("Halo", halo), ("Faixa de luz", shine)]
for s_name, builder in SPARKLES:
    for color_name in ("Branco", "Amarelo", "Ciano", "Rosa"):
        name = f"{s_name} {color_name.lower()}"
        add(f"lib_sparkle_{slug(s_name)}_{slug(color_name)}", name, "SPARKLES", composition(name, [layer(builder(PALETTE[color_name]))]),
            ["brilho", "sparkle", "glitter", slug(s_name)])

# ── Frames ───────────────────────────────────────────────────────────────────


def corners(c, size=150, length=60, width=12):
    s = size
    parts = []
    for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
        parts.append(path([[sx * s, sy * (s - length)], [sx * s, sy * s], [sx * (s - length), sy * s]], False))
    return parts + [stroke(c, width)]


def frame_brackets(c):
    tighten = anim([(0, [125, 125]), (14, [100, 100]), (60, [100, 100])], EASE_OUT)
    return [group(corners(c), scale=tighten)]


def frame_rec(c):
    blink = anim([(0, 100), (15, 100), (16, 0), (30, 0), (31, 100), (45, 100), (46, 0), (60, 0)], LINEAR)
    return [group(corners("#FFFFFF", 160, 50, 8)), group([ellipse(30), fill("#FF4D5E")], pos=(-120, -125), opacity=blink)]


def frame_focus(c):
    breathe = anim([(0, [100, 100]), (30, [92, 92]), (60, [100, 100])])
    return [group([rect(260, 260, radius=24), stroke(c, 6)], scale=breathe), group([ellipse(14), fill(c)])]


def frame_circle(c):
    return [group([ellipse(280), stroke(c, 8), trim(end=anim([(0, 0), (30, 100), (60, 100)], EASE_OUT))],
                  rotation=anim([(0, -90), (60, 270)], LINEAR))]


def frame_dashed(c):
    out = []
    for k in range(12):
        a = k * 30
        out.append(group([rect(36, 10, radius=5), fill(c)], pos=polar(140, a), rotation=a + 90))
    return [group(out, rotation=anim([(0, 0), (60, 30)], LINEAR))]


FRAMES = [("Cantoneiras", frame_brackets), ("Gravando", frame_rec), ("Foco", frame_focus), ("Círculo foco", frame_circle), ("Tracejado", frame_dashed)]
for f_name, builder in FRAMES:
    for color_name in ("Branco", "Amarelo", "Ciano"):
        name = f"{f_name} {color_name.lower()}"
        add(f"lib_frame_{slug(f_name)}_{slug(color_name)}", name, "FRAMES", composition(name, [layer(builder(PALETTE[color_name]))]),
            ["moldura", "frame", "foco", slug(f_name)])

# ── Nature & weather ─────────────────────────────────────────────────────────


def sun(c):
    rays = [path([polar(110, a), polar(150, a)], False) for a in range(0, 360, 30)]
    return [group(rays + [stroke(c, 14)], rotation=anim([(0, 0), (60, 30)], LINEAR)), group([ellipse(150), fill(c)])]


def cloud(c):
    drift = anim([(0, [-30, 0]), (60, [30, 0])])
    return [group([ellipse(130, (-50, 10)), ellipse(160, (30, -20)), ellipse(110, (90, 20)), rect(220, 70, (20, 40), 35), fill(c)], pos=drift)]


def rain(c):
    rng = random.Random(3)
    drops = []
    for k in range(14):
        x = rng.uniform(-140, 140)
        d = rng.randint(0, 25)
        drops.append(group([rect(8, 34, radius=4), fill(c)], pos=anim([(d, [x, -40]), (d + 20, [x - 20, 190]), (60, [x - 20, 190])], LINEAR)))
    return [group([ellipse(120, (-50, -110)), ellipse(150, (30, -130)), ellipse(100, (90, -100)), fill("#FFFFFF")])] + drops


def snowflake(c):
    arms = [path([polar(0, a), polar(120, a)], False) for a in range(0, 360, 60)]
    ticks = [path([polar(70, a - 25) , polar(85, a), polar(70, a + 25)], False) for a in range(0, 360, 60)]
    return [group(arms + ticks + [stroke(c, 12)], rotation=anim([(0, 0), (60, 60)], LINEAR))]


def lightning_cloud(c):
    flash = anim([(0, 0), (20, 0), (22, 100), (26, 0), (30, 100), (40, 0), (60, 0)], LINEAR)
    return [group([ellipse(130, (-50, -60)), ellipse(160, (30, -80)), ellipse(110, (90, -50)), fill("#C8C8D8")]),
            group([path([[10, -30], [-40, 60], [0, 60], [-20, 140], [45, 30], [5, 30]]), fill(c)], opacity=flash)]


def moon(c):
    out = [group([ellipse(170, (0, 0)), fill(c)]), group([ellipse(150, (55, -35)), fill("#16161C")])]
    for k, pos in enumerate([(-120, -100), (110, 90), (-90, 120)]):
        tw = anim([(k * 8, [100, 100]), (k * 8 + 12, [30, 30]), (k * 8 + 24, [100, 100]), (60, [100, 100])])
        out.append(group([star(4, 22, 6), fill("#FFFFFF")], pos=pos, scale=tw))
    return out


def leaf(c):
    fall = anim([(0, [-60, -180]), (30, [60, 0]), (60, [-40, 180])])
    return [group([path([[0, -70], [0, 70]], True, [[0, 0], [60, 0]], [[60, 0], [0, 0]]), path([[0, -70], [0, 70]], True, [[0, 0], [-60, 0]], [[-60, 0], [0, 0]]),
                   fill(c)], pos=fall, rotation=anim([(0, -30), (30, 30), (60, -30)]))]


def flower(c):
    petals = [group([ellipse(70, 110, (0, -60)), fill(c)], rotation=a) for a in range(0, 360, 60)]
    return [group(petals + [group([ellipse(70), fill("#FFD23F")])], rotation=anim([(0, 0), (60, 60)], LINEAR))]


def rainbow(c):
    colors = ["#FF4D5E", "#FF8A3D", "#FFD23F", "#3DDC97", "#3D7BFF", "#9B87FF"]
    arcs = []
    for k, color in enumerate(colors):
        r = 170 - k * 18
        arcs.append(group([path([[-r, 60], [r, 60]], False, [[0, 0], [0, -r * 1.33]], [[0, -r * 1.33], [0, 0]]), stroke(color, 16),
                           trim(end=anim([(k * 3, 0), (k * 3 + 24, 100), (60, 100)], EASE_OUT))]))
    return arcs


def wave(c):
    out = []
    for k in range(3):
        y = -30 + k * 45
        sway = anim([(0, [-20, y]), (30, [20, y]), (60, [-20, y])])
        out.append(group([path([[-180, 0], [-60, 0], [60, 0], [180, 0]], False, [[0, 0], [-40, -35], [-40, 35], [-40, -35]],
                               [[40, -35], [40, 35], [40, -35], [0, 0]]), stroke(c, 14, 100 - k * 25)], pos=sway))
    return out


NATURE = [("Sol", sun, "Amarelo"), ("Nuvem", cloud, "Branco"), ("Chuva", rain, "Ciano"), ("Floco de neve", snowflake, "Branco"),
          ("Tempestade", lightning_cloud, "Amarelo"), ("Lua", moon, "Amarelo"), ("Folha", leaf, "Verde"), ("Flor", flower, "Rosa"),
          ("Arco-íris", rainbow, "Branco"), ("Onda", wave, "Ciano")]
ALT = {"Amarelo": "Laranja", "Branco": "Ciano", "Ciano": "Azul", "Verde": "Laranja", "Rosa": "Roxo"}
for n_name, builder, base in NATURE:
    for color_name in (base, ALT[base]):
        name = f"{n_name} {color_name.lower()}"
        add(f"lib_nature_{slug(n_name)}_{slug(color_name)}", name, "NATURE", composition(name, [layer(builder(PALETTE[color_name]))]),
            ["natureza", "clima", "tempo", slug(n_name)])

# ── Doodles (drawn on with trim paths) ───────────────────────────────────────
DRAW = lambda delay=0, length=26: trim(end=anim([(delay, 0), (delay + length, 100), (60, 100)], EASE_OUT))
DOODLES = [
    ("Sublinhado", lambda c: [path([[-160, 0], [-60, 10], [60, -8], [160, 6]], False, [[0, 0], [-40, 0], [-40, 0], [-40, 0]], [[40, 0], [40, 0], [40, 0], [0, 0]]), stroke(c, 14), DRAW()]),
    ("Círculo desenhado", lambda c: [ellipse(300, 200), stroke(c, 10), DRAW(0, 30)]),
    ("Check desenhado", lambda c: [path([[-80, 0], [-20, 60], [90, -70]], False), stroke(c, 24), DRAW()]),
    ("X desenhado", lambda c: [path([[-70, -70], [70, 70]], False), path([[70, -70], [-70, 70]], False), stroke(c, 22), DRAW()]),
    ("Zigue-zague", lambda c: [path([[-160, 20], [-110, -20], [-55, 20], [0, -20], [55, 20], [110, -20], [160, 20]], False), stroke(c, 12), DRAW(0, 30)]),
    ("Espiral", lambda c: [path([[0, 0], [40, 0], [0, 60], [-80, 0], [0, -110], [130, 0]], False,
                                [[0, 0], [0, -25], [35, 0], [0, 45], [-60, 0], [0, -80]], [[0, -25], [0, 35], [-50, 0], [0, -65], [80, 0], [0, 0]]), stroke(c, 10), DRAW(0, 34)]),
    ("Swoosh", lambda c: [path([[-160, 60], [160, -60]], False, [[0, 0], [-120, 80]], [[80, 40], [0, 0]]), stroke(c, 16), DRAW()]),
    ("Destaque", lambda c: [path([[0, -110], [0, -50]], False), path([[-80, -80], [-45, -35]], False), path([[80, -80], [45, -35]], False), stroke(c, 14), DRAW(0, 14)]),
    ("Seta rabisco", lambda c: [path([[-150, 60], [100, -40]], False, [[0, 0], [-100, 60]], [[60, -90], [0, 0]]),
                                path([[60, -70], [105, -40], [70, 5]], False), stroke(c, 12), DRAW(0, 30)]),
]
for d_name, builder in DOODLES:
    for color_name in ("Branco", "Amarelo", "Vermelho"):
        name = f"{d_name} {color_name.lower()}"
        add(f"lib_doodle_{slug(d_name)}_{slug(color_name)}", name, "DOODLES", composition(name, [layer([group(builder(PALETTE[color_name]))])]),
            ["rabisco", "doodle", "desenho", slug(d_name)])
