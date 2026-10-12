"""Generates ~350 animation presets (keyframe JSON) under assets/editor/animations."""
import itertools, json, math, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import validate

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "editor-engine/src/main/assets/editor/animations")
items = []
ids = set()


def pt(time, **kw):
    d = {"time": round(time, 4)}
    d.update({k: (round(v, 4) if isinstance(v, float) else v) for k, v in kw.items()})
    return d


def add(pid, name, cat, points):
    pid = "lib_" + pid
    assert pid not in ids, pid
    ids.add(pid)
    doc = {"id": pid, "type": "ANIMATION", "version": 1, "engineVersion": 1, "name": name, "category": cat,
           "license": "Recly original presets", "premium": False, "points": points}
    validate.animation(doc, pid)
    items.append(doc)


clamp = lambda v, lo, hi: max(lo, min(hi, v))
EASES = {"Suave": "SMOOTH", "Freio": "EASE_OUT", "Mola": "SPRING", "Quique": "BOUNCE", "Elástico": "ELASTIC", "Antecipa": "BACK"}
DIRS = {"esquerda": (-1, 0), "direita": (1, 0), "cima": (0, -1), "baixo": (0, 1)}
DIST = {"curta": .12, "longa": .45}
STYLES = ["fade", "zoom", "giro"]


def slug(t):
    return "".join(c for c in t.lower().replace("á", "a").replace("é", "e").replace("ç", "c").replace("í", "i") if c.isalnum())


# ── Entradas e saídas direcionais ────────────────────────────────────────────
for (dname, (dx, dy)), (ename, ease), (dist_name, d), style in itertools.product(DIRS.items(), EASES.items(), DIST.items(), STYLES):
    if dist_name == "curta" and style != "fade":
        continue
    sign = 1 if dx + dy > 0 else -1
    a = dict(x=-dx * d, y=-dy * d, easing=ease)
    b = dict(x=dx * d, y=dy * d)
    if style == "fade":
        a["opacity"], b["opacity"] = 0.0, 0.0
    elif style == "zoom":
        a["zoom"], a["opacity"] = .6, 0.0
        b["zoom"], b["opacity"] = 1.4, 0.0
    else:
        a["rotation"], a["opacity"] = 16.0 * sign, 0.0
        b["rotation"], b["opacity"] = -16.0 * sign, 0.0
    tag = f"{slug(ename)}_{dname}_{dist_name}_{style}"
    nice = f"{ename} {dname} {dist_name} · {style}"
    add("in_" + tag, "Entrada " + nice, "IN", [pt(0, **a), pt(1)])
    add("out_" + tag, "Saída " + nice, "OUT", [pt(0, easing=ease), pt(1, **b)])

# ── Entradas especiais (ENTRANCE / EXIT) ─────────────────────────────────────
SPECIAL_IN = {
    "estouro": [pt(0, zoom=.1, opacity=0.0, easing="BACK"), pt(1)],
    "estouro_giro": [pt(0, zoom=.2, rotation=-120.0, opacity=0.0, easing="BACK"), pt(1)],
    "queda": [pt(0, y=-.6, opacity=0.0, easing="BOUNCE"), pt(1)],
    "subida": [pt(0, y=.6, opacity=0.0, easing="BOUNCE"), pt(1)],
    "gigante": [pt(0, zoom=3.0, opacity=0.0, easing="EASE_OUT"), pt(1)],
    "cambalhota": [pt(0, rotation=-360.0, zoom=.3, opacity=0.0, easing="EASE_OUT"), pt(1)],
    "balanco": [pt(0, rotation=25.0, opacity=0.0, easing="ELASTIC"), pt(1)],
    "diagonal": [pt(0, x=-.4, y=.4, opacity=0.0, easing="EASE_OUT"), pt(1)],
    "diagonal_inv": [pt(0, x=.4, y=-.4, opacity=0.0, easing="EASE_OUT"), pt(1)],
    "pulo": [pt(0, y=.3, zoom=.8, opacity=0.0, easing="EASE_OUT"), pt(.6, y=-.04, zoom=1.06, easing="SMOOTH"), pt(1)],
    "chicote": [pt(0, x=-.7, rotation=-30.0, opacity=0.0, easing="EASE_OUT"), pt(.7, x=.03, rotation=4.0), pt(1)],
    "foco": [pt(0, zoom=1.6, opacity=0.0, easing="EASE_OUT"), pt(.6, zoom=.97), pt(1)],
    "tremor": [pt(0, x=-.05, opacity=0.0, easing="LINEAR"), pt(.2, x=.05, opacity=.6), pt(.4, x=-.04, opacity=.8),
               pt(.6, x=.03), pt(.8, x=-.015), pt(1)],
    "elastico_h": [pt(0, zoom=.3, x=-.2, opacity=0.0, easing="ELASTIC"), pt(1)],
    "gota": [pt(0, y=-.5, zoom=.5, opacity=0.0, easing="BOUNCE"), pt(1)],
    "radar": [pt(0, zoom=2.2, rotation=45.0, opacity=0.0, easing="EASE_OUT"), pt(1)],
}
for name, pts in SPECIAL_IN.items():
    add("entrance_" + name, "Entrada " + name.replace("_", " "), "ENTRANCE", pts)
    # Saída espelhada: inverte o tempo e a pose.
    rev = []
    for p in reversed(pts):
        q = dict(p); q["time"] = round(1 - p["time"], 4); q["easing"] = "EASE_IN"
        rev.append(q)
    rev[-1].pop("easing", None)
    add("exit_" + name, "Saída " + name.replace("_", " "), "EXIT", rev)

