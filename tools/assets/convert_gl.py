"""Converte gl-transitions (MIT/BSD) para o formato de shader de transição do Recly."""
import json, os, re, sys

SRC = sys.argv[1]; OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)
UNI = re.compile(r'^\s*uniform\s+(\w+)\s+(\w+)\s*(?:/\*\s*=\s*(.*?)\s*\*/)?\s*;\s*(?://\s*=\s*([^;]*?)\s*(?:;.*)?)?$', re.M)

def readable(stem):
    s = stem.replace('_', ' ')
    s = re.sub(r'(?<=[a-z0-9])(?=[A-Z])', ' ', s)
    s = re.sub(r'(?<=[A-Z])(?=[A-Z][a-z])', ' ', s)
    return ' '.join(w[:1].upper() + w[1:] for w in s.split())

def snake(stem):
    s = re.sub(r'(?<=[a-z0-9])(?=[A-Z])', '_', stem)
    s = re.sub(r'[^A-Za-z0-9]+', '_', s).strip('_').lower()
    return 'gl_' + s

def param_range(name, default):
    d = float(default)
    n = name.lower()
    if any(k in n for k in ('smooth', 'soft', 'feather')): return 0.0, max(1.0, d * 2)
    if 'angle' in n or 'rot' in n: return min(-6.2832, d), max(6.2832, d)
    if d == 0: return -1.0, 1.0
    if d < 0: return d * 3, -d * 3
    if d <= 1: return 0.0, max(1.0, d * 3)
    return 0.0, d * 3

entries, skipped = [], []
for fn in sorted(os.listdir(SRC)):
    if not fn.endswith('.glsl'): continue
    stem = fn[:-5]
    code = open(os.path.join(SRC, fn)).read()
    meta = {k.lower(): v.strip() for k, v in re.findall(r'^//\s*(Author|License|Description|Tags)\s*:\s*(.*)$', code, re.M | re.I)}
    lic = meta.get('license', 'MIT').strip()
    params, consts, bad = [], [], None
    for m in UNI.finditer(code):
        typ, name, d1, d2 = m.group(1), m.group(2), m.group(3), m.group(4)
        default = (d1 or d2 or '').strip().rstrip(';').strip()
        if typ == 'sampler2D': bad = 'textura externa'; break
        if typ == 'float':
            if not default: bad = f'sem padrao {name}'; break
            try: dv = float(eval(default.replace('f', ''), {'__builtins__': {}}, {}))
            except Exception: bad = f'padrao invalido {name}={default}'; break
            lo, hi = param_range(name, dv)
            before = code[:m.start()].splitlines()[-3:]
            for line in before:
                rm = re.search(r'@range\(\s*([-\d.]+)\s*,\s*([-\d.]+)\s*\)', line)
                if rm and float(rm.group(1)) <= dv <= float(rm.group(2)) and float(rm.group(1)) < float(rm.group(2)):
                    lo, hi = float(rm.group(1)), float(rm.group(2))
            pid = re.sub(r'(?<=[a-z0-9])(?=[A-Z])', '_', name).lower()
            params.append({'name': name, 'id': pid, 'default': round(dv, 6), 'min': round(lo, 6), 'max': round(hi, 6)})
        else:
            if not default: bad = f'sem padrao {typ} {name}'; break
            if typ == 'int': default = str(int(float(default)))
            if typ in ('vec2', 'vec3', 'vec4', 'ivec2', 'ivec3') and not default.startswith(typ):
                default = f'{typ}({default})'
            if typ == 'float' or typ.startswith('vec'):
                default = re.sub(r'(?<![\w.])(\d+)(?![\w.])', r'\1.0', default) if typ.startswith('vec') else default
            consts.append(f'const {typ} {name} = {default};')
    if bad: skipped.append((stem, bad)); continue
    body = UNI.sub('', code)
    for p in params:
        body = re.sub(r'(?<![\w.])' + re.escape(p['name']) + r'(?!\w)', 'p_' + p['id'], body)
    body = re.sub(r'^\s*precision\s+\w+\s+float\s*;\s*$', '', body, flags=re.M)
    header = (
        f'// {readable(stem)} — adaptado de gl-transitions ({stem}.glsl)\n'
        f'// Autor: {meta.get("author", "gl-transitions contributors")} · Licença: {lic}\n'
        f'// https://github.com/gl-transitions/gl-transitions\n'
    )
    shim = (
        'vec4 getFromColor(vec2 uv) { return texture2D(uTexA, uv); }\n'
        'vec4 getToColor(vec2 uv) { return texture2D(uTexB, uv); }\n'
        'float progress;\nfloat ratio;\n'
    )
    entry_fn = (
        '\nvec4 reclyTransition(vec2 uv, float reclyProgress) {\n'
        '    progress = clamp(reclyProgress, 0.0, 1.0);\n'
        '    ratio = uResolution.x / max(uResolution.y, 1.0);\n'
        '    return transition(uv);\n}\n'
    )
    shader = header + shim + '\n'.join(consts) + ('\n' if consts else '') + body.strip() + '\n' + entry_fn
    sid = snake(stem)
    fname = f'{sid}.frag'
    open(os.path.join(OUT, fname), 'w').write(shader)
    entries.append({
        'id': sid, 'stem': stem, 'name': readable(stem), 'shaderFile': fname,
        'license': lic, 'author': meta.get('author', ''), 'tags': meta.get('tags', ''),
        'description': meta.get('description', ''),
        'parameters': [{'id': p['id'],
                        'glname': p['name'], 'name': readable(p['name']), 'type': 'float',
                        'min': p['min'], 'max': p['max'], 'default': p['default']} for p in params],
    })
json.dump({'entries': entries, 'skipped': skipped}, open(os.path.join(OUT, '_manifest.json'), 'w'), indent=1, ensure_ascii=False)
print(len(entries), 'convertidas;', len(skipped), 'puladas:', skipped)
