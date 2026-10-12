import json, re, shutil, os
S = '/tmp/claude-0/-home-claude-ed/4241a4ef-8c73-5cc8-916f-1296d60f6d03/scratchpad'
ED = '/home/claude/ed/editor-engine/src/main/assets/editor'
cat = json.load(open(f'{ED}/transitions.json'))
existing_ids = {t['id'] for t in cat}
existing_names = {t['name'].lower() for t in cat}
man = json.load(open(f'{S}/gen/gl/_manifest.json'))['entries']
SKIP = {'cannabisleaf'}
RENAME = {'ZoomRigthWipe': 'Zoom Right Wipe', 'coord-from-in': 'Coord In', 'HSVfade': 'HSV Fade',
          'TVStatic': 'TV Static', 'Drop_Zone_Flicker': 'Drop Zone Flicker', 'Revolve_Left': 'Revolve Left',
          'powerKaleido': 'Power Kaleido', 'randomNoisex': 'Random Noise', 'burn0': 'Burn Soft',
          'scale-in': 'Scale In', 'directional-easing': 'Directional Easing', 'mosaic_transition': 'Mosaic Shift',
          'x_axis_translation': 'X Axis Slide', 'old_tv_lost_signal': 'Old TV Lost Signal'}
RULES = [  # (palavras-chave, categoria, engine)
    (('glitch', 'datamosh', 'static', 'noise', 'signal', 'flicker'), 'Glitch', 'RGB_GLITCH'),
    (('colourdistance', 'colorphase', 'hsv', 'rgb', 'multiply'), 'RGB', 'RGB_GLITCH'),
    (('mosaic', 'pixel', 'grid', 'puzzle', 'squares', 'chess', 'hexagon', 'block', 'tiles', 'polka', 'doom'), 'Gaming', 'CREATIVE'),
    (('burn', 'film', 'overexposure', 'luminance', 'melt'), 'Film', 'LIGHT_FLASH'),
    (('blur', 'defocus'), 'Blur', 'BLUR'),
    (('cube', 'flip', 'fold', 'page', 'book', 'doorway', 'revolve', 'stereo', 'rolls', 'swap', 'box'), '3D', 'PERSPECTIVE_3D'),
    (('zoom', 'scale', 'vanish', 'bounce'), 'Zoom', 'TRANSFORM'),
    (('slide', 'translation', 'directional', 'leftright', 'topbottom', 'wind'), 'Slide', 'TRANSFORM'),
    (('wipe', 'circle', 'rectangle', 'radial', 'angular', 'heart', 'star', 'window', 'crop', 'open', 'close', 'bowtie', 'pinwheel', 'crosshatch', 'blinds'), 'Wipe', 'MASK_WIPE'),
    (('ripple', 'wave', 'swirl', 'morph', 'kaleido', 'water', 'warp', 'squeeze', 'polar', 'undulat', 'dreamy', 'flyeye', 'butterfly', 'parametric', 'perlin', 'fragment'), 'Distortion', 'DISTORTION_UV'),
    (('fade', 'dissolve'), 'Fade', 'BLEND'),
]
def classify(stem):
    s = stem.lower()
    for words, c, eng in RULES:
        if any(w in s for w in words): return c, eng
    return 'Creative', 'CREATIVE'

added, licenses = [], {}
for e in man:
    if e['stem'] in SKIP or e['id'] in existing_ids: continue
    name = RENAME.get(e['stem'], e['name'])
    if name.lower() in existing_names: name += ' Pro'
    existing_names.add(name.lower())
    c, eng = classify(e['stem'])
    params = [{'id': p['id'], 'name': p['name'], 'type': 'float', 'min': p['min'], 'max': p['max'], 'default': p['default']} for p in e['parameters']]
    cat.append({'id': e['id'], 'version': 1, 'name': name, 'category': c, 'engine': eng,
                'defaultDurationUs': 700000, 'minDurationUs': 100000, 'maxDurationUs': 3000000,
                'shaderFile': e['shaderFile'], 'supportsDirection': False, 'parameters': params})
    shutil.copy(f"{S}/gen/gl/{e['shaderFile']}", f"{ED}/transitions/{e['shaderFile']}")
    added.append(e['id']); licenses.setdefault(e['license'], []).append(f"{name} ({e['stem']}.glsl) — {e['author'] or 'gl-transitions contributors'}")
json.dump(cat, open(f'{ED}/transitions.json', 'w'), indent=1, ensure_ascii=False)
json.dump({'added': added, 'licenses': licenses}, open(f'{S}/gen/added_transitions.json', 'w'), indent=1, ensure_ascii=False)
import collections
print(len(added), 'novas; total', len(cat)); print(collections.Counter(t['category'] for t in cat))