# ── Loops (terminam onde começam) ────────────────────────────────────────────
for amp in (.02, .04, .07, .1, .15):
    for ename, ease in (("suave", "SMOOTH"), ("mola", "SPRING")):
        add(f"loop_pulso_{int(amp*100)}_{ename}", f"Pulso {int(amp*100)}% {ename}", "LOOP",
            [pt(0, easing=ease), pt(.5, zoom=1 + amp, easing=ease), pt(1)])
for amp in (2.0, 4.0, 7.0, 12.0, 20.0):
    add(f"loop_balanco_{int(amp)}", f"Balanço {int(amp)}°", "LOOP",
        [pt(0), pt(.25, rotation=-amp), pt(.75, rotation=amp), pt(1)])
    add(f"loop_pendulo_{int(amp)}", f"Pêndulo {int(amp)}°", "LOOP", [pt(0, rotation=-amp), pt(.5, rotation=amp), pt(1, rotation=-amp)])
for dname, (dx, dy) in DIRS.items():
    for amp in (.01, .025, .05, .09):
        add(f"loop_flutuar_{dname}_{int(amp*1000)}", f"Flutuar {dname} {amp*100:.1f}%", "LOOP",
            [pt(0), pt(.5, x=dx * amp, y=dy * amp), pt(1)])
for amp in (.005, .012, .025, .045):
    for n in (3, 5, 8):
        pts = [pt(0)]
        for k in range(1, n + 1):
            s = (-1) ** k
            pts.append(pt(k / (n + 1), x=s * amp * (1 - k / (n + 2)) * 1.5, y=-s * amp * .6))
        pts.append(pt(1))
        add(f"loop_tremor_{int(amp*1000)}_{n}", f"Tremor {amp*100:.1f}% x{n}", "LOOP", pts)
for turns in (10.0, 45.0, 90.0, 180.0, 360.0):
    add(f"loop_giro_{int(turns)}", f"Giro {int(turns)}°", "LOOP", [pt(0), pt(.5, rotation=turns / 2 if turns < 360 else 180.0), pt(1, rotation=0.0)]) if False else None
for amp in (.04, .08, .15):
    add(f"loop_orbita_{int(amp*100)}", f"Órbita {int(amp*100)}%", "LOOP",
        [pt(0)] + [pt(k / 8, x=amp * math.sin(2 * math.pi * k / 8), y=amp * (1 - math.cos(2 * math.pi * k / 8)) * .5) for k in range(1, 8)] + [pt(1)])
for amp in (.05, .1, .2):
    add(f"loop_batida_{int(amp*100)}", f"Batida {int(amp*100)}%", "LOOP",
        [pt(0), pt(.1, zoom=1 + amp, easing="EASE_OUT"), pt(.3), pt(.4, zoom=1 + amp * .6), pt(.6), pt(1)])
    add(f"loop_respira_{int(amp*100)}", f"Respirar {int(amp*100)}%", "LOOP", [pt(0), pt(.5, zoom=1 + amp / 2, y=-amp / 8), pt(1)])
for amp in (.2, .4, .6, .8):
    add(f"loop_piscar_{int(amp*100)}", f"Piscar {int(amp*100)}%", "LOOP", [pt(0), pt(.45, opacity=1 - amp), pt(.55, opacity=1 - amp), pt(1)])
