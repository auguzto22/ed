import json, subprocess, base64, os, sys
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__))
from check_transitions import build, img, S
ED = '/home/claude/ed/editor-engine/src/main/assets/editor'
cat = json.load(open(f'{ED}/transitions.json'))
dark = []
for t in cat:
    p = f'{S}/gen/thumbs/{t["id"]}.png'
    if not os.path.exists(p) or np.asarray(Image.open(p)).mean() < 22: dark.append(t)
jobs = []
for t in dark:
    frag, uni = build(open(f'{ED}/transitions/{t["shaderFile"]}').read(), t['parameters'])
    jobs.append({'id': t['id'], 'fragment': frag, 'uniforms': uni, 'progress': [0.3], 'texA': f'{S}/texA.png', 'texB': f'{S}/texB.png'})
json.dump(jobs, open(f'{S}/gen/jobs_dark.json', 'w'))
subprocess.run(['python3', f'{S}/gen/harness.py', f'{S}/gen/jobs_dark.json', f'{S}/gen/res_dark.json'], check=True)
res = json.load(open(f'{S}/gen/res_dark.json'))
for k, r in res.items():
    if 'frames' in r: Image.fromarray(img(r['frames']['0.3'])).convert('RGB').save(f'{S}/gen/thumbs/{k}.png')
# atlases: 4x4 cells of 128 px
for f in os.listdir(f'{ED}/previews'):
    if f.startswith('transitions_atlas_'): os.remove(f'{ED}/previews/{f}')
reg = json.load(open(f'{ED}/previews/preview_registry.json'))
reg['transitions'] = {}
for i, t in enumerate(cat):
    a, local = i // 16 + 1, i % 16
    name = f'transitions_atlas_{a:02d}'
    reg['transitions'][t['id']] = {'atlas': name, 'col': local % 4, 'row': local // 4}
for a in range((len(cat) + 15) // 16):
    sheet = Image.new('RGB', (512, 512), (18, 18, 20))
    for local, t in enumerate(cat[a * 16:(a + 1) * 16]):
        sheet.paste(Image.open(f'{S}/gen/thumbs/{t["id"]}.png').convert('RGB'), ((local % 4) * 128, (local // 4) * 128))
    sheet.save(f'{ED}/previews/transitions_atlas_{a + 1:02d}.webp', quality=82, method=6)
json.dump(reg, open(f'{ED}/previews/preview_registry.json', 'w'), indent=1)
print('escuras re-renderizadas:', len(dark), 'atlases:', (len(cat) + 15) // 16)
