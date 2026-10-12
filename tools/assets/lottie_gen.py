"""Gera elementos Lottie (bodymovin 5.5.7, camadas de forma) no mesmo formato dos stickers que já
funcionam no Recly. Só usa recursos suportados pelo lottie-android: gr, el, rc, sr, sh, fl, st, tm, tr."""
import json, math, os
OUT = '/home/claude/ed/editor-engine/src/main/assets/editor/lottie'
FR, OP, W = 30, 60, 400
EASE = {'i': {'x': [0.4], 'y': [1]}, 'o': {'x': [0.6], 'y': [0]}}
LIN = {'i': {'x': [1], 'y': [1]}, 'o': {'x': [0], 'y': [0]}}

def static(v): return {'a': 0, 'k': v}
def anim(frames, ease=EASE):
    """frames = [(t, valor), ...] → keyframes legados (s/e) com curvas i/o."""
    ks = []
    for (t, v), nxt in zip(frames, frames[1:] + [None]):
        v = v if isinstance(v, list) else [v]
        k = {'t': t, 's': v}
        if nxt is not None:
            nv = nxt[1] if isinstance(nxt[1], list) else [nxt[1]]
            k['e'] = nv; k.update(ease)
        ks.append(k)
    return {'a': 1, 'k': ks}

def color(hexs, a=1):
    h = hexs.lstrip('#'); return [round(int(h[i:i + 2], 16) / 255, 4) for i in (0, 2, 4)] + [a]

def tr(p=(0, 0), s=100, r=0, o=100):
    return {'ty': 'tr', 'p': static(list(p)), 'a': static([0, 0]), 's': static([s, s]) if not isinstance(s, dict) else s,
            'r': static(r) if not isinstance(r, dict) else r, 'o': static(o) if not isinstance(o, dict) else o, 'nm': 'Transform'}
def fill(c, o=100): return {'ty': 'fl', 'c': static(color(c)), 'o': static(o) if not isinstance(o, dict) else o, 'r': 1, 'nm': 'Fill'}
def stroke(c, w, o=100): return {'ty': 'st', 'c': static(color(c)), 'o': static(o) if not isinstance(o, dict) else o, 'w': static(w), 'lc': 2, 'lj': 2, 'ml': 4, 'nm': 'Stroke'}
def ell(w, h=None, p=(0, 0)): return {'ty': 'el', 'd': 1, 's': static([w, h or w]) if not isinstance(w, dict) else w, 'p': static(list(p)), 'nm': 'Ellipse'}
def rect(w, h, p=(0, 0), r=0): return {'ty': 'rc', 'd': 1, 's': static([w, h]), 'p': static(list(p)), 'r': static(r), 'nm': 'Rect'}
def star(points, outer, inner=None, p=(0, 0), rot=0):
    d = {'ty': 'sr', 'sy': 1 if inner else 2, 'd': 1, 'pt': static(points), 'p': static(list(p)), 'r': static(rot),
         'or': static(outer), 'os': static(0), 'nm': 'Star'}
    if inner: d.update({'ir': static(inner), 'is': static(0)})
    return d
def path(pts, closed=False, ins=None, outs=None):
    n = len(pts)
    return {'ty': 'sh', 'd': 1, 'ks': static({'c': closed, 'v': [list(p) for p in pts],
            'i': ins or [[0, 0]] * n, 'o': outs or [[0, 0]] * n}), 'nm': 'Path'}
def trim(end_frames, start=None):
    return {'ty': 'tm', 's': start or static(0), 'e': anim(end_frames), 'o': static(0), 'm': 1, 'nm': 'Trim'}
def group(*items, t=None): return {'ty': 'gr', 'it': list(items) + [t or tr()], 'nm': 'Group'}