add("loop_neon", "Neon instável", "LOOP", [pt(0), pt(.1, opacity=.4), pt(.15), pt(.5, opacity=.85), pt(.55), pt(.8, opacity=.5), pt(.85), pt(1)])
add("loop_gelatina", "Gelatina", "LOOP", [pt(0), pt(.2, zoom=1.12, easing="SPRING"), pt(.4, zoom=.94), pt(.6, zoom=1.04), pt(.8, zoom=.99), pt(1)])
add("loop_onda", "Onda", "LOOP", [pt(0), pt(.25, y=-.02, rotation=2.0), pt(.5), pt(.75, y=.02, rotation=-2.0), pt(1)])
add("loop_zoom_vai_volta", "Zoom vai e volta", "LOOP", [pt(0), pt(.5, zoom=1.2, easing="SMOOTH"), pt(1)])

# ── Destaque (EMPHASIS): efeitos pontuais que voltam à pose ──────────────────
for amp in (.08, .15, .25, .4):
    add(f"dest_soco_{int(amp*100)}", f"Soco de zoom {int(amp*100)}%", "EMPHASIS",
        [pt(0), pt(.15, zoom=1 + amp, easing="EASE_OUT"), pt(1, easing="SMOOTH")])
    add(f"dest_pop_{int(amp*100)}", f"Pop {int(amp*100)}%", "EMPHASIS",
        [pt(0), pt(.25, zoom=1 + amp, easing="EASE_OUT"), pt(.5, zoom=1 - amp / 3), pt(.75, zoom=1 + amp / 8), pt(1)])
    add(f"dest_giro_{int(amp*100)}", f"Giro de destaque {int(amp*100)}", "EMPHASIS",
        [pt(0), pt(.5, rotation=360.0 * amp, zoom=1 + amp / 2, easing="SMOOTH"), pt(1, rotation=0.0)])
for amp in (.03, .06, .1):
    add(f"dest_chacoalha_{int(amp*100)}", f"Chacoalhar {int(amp*100)}%", "EMPHASIS",
        [pt(0), pt(.1, x=-amp, rotation=-6.0), pt(.25, x=amp, rotation=6.0), pt(.4, x=-amp / 2, rotation=-3.0), pt(.55, x=amp / 3), pt(1)])
    add(f"dest_salto_{int(amp*100)}", f"Salto {int(amp*100)}%", "EMPHASIS",
        [pt(0), pt(.3, y=-amp * 2, easing="EASE_OUT"), pt(.6, y=0.0, easing="BOUNCE"), pt(.8, y=-amp / 2), pt(1)])
    add(f"dest_flash_{int(amp*100)}", f"Piscada {int(amp*100)}%", "EMPHASIS", [pt(0), pt(.2, opacity=1 - amp * 6), pt(.4), pt(.6, opacity=1 - amp * 4), pt(1)])
add("dest_tada", "Tada", "EMPHASIS", [pt(0), pt(.1, zoom=.9, rotation=-3.0), pt(.2, zoom=.9, rotation=-3.0), pt(.3, zoom=1.1, rotation=3.0),
                                       pt(.4, zoom=1.1, rotation=-3.0), pt(.5, zoom=1.1, rotation=3.0), pt(.6, zoom=1.1, rotation=-3.0), pt(1)])
add("dest_batida_dupla", "Batida dupla", "EMPHASIS", [pt(0), pt(.15, zoom=1.12, easing="EASE_OUT"), pt(.3), pt(.45, zoom=1.12, easing="EASE_OUT"), pt(.7), pt(1)])
add("dest_ioio", "Ioiô", "EMPHASIS", [pt(0), pt(.4, y=.1, zoom=.92, easing="EASE_OUT"), pt(.7, y=-.03, zoom=1.04), pt(1)])
add("dest_vira", "Virada 3D", "EMPHASIS", [pt(0), pt(.5, zoom=.1, rotation=180.0), pt(.51, zoom=.1, rotation=-180.0), pt(1, rotation=0.0)]) if False else None

# ── Famílias por tema (TEXT, IMAGE, STICKER, SOCIAL, GAMING, UI, CELEBRATION, MOTION) ──
for k, (ename, ease) in enumerate(EASES.items()):
    for dname, (dx, dy) in DIRS.items():
        add(f"mov_deslizar_{slug(ename)}_{dname}", f"Deslizar {dname} · {ename}", "MOTION",
            [pt(0), pt(.5, x=dx * .12, y=dy * .12, easing=ease), pt(1, x=dx * .12, y=dy * .12)]) if False else None
