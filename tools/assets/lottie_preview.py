import json, math, sys
import numpy as np
import matplotlib; matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.path import Path
from matplotlib.patches import PathPatch
from matplotlib.transforms import Affine2D

def val(prop, t):
    if prop['a'] == 0: return prop['k']
    ks = prop['k']
    if t <= ks[0]['t']: return ks[0]['s']
    for k, n in zip(ks, ks[1:]):
        if k['t'] <= t <= n['t']:
            u = (t - k['t']) / max(n['t'] - k['t'], 1e-6)
            e = k.get('e', n['s'])
            return [a + (b - a) * u for a, b in zip(k['s'], e)]
    return ks[-1]['s']
def v1(prop, t):
    x = val(prop, t); return x[0] if isinstance(x, list) else x

def shape_paths(items, t):
    paths = []
    for it in items:
        ty = it['ty']
        if ty == 'el':
            w, h = val(it['s'], t); cx, cy = val(it['p'], t)
            a = np.linspace(0, 2 * np.pi, 64); paths.append(np.c_[cx + w / 2 * np.cos(a), cy + h / 2 * np.sin(a)])
        elif ty == 'rc':
            w, h = val(it['s'], t); cx, cy = val(it['p'], t)
            paths.append(np.array([[cx - w/2, cy - h/2], [cx + w/2, cy - h/2], [cx + w/2, cy + h/2], [cx - w/2, cy + h/2], [cx - w/2, cy - h/2]]))
        elif ty == 'sr':
            n = int(v1(it['pt'], t)); ro = v1(it['or'], t); cx, cy = val(it['p'], t); rot = math.radians(v1(it['r'], t))
            ri = v1(it['ir'], t) if 'ir' in it else None
            pts = []
            steps = n * 2 if ri else n
            for i in range(steps + 1):
                ang = rot - math.pi / 2 + 2 * math.pi * i / steps
                r = ro if (not ri or i % 2 == 0) else ri
                pts.append([cx + r * math.cos(ang), cy + r * math.sin(ang)])
            paths.append(np.array(pts))
        elif ty == 'sh':
            k = it['ks']['k']; v, ii, oo = k['v'], k['i'], k['o']; pts = []
            segs = list(range(len(v) - 1)) + ([len(v) - 1] if k['c'] else [])
            for s in segs:
                a, b = np.array(v[s]), np.array(v[(s + 1) % len(v)])
                c1, c2 = a + np.array(oo[s]), b + np.array(ii[(s + 1) % len(v)])
                for u in np.linspace(0, 1, 24):
                    pts.append((1-u)**3*a + 3*(1-u)**2*u*c1 + 3*(1-u)*u**2*c2 + u**3*b)
            paths.append(np.array(pts))
    return paths

def trim_path(p, s, e):
    if s <= 0 and e >= 100: return p
    d = np.r_[0, np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]
    if d[-1] == 0: return p[:1]
    lo, hi = d[-1] * s / 100, d[-1] * e / 100
    return p[(d >= lo) & (d <= hi)] if hi > lo else p[:0]

def draw_group(ax, g, t, M):
    items = g['it']; trn = items[-1]
    px, py = val(trn['p'], t); sx, sy = val(trn['s'], t)[:2]; r = v1(trn['r'], t)
    M2 = Affine2D().scale(sx / 100, sy / 100).rotate_deg(r).translate(px, py) + M
    paths = shape_paths(items, t)
    tm = next((i for i in items if i['ty'] == 'tm'), None)
    if tm: paths = [trim_path(p, v1(tm['s'], t), v1(tm['e'], t)) for p in paths]
    for it in items:
        if it['ty'] == 'fl':
            c = it['c']['k']; o = v1(it['o'], t) / 100
            for p in paths:
                if len(p) > 2: ax.add_patch(PathPatch(Path(p), facecolor=c[:3], alpha=o, lw=0, transform=M2 + ax.transData))
        if it['ty'] == 'st':
            c = it['c']['k']; w = v1(it['w'], t)
            for p in paths:
                if len(p) > 1: ax.add_patch(PathPatch(Path(p), facecolor='none', edgecolor=c[:3], lw=w * 0.18, capstyle='round', transform=M2 + ax.transData))
        if it['ty'] == 'gr': draw_group(ax, it, t, M2)

def render(doc, t, ax):
    ax.set_xlim(0, doc['w']); ax.set_ylim(doc['h'], 0); ax.set_facecolor('#3a3a40'); ax.set_xticks([]); ax.set_yticks([])
    for l in reversed(doc['layers']):
        if not (l['ip'] <= t < l['op']): continue
        ks = l['ks']; px, py = val(ks['p'], t)[:2]; sx, sy = val(ks['s'], t)[:2]; r = v1(ks['r'], t); o = v1(ks['o'], t)
        if o <= 1: continue
        M = Affine2D().scale(sx / 100, sy / 100).rotate_deg(r).translate(px, py)
        for g in l['shapes']: draw_group(ax, g, t, M)

meta = json.load(open(sys.argv[1])); base = sys.argv[2]
fig, axes = plt.subplots(len(meta), 3, figsize=(4.5, 1.5 * len(meta)))
for row, (slug, name, cat) in enumerate(meta):
    d = json.load(open(f'{base}/{slug}.json'))
    for col, t in enumerate((10, 30, 50)):
        render(d, t, axes[row][col])
    axes[row][0].set_ylabel(slug, fontsize=6, rotation=0, ha='right')
plt.subplots_adjust(wspace=0.02, hspace=0.05, left=0.3)
plt.savefig(sys.argv[3], dpi=80)