def layer(name, shapes, p=(200, 200), s=None, r=None, o=None, ind=1, ip=0, op=OP):
    return {'ddd': 0, 'ind': ind, 'ty': 4, 'nm': name, 'sr': 1,
            'ks': {'o': o or static(100), 'r': r or static(0), 'p': p if isinstance(p, dict) else static([p[0], p[1], 0]),
                   'a': static([0, 0, 0]), 's': s or static([100, 100, 100])},
            'shapes': shapes, 'ip': ip, 'op': op, 'st': 0}

def doc(name, layers):
    # Escrito de trás para frente (fundo primeiro); no Lottie a primeira camada fica no topo.
    layers = list(reversed(layers))
    for i, l in enumerate(layers): l['ind'] = i + 1
    return {'v': '5.5.7', 'fr': FR, 'ip': 0, 'op': OP, 'w': W, 'h': W, 'nm': name, 'ddd': 0, 'assets': [], 'layers': layers}

def pop_scale(peak=118, settle=100, t0=0):
    return anim([(t0, [0, 0, 100]), (t0 + 12, [peak, peak, 100]), (t0 + 20, [settle - 6, settle - 6, 100]), (t0 + 26, [settle, settle, 100])])
def pulse_scale(a=100, b=112):
    return anim([(0, [a, a, 100]), (15, [b, b, 100]), (30, [a, a, 100]), (45, [b, b, 100]), (60, [a, a, 100])])

E = {}
CORAL, YEL, BLUE, INK, WHITE, GREEN, PINK, PURPLE = '#FF5A36', '#FFD23F', '#7F92B8', '#141414', '#FFFFFF', '#3DDC84', '#FF4F9A', '#8E6CFF'

# ── Formas ──
E['circle_pop'] = ('Círculo Pop', 'SHAPES', doc('Circle Pop', [layer('Circle', [group(ell(160), fill(CORAL))], s=pop_scale())]))
E['ring_burst'] = ('Anel Explosivo', 'SHAPES', doc('Ring Burst', [layer('Ring', [group(ell(120), stroke(YEL, 14))],
    s=anim([(0, [20, 20, 100]), (40, [260, 260, 100])]), o=anim([(0, 100), (40, 0)]))]))
E['square_spin'] = ('Quadrado Girando', 'SHAPES', doc('Square Spin', [layer('Square', [group(rect(140, 140, r=18), fill(BLUE))],
    r=anim([(0, 0), (60, 360)], LIN), s=pulse_scale(95, 105))]))
E['triangle_bounce'] = ('Triângulo Saltando', 'SHAPES', doc('Triangle', [layer('Tri', [group(star(3, 95, p=(0, 0)), fill(PURPLE))],
    p=anim([(0, [200, 220, 0]), (15, [200, 150, 0]), (30, [200, 220, 0]), (45, [200, 175, 0]), (60, [200, 220, 0])]))]))
heart = path([(0, -40), (90, -95), (0, 95), (-90, -95)], True,
             ins=[[0, 0], [-40, -55], [60, -45], [-90, 60]], outs=[[0, 0], [90, 60], [-60, -45], [40, -55]])
heart['ks']['k'] = {'c': True, 'v': [[0, -45], [0, 95]],
                    'i': [[-60, 85], [150, -90]], 'o': [[60, 85], [-150, -90]]}
heart['ks']['k'] = {'c': True,
    'v': [[0, -50], [95, -70], [0, 95], [-95, -70]],
    'i': [[0, 0], [-35, -55], [55, -40], [-55, 30]],
    'o': [[0, 0], [55, 30], [-55, -40], [35, -55]]}
E['heart_beat'] = ('Coração Batendo', 'SHAPES', doc('Heart', [layer('Heart', [group(heart, fill(PINK))],
    s=anim([(0, [100, 100, 100]), (8, [122, 122, 100]), (16, [100, 100, 100]), (24, [115, 115, 100]), (34, [100, 100, 100]), (60, [100, 100, 100])]))]))