for dname, (dx, dy) in DIRS.items():
    for amp in (.1, .2, .35):
        add(f"mov_passada_{dname}_{int(amp*100)}", f"Passada {dname} {int(amp*100)}%", "MOTION",
            [pt(0, x=-dx * amp, y=-dy * amp, opacity=.0, easing="EASE_OUT"), pt(.3, opacity=1.0), pt(.7, opacity=1.0, easing="EASE_IN"),
             pt(1, x=dx * amp, y=dy * amp, opacity=0.0)])
        add(f"mov_deriva_{dname}_{int(amp*100)}", f"Deriva {dname} {int(amp*100)}%", "MOTION",
            [pt(0), pt(1, x=dx * amp * .4, y=dy * amp * .4, zoom=1 + amp * .15, easing="LINEAR")])
for z in (.1, .2, .3, .5):
    add(f"mov_kenburns_in_{int(z*100)}", f"Zoom lento aproximando {int(z*100)}%", "MOTION", [pt(0), pt(1, zoom=1 + z, easing="LINEAR")])
    add(f"mov_kenburns_out_{int(z*100)}", f"Zoom lento afastando {int(z*100)}%", "MOTION", [pt(0, zoom=1 + z), pt(1, zoom=1.0, easing="LINEAR")])
    add(f"mov_dolly_{int(z*100)}", f"Dolly {int(z*100)}%", "MOTION", [pt(0), pt(.5, zoom=1 + z, easing="SMOOTH"), pt(1, zoom=1 + z * 1.5, easing="SMOOTH")])
for ang in (3.0, 6.0, 10.0, 15.0):
    add(f"mov_inclina_{int(ang)}", f"Inclinar {int(ang)}°", "MOTION", [pt(0), pt(1, rotation=ang, zoom=1.06, easing="SMOOTH")])
    add(f"mov_inclina_inv_{int(ang)}", f"Inclinar inverso {int(ang)}°", "MOTION", [pt(0), pt(1, rotation=-ang, zoom=1.06, easing="SMOOTH")])
    add(f"mov_gira_zoom_{int(ang)}", f"Girar e aproximar {int(ang)}°", "MOTION", [pt(0, rotation=-ang), pt(1, rotation=ang, zoom=1.12, easing="SMOOTH")])

THEMES = {
    "TEXT": ("texto", "Texto"), "IMAGE": ("imagem", "Imagem"), "STICKER": ("sticker", "Sticker"), "SOCIAL": ("social", "Social"),
    "GAMING": ("gaming", "Game"), "UI": ("ui", "Interface"), "CELEBRATION": ("festa", "Festa"),
}
MOVES = {
    "surge": lambda a, e: [pt(0, zoom=1 - a, opacity=0.0, easing=e), pt(1)],
    "sobe": lambda a, e: [pt(0, y=a, opacity=0.0, easing=e), pt(1)],
    "desce": lambda a, e: [pt(0, y=-a, opacity=0.0, easing=e), pt(1)],
    "entra_esq": lambda a, e: [pt(0, x=-a, opacity=0.0, easing=e), pt(1)],
    "entra_dir": lambda a, e: [pt(0, x=a, opacity=0.0, easing=e), pt(1)],
    "gira_entra": lambda a, e: [pt(0, rotation=-a * 120, zoom=1 - a, opacity=0.0, easing=e), pt(1)],
    "estica": lambda a, e: [pt(0, zoom=1 + a * 2, opacity=0.0, easing=e), pt(1)],
}
for cat, (cslug, cname) in THEMES.items():
    for mname, fn in MOVES.items():
        for amp, aname, ename, ease in ((.25, "leve", "Freio", "EASE_OUT"), (.25, "leve", "Mola", "SPRING"), (.5, "forte", "Quique", "BOUNCE")):
            if True:
                add(f"{cslug}_{mname}_{aname}_{slug(ename)}", f"{cname}: {mname.replace('_', ' ')} {aname} · {ename}", cat, fn(amp, ease))


def main():
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f.startswith("lib_"):
            os.remove(os.path.join(OUT, f))
    for doc in items:
        with open(os.path.join(OUT, doc["id"] + ".json"), "w") as fh:
            json.dump(doc, fh, ensure_ascii=False, separators=(",", ":"))
    print(len(items), "animations")


main()
