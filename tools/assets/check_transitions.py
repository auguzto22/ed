import json, re, sys, os, base64, subprocess
import numpy as np
from PIL import Image
S = '/tmp/claude-0/-home-claude-ed/4241a4ef-8c73-5cc8-916f-1296d60f6d03/scratchpad'
RES = '/home/claude/ed/editor-engine/src/main/res/raw'
header = open(f'{RES}/transition_header.glsl').read(); footer = open(f'{RES}/transition_footer.glsl').read()
UNI = re.compile(r'uniform\s+\w+\s+([A-Za-z0-9_]+)\s*;')
def supp(i): return {'perspective':1.0,'radius':16.0,'softness':0.1,'intensity':1.0,'strength':1.0,'blur':1.0}.get(i,0.0)

def build(body, params):
    body_uni = set(UNI.findall(body))
    hdr = '\n'.join(l for l in header.splitlines() if not (UNI.search(l) and UNI.search(l).group(1) in body_uni))
    body_params = set(re.findall(r'\bp_([a-zA-Z0-9_]+)\b', body))
    declared = {u[2:] for u in body_uni if u.startswith('p_')}
    ids = sorted(({p['id'] for p in params} | body_params) - declared)
    gen = '\n'.join(f'uniform float p_{i};' for i in ids)
    uniforms = {f'p_{i}': next((p['default'] for p in params if p['id']==i), supp(i)) for i in ids}
    return f'{hdr}\n{gen}\n{body}\n{footer}', uniforms

def run(items, tag):
    jobs = []
    for it in items:
        frag, uni = build(open(it['path']).read(), it['parameters'])
        jobs.append({'id': it['id'], 'fragment': frag, 'uniforms': uni, 'progress': [0.0, 0.5, 1.0], 'texA': f'{S}/texA.png', 'texB': f'{S}/texB.png'})
    jp, rp = f'{S}/gen/jobs_{tag}.json', f'{S}/gen/res_{tag}.json'
    json.dump(jobs, open(jp, 'w'))
    subprocess.run(['python3', f'{S}/gen/harness.py', jp, rp], check=True)
    return json.load(open(rp))

def img(b64):
    a = np.frombuffer(base64.b64decode(b64), dtype=np.uint8).reshape(128, 128, 4)[::-1]
    return a
A = np.asarray(Image.open(f'{S}/texA.png').convert('RGBA')); B = np.asarray(Image.open(f'{S}/texB.png').convert('RGBA'))
def diff(x, y): return float(np.abs(x[..., :3].astype(int) - y[..., :3].astype(int)).mean())

def evaluate(results):
    report = {}
    for k, r in results.items():
        if 'error' in r: report[k] = {'ok': False, 'why': r['error'][:400]}; continue
        f0, f5, f1 = (img(r['frames'][p]) for p in ('0', '0.5', '1'))
        d0, d1 = diff(f0, A), diff(f1, B)
        mid_bright = float(f5[..., :3].mean())
        ok = d0 < 12 and d1 < 12 and mid_bright > 8
        report[k] = {'ok': ok, 'd0': round(d0, 1), 'd1': round(d1, 1), 'mid': round(mid_bright, 1)}
        Image.fromarray(f5).convert('RGB').save(f'{S}/gen/thumbs/{k}.png')
    return report

if __name__ == '__main__':
    os.makedirs(f'{S}/gen/thumbs', exist_ok=True)
    mode = sys.argv[1]
    if mode == 'existing':
        cat = json.load(open('/home/claude/ed/editor-engine/src/main/assets/editor/transitions.json'))
        items = [{'id': t['id'], 'path': f"/home/claude/ed/editor-engine/src/main/assets/editor/transitions/{t['shaderFile']}",
                  'parameters': t['parameters']} for t in cat]
    else:
        man = json.load(open(f'{S}/gen/gl/_manifest.json'))
        items = [{'id': e['id'], 'path': f"{S}/gen/gl/{e['shaderFile']}", 'parameters': e['parameters']} for e in man['entries']]
    rep = evaluate(run(items, mode))
    json.dump(rep, open(f'{S}/gen/report_{mode}.json', 'w'), indent=1)
    bad = {k: v for k, v in rep.items() if not v['ok']}
    print(f'{mode}: {len(rep) - len(bad)} ok / {len(rep)}')
    for k, v in bad.items(): print(' X', k, v)