E['sparkle'] = ('Brilho', 'SHAPES', doc('Sparkle', [
    layer('Big', [group(star(4, 110, 22), fill(YEL))], s=anim([(0, [0, 0, 100]), (15, [100, 100, 100]), (30, [70, 70, 100]), (45, [100, 100, 100]), (60, [0, 0, 100])]), r=anim([(0, 0), (60, 90)], LIN)),
    layer('Small', [group(star(4, 45, 9), fill(WHITE))], p=(300, 110), s=anim([(10, [0, 0, 100]), (25, [100, 100, 100]), (45, [0, 0, 100]), (60, [0, 0, 100])])),
]))
E['underline_draw'] = ('Sublinhado', 'SHAPES', doc('Underline', [layer('Line', [group(
    path([(-150, 10), (-50, -8), (60, 6), (150, -6)]), trim([(0, 0), (22, 100), (60, 100)]), stroke(YEL, 18))])]))
E['circle_draw'] = ('Círculo Destaque', 'SHAPES', doc('Circle Draw', [layer('Circle', [group(ell(300, 220), trim([(0, 0), (26, 100), (60, 100)]), stroke(CORAL, 12))], r=static(-8))]))
E['check_draw'] = ('Check Animado', 'SHAPES', doc('Check', [
    layer('Disc', [group(ell(240), fill(GREEN))], s=pop_scale()),
    layer('Tick', [group(path([(-60, 0), (-15, 45), (70, -50)]), trim([(10, 0), (28, 100), (60, 100)]), stroke(WHITE, 26))]),
]))
E['cross_x'] = ('X Vermelho', 'SHAPES', doc('Cross', [
    layer('Disc', [group(ell(240), fill(CORAL))], s=pop_scale()),
    layer('X1', [group(path([(-55, -55), (55, 55)]), trim([(8, 0), (20, 100), (60, 100)]), stroke(WHITE, 26))]),
    layer('X2', [group(path([(55, -55), (-55, 55)]), trim([(16, 0), (28, 100), (60, 100)]), stroke(WHITE, 26))]),
]))
E['dots_loading'] = ('Três Pontos', 'SHAPES', doc('Dots', [layer(f'Dot{i}', [group(ell(56), fill(WHITE))],
    p=anim([(i * 8, [120 + i * 80, 200, 0]), (i * 8 + 10, [120 + i * 80, 160, 0]), (i * 8 + 20, [120 + i * 80, 200, 0]), (60, [120 + i * 80, 200, 0])])) for i in range(3)]))
# ── Setas ──
def arrow_layer(color_hex):
    return [group(rect(36, 130, p=(0, 30), r=8), star(3, 62, p=(0, -55)), fill(color_hex))]
for slug, name, rot, axis in [('arrow_left', 'Seta Esquerda', -90, (-30, 0)), ('arrow_right', 'Seta Direita', 90, (30, 0)), ('arrow_up', 'Seta para Cima', 0, (0, -30))]:
    E[slug] = (name, 'ARROWS', doc(name, [layer('Arrow', arrow_layer(CORAL), r=static(rot),
        p=anim([(0, [200, 200, 0]), (15, [200 + axis[0], 200 + axis[1], 0]), (30, [200, 200, 0]), (45, [200 + axis[0], 200 + axis[1], 0]), (60, [200, 200, 0])]))]))
E['arrow_curved'] = ('Seta Curva', 'ARROWS', doc('Curved', [
    layer('Curve', [group(path([(-120, 80), (-20, -90), (110, -40)], ins=[[0, 0], [-90, 0], [0, 0]], outs=[[0, 0], [90, 0], [0, 0]]),
                          trim([(0, 0), (24, 100), (60, 100)]), stroke(YEL, 16))]),
    layer('Head', [group(star(3, 30, p=(0, 0)), fill(YEL))], p=(118, 160), r=static(105), s=anim([(18, [0, 0, 100]), (26, [100, 100, 100]), (60, [100, 100, 100])])),
]))
E['chevrons'] = ('Chevrons', 'ARROWS', doc('Chevrons', [layer(f'C{i}', [group(path([(-50, -40), (0, 10), (50, -40)]), stroke(WHITE, 20))],
    p=(200, 140 + i * 55), o=anim([(i * 6, 20), (i * 6 + 12, 100), (i * 6 + 24, 20), (60, 20)])) for i in range(3)]))
