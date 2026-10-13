import json, subprocess, base64, os
import numpy as np
from PIL import Image
S = '/tmp/claude-0/-home-claude-ed/4241a4ef-8c73-5cc8-916f-1296d60f6d03/scratchpad'
ED = '/home/claude/ed/editor-engine/src/main/assets/editor'
RES = '/home/claude/ed/editor-engine/src/main/res/raw'
header = open(f'{RES}/effect_header.glsl').read(); footer = open(f'{RES}/effect_footer.glsl').read()
added = set(json.load(open(f'{S}/gen/added_filters.json')))
cat = [e for e in json.load(open(f'{ED}/effects.json')) if e['id'] in added]
jobs = []
for e in cat:
    body = open(f"{ED}/effects/{e['shader']}").read()
    frag = header + '\n' + '\n'.join(f"uniform float p_{p['id']};" for p in e['parameters']) + '\n' + body + '\n' + footer
    uni = {f"p_{p['id']}": p['default'] for p in e['parameters']}; uni['uIntensity'] = 1.0
    jobs.append({'id': e['id'], 'fragment': frag, 'uniforms': uni, 'progress': [0.5], 'texA': f'{S}/texA.png'})
json.dump(jobs, open(f'{S}/gen/jobs_fx.json', 'w'))
subprocess.run(['python3', f'{S}/gen/harness.py', f'{S}/gen/jobs_fx.json', f'{S}/gen/res_fx.json'], check=True)
res = json.load(open(f'{S}/gen/res_fx.json'))
A = np.asarray(Image.open(f'{S}/texA.png').convert('RGB')).astype(int)
os.makedirs(f'{S}/gen/fx', exist_ok=True)
ok = 0; tiles = []
for e in cat:
    r = res[e['id']]
    if 'error' in r: print('X', e['id'], r['error'][:300]); continue
    f = np.frombuffer(base64.b64decode(r['frames']['0.5']), dtype=np.uint8).reshape(128, 128, 4)[::-1][..., :3]
    d = float(np.abs(f.astype(int) - A).mean()); m = float(f.mean())
    good = d > 3 and 6 < m < 250
    ok += good
    if not good: print('?', e['id'], 'diff', round(d, 1), 'media', round(m, 1))
    Image.fromarray(f).save(f"{S}/gen/fx/{e['id']}.png"); tiles.append(e['id'])
print(ok, 'ok de', len(cat))
cols = 10; sheet = Image.new('RGB', (cols * 96, ((len(tiles) + cols - 1) // cols) * 96))
for i, k in enumerate(tiles): sheet.paste(Image.open(f'{S}/gen/fx/{k}.png').resize((96, 96)), ((i % cols) * 96, (i // cols) * 96))
sheet.save(f'{S}/gen/fx_sheet.png')