# ── Balões ──
E['speech_bubble'] = ('Balão de Fala', 'CALLOUTS', doc('Speech', [layer('Bubble', [group(rect(280, 170, p=(0, -20), r=40),
    path([(-60, 55), (-90, 120), (-10, 60)], True), fill(WHITE))], s=pop_scale(112))]))
E['thought_bubble'] = ('Balão de Pensamento', 'CALLOUTS', doc('Thought', [
    layer('Cloud', [group(ell(220, 150, (0, -40)), ell(110, 100, (-95, -10)), ell(110, 100, (95, -10)), fill(WHITE))], s=pop_scale(110, 100, 10)),
    layer('Dot1', [group(ell(40), fill(WHITE))], p=(130, 300), s=pop_scale(110, 100, 0)),
    layer('Dot2', [group(ell(26), fill(WHITE))], p=(95, 345), s=pop_scale(110, 100, 4)),
]))
E['alert_badge'] = ('Alerta', 'CALLOUTS', doc('Alert', [
    layer('Disc', [group(ell(230), fill(YEL))], s=pulse_scale(100, 108)),
    layer('Mark', [group(rect(30, 100, p=(0, -25), r=12), ell(34, 34, (0, 60)), fill(INK))]),
]))
# ── Selos ──
E['star_rating'] = ('Cinco Estrelas', 'BADGES', doc('Rating', [layer(f'Star{i}', [group(star(5, 34, 15), fill(YEL))],
    p=(80 + i * 60, 200), s=anim([(i * 6, [0, 0, 100]), (i * 6 + 10, [125, 125, 100]), (i * 6 + 16, [100, 100, 100]), (60, [100, 100, 100])])) for i in range(5)]))
E['new_burst'] = ('Selo Novo', 'BADGES', doc('Burst', [layer('Burst', [group(star(16, 150, 118), fill(CORAL))],
    r=anim([(0, 0), (60, 45)], LIN), s=pop_scale(115))]))
E['verified'] = ('Verificado', 'BADGES', doc('Verified', [
    layer('Seal', [group(star(12, 130, 112), fill(BLUE))], s=pop_scale(), r=anim([(0, -30), (26, 0), (60, 0)])),
    layer('Tick', [group(path([(-45, 0), (-10, 35), (55, -40)]), trim([(14, 0), (30, 100), (60, 100)]), stroke(WHITE, 22))]),
]))
E['price_tag'] = ('Etiqueta', 'BADGES', doc('Tag', [layer('Tag', [group(path([(-120, -60), (80, -60), (130, 0), (80, 60), (-120, 60)], True),
    ell(26, 26, (-80, 0)), fill(YEL))], r=anim([(0, -12), (15, 8), (30, -6), (45, 4), (60, -12)]), s=pop_scale(110))]))
# ── Celebração ──
def burst_lines(colors, n=10, r0=40, r1=150):
    layers = []
    for i in range(n):
        a = 2 * math.pi * i / n
        x0, y0, x1, y1 = r0 * math.cos(a), r0 * math.sin(a), r1 * math.cos(a), r1 * math.sin(a)
        layers.append(layer(f'Ray{i}', [group(path([(x0, y0), (x1, y1)]),
            {'ty': 'tm', 's': anim([(6, 0), (26, 100)]), 'e': anim([(0, 0), (18, 100)]), 'o': static(0), 'm': 1, 'nm': 'Trim'},
            stroke(colors[i % len(colors)], 10))]))
    return layers
def shifted(layers, dt, dx, dy, rot):
    out = json.loads(json.dumps(layers))
    for l in out:
        l['ks']['p'] = static([200 + dx, 200 + dy, 0]); l['ks']['r'] = static(rot)
        for g in l['shapes']:
            for it in g['it']:
                if it['ty'] == 'tm':
                    for key in ('s', 'e'):
                        for k in it[key]['k']: k['t'] += dt
    return out
E['fireworks'] = ('Fogos', 'CELEBRATION', doc('Fireworks',
    burst_lines([YEL, CORAL, BLUE, PINK], 12) + shifted(burst_lines([PINK, YEL, BLUE], 10, 25, 110), 28, -60, -50, 15)))
E['sparkles_cluster'] = ('Brilhos', 'CELEBRATION', doc('Sparkles', [layer(f'S{i}', [group(star(4, 40 - i * 6, 8), fill([YEL, WHITE, PINK, BLUE][i]))],
    p=[(130, 140), (280, 120), (250, 280), (120, 270)][i], s=anim([(i * 10, [0, 0, 100]), (i * 10 + 10, [100, 100, 100]), (i * 10 + 24, [0, 0, 100]), (60, [0, 0, 100])])) for i in range(4)]))
E['balloon'] = ('Balão', 'CELEBRATION', doc('Balloon', [
    layer('String', [group(path([(0, 90), (12, 140), (-6, 190)]), stroke(WHITE, 4))], p=anim([(0, [200, 220, 0]), (30, [200, 200, 0]), (60, [200, 220, 0])])),
    layer('Body', [group(ell(150, 180), fill(CORAL))], p=anim([(0, [200, 220, 0]), (30, [200, 200, 0]), (60, [200, 220, 0])]), r=anim([(0, -4), (30, 4), (60, -4)])),
]))
# ── Emojis (rostinhos simples, originais) ──
def face(name, mouth, eyes_wink=False):
    eye_r = group(ell(26, 34, (40, -25)), fill(INK)) if not eyes_wink else group(path([(25, -25), (55, -25)]), stroke(INK, 10))
    return doc(name, [
        layer('Face', [group(ell(240), fill(YEL))], s=pop_scale(112)),
        layer('Eyes', [group(ell(26, 34, (-40, -25)), fill(INK)), eye_r],
              s=anim([(0, [100, 100, 100]), (40, [100, 100, 100]), (44, [100, 10, 100]), (48, [100, 100, 100]), (60, [100, 100, 100])])),
        layer('Mouth', [mouth]),
    ])
E['face_smile'] = ('Sorriso', 'EMOJIS', face('Smile', group(path([(-55, 30), (55, 30)], ins=[[0, 0], [45, 0]], outs=[[30, 50], [0, 0]]), stroke(INK, 12))))
E['face_smile']['2'] if False else None
smile = path([(-55, 30), (0, 70), (55, 30)], ins=[[0, 0], [-35, 0], [0, 0]], outs=[[0, 0], [35, 0], [0, 0]])
E['face_smile'] = ('Sorriso', 'EMOJIS', face('Smile', group(smile, stroke(INK, 12))))
E['face_wow'] = ('Uau', 'EMOJIS', face('Wow', group(ell(50, 64, (0, 50)), fill(INK))))
E['face_wink'] = ('Piscadinha', 'EMOJIS', face('Wink', group(path([(-55, 30), (0, 70), (55, 30)], ins=[[0, 0], [-35, 0], [0, 0]], outs=[[0, 0], [35, 0], [0, 0]]), stroke(INK, 12)), eyes_wink=True))
# ── Social / Gaming ──
E['tap_here'] = ('Toque Aqui', 'SOCIAL', doc('Tap', [
    layer('Ripple', [group(ell(90), stroke(WHITE, 8))], s=anim([(0, [40, 40, 100]), (30, [220, 220, 100]), (60, [220, 220, 100])]), o=anim([(0, 100), (30, 0), (60, 0)])),
    layer('Dot', [group(ell(70), fill(WHITE, 90))], s=anim([(0, [100, 100, 100]), (6, [80, 80, 100]), (14, [100, 100, 100]), (60, [100, 100, 100])])),
]))
E['play_button'] = ('Botão Play', 'SOCIAL', doc('Play', [
    layer('Disc', [group(ell(230), fill(CORAL))], s=pulse_scale(100, 106)),
    layer('Tri', [group(star(3, 60, p=(10, 0), rot=90), fill(WHITE))]),
]))
E['comment_bubble'] = ('Comentário', 'SOCIAL', doc('Comment', [layer('Bubble', [group(rect(220, 150, p=(0, -15), r=36),
    path([(30, 55), (70, 110), (75, 55)], True), fill(BLUE)), group(ell(22, 22, (-50, -15)), ell(22, 22, (0, -15)), ell(22, 22, (50, -15)), fill(WHITE))],
    s=pop_scale(110))]))
E['lightning'] = ('Raio', 'GAMING', doc('Bolt', [layer('Bolt', [group(path([(20, -140), (-70, 20), (-5, 20), (-25, 140), (70, -30), (5, -30)], True), fill(YEL))],
    s=pop_scale(115), o=anim([(26, 100), (32, 40), (38, 100), (44, 50), (50, 100), (60, 100)]))]))
E['coin_spin'] = ('Moeda Girando', 'GAMING', doc('Coin', [layer('Coin', [group(ell(200), fill(YEL)), group(ell(130), stroke('#E0A800', 14))],
    s=anim([(0, [100, 100, 100]), (15, [8, 100, 100]), (30, [100, 100, 100]), (45, [8, 100, 100]), (60, [100, 100, 100])], LIN))]))
E['explosion'] = ('Explosão', 'GAMING', doc('Boom', [
    layer('Outer', [group(star(14, 170, 95), fill(CORAL))], s=anim([(0, [0, 0, 100]), (10, [110, 110, 100]), (18, [95, 95, 100]), (60, [100, 100, 100])]), r=anim([(0, 0), (60, 20)], LIN)),
    layer('Inner', [group(star(12, 100, 60), fill(YEL))], s=anim([(4, [0, 0, 100]), (14, [110, 110, 100]), (22, [95, 95, 100]), (60, [100, 100, 100])])),
]))
E['level_up'] = ('Subiu de Nível', 'GAMING', doc('LevelUp', [
    layer('Arrow', arrow_layer(GREEN), p=anim([(0, [200, 260, 0]), (20, [200, 180, 0]), (40, [200, 200, 0]), (60, [200, 200, 0])]), o=anim([(0, 0), (12, 100), (60, 100)])),
    layer('Glow', [group(ell(250), stroke(GREEN, 6))], s=anim([(10, [40, 40, 100]), (40, [130, 130, 100]), (60, [130, 130, 100])]), o=anim([(10, 100), (40, 0), (60, 0)])),
]))

# validação estrutural
def check(d):
    assert d['layers']
    for l in d['layers']:
        for key in ('o', 'r', 'p', 'a', 's'): assert key in l['ks']
        for g in l['shapes']:
            assert g['ty'] == 'gr' and g['it'][-1]['ty'] == 'tr'
    def walk(o):
        if isinstance(o, dict):
            if o.get('a') == 1:
                ts = [k['t'] for k in o['k']]; assert ts == sorted(ts) and len(set(ts)) == len(ts), ts
                for k in o['k'][:-1]: assert 'e' in k and len(k['e']) == len(k['s'])
            for v in o.values(): walk(v)
        elif isinstance(o, list):
            for v in o: walk(v)
    walk(d)
meta = []
for slug, (name, cat, d) in E.items():
    check(d)
    json.dump(d, open(f'{OUT}/{slug}.json', 'w'), separators=(',', ':'))
    meta.append((slug, name, cat))
json.dump(meta, open('/tmp/claude-0/-home-claude-ed/4241a4ef-8c73-5cc8-916f-1296d60f6d03/scratchpad/gen/lottie_meta.json', 'w'), ensure_ascii=False)
print(len(meta), 'elementos gerados')
