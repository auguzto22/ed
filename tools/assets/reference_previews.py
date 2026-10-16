"""Prévias animadas (tipo GIF) feitas sobre a mídia de referência, não sobre o vídeo da pessoa.

Gera em editor-engine/src/main/assets/editor/previews/reference/:
  - reference_loop.webp   faixa vertical com LOOP_FRAMES quadros quadrados do vídeo de referência.
                          O app recorta a faixa e anima: filtros por ColorMatrix, presets na GPU.
  - transitions/<id>.webp WebP animado de cada transição, da foto de referência para o vídeo.

Também pode renderizar amostras de presets (multi-pass) para conferir o visual fora do aparelho.

Uso:
  python3 tools/assets/reference_previews.py VIDEO FOTO WORKDIR [--presets-sample N]

Requisitos: ffmpeg, Python 3 com Playwright, Pillow e NumPy; Chromium do Playwright (WebGL1 via
SwiftShader, o mesmo GLSL ES 1.0 do Android).
"""
import argparse, asyncio, base64, json, os, re, subprocess, sys
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ED = f'{ROOT}/editor-engine/src/main/assets/editor'
RAW = f'{ROOT}/editor-engine/src/main/res/raw'
OUT = f'{ED}/previews/reference'

# Precisa bater com ReferencePreviewFrames.kt.
LOOP_FRAMES = 12
LOOP_FPS = 8
LOOP_SIZE = 128
LOOP_START_S = 2.0
# Transições: cartão de ~1,5:1, foto (A) → vídeo (B).
TR_W, TR_H = 132, 88
TR_HOLD = 2          # quadros parados no começo (foto) e no fim (vídeo)
TR_STEPS = 10        # quadros da transição em si
TR_FRAME_MS = 100

UNI = re.compile(r'uniform\s+\w+\s+([A-Za-z0-9_]+)\s*;')

PAGE = """<!doctype html><html><body><canvas id=c></canvas><script>
const cv = document.getElementById('c');
const gl = cv.getContext('webgl', {preserveDrawingBuffer:true, premultipliedAlpha:false});
const VS = 'attribute vec2 aPos; varying vec2 vUv; void main(){ vUv = aPos*0.5+0.5; gl_Position = vec4(aPos,0.0,1.0); }';
const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);
function params(){ for (const [k,v] of [[gl.TEXTURE_MIN_FILTER,gl.LINEAR],[gl.TEXTURE_MAG_FILTER,gl.LINEAR],[gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE],[gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D,k,v); }
const images = {};
function load(key, url){ return new Promise(r=>{ const im=new Image(); im.onload=()=>{ const t=gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D,t);
  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true); gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,im); params(); images[key]=t; r(); }; im.src=url; }); }
window.loadImages = async (map) => { for (const [k,u] of Object.entries(map)) await load(k,u); };
function sh(type, src){ const s=gl.createShader(type); gl.shaderSource(s,src); gl.compileShader(s);
  if(!gl.getShaderParameter(s,gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; }
function target(w,h){ const t=gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D,t); gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,w,h,0,gl.RGBA,gl.UNSIGNED_BYTE,null); params();
  const f=gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER,f); gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,t,0); return {t,f}; }
// job: {w,h, passes:[{fragment, uniforms}], frames:[{inputs:{uTexSampler|uTexA|uTexB: imageKey}, uniforms:{}}]}
// Cada passe lê o resultado do anterior em uTexSampler (como o EffectThumbnailRenderer).
window.run = (job) => {
  cv.width = job.w; cv.height = job.h;
  const progs = [];
  try { for (const p of job.passes) { const prog=gl.createProgram(); gl.attachShader(prog, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, p.fragment));
    gl.linkProgram(prog); if(!gl.getProgramParameter(prog,gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog)); progs.push(prog); } }
  catch(e){ return {error: String(e.message||e)}; }
  const rt = [target(job.w,job.h), target(job.w,job.h)];
  const out = [];
  for (const fr of job.frames) {
    let src = null;
    progs.forEach((prog, i) => {
      gl.useProgram(prog);
      const loc = gl.getAttribLocation(prog,'aPos'); gl.enableVertexAttribArray(loc); gl.bindBuffer(gl.ARRAY_BUFFER, buf); gl.vertexAttribPointer(loc,2,gl.FLOAT,false,0,0);
      const u = n => gl.getUniformLocation(prog, n);
      let unit = 0;
      const bind = (name, tex) => { if (!u(name)) return; gl.activeTexture(gl.TEXTURE0+unit); gl.bindTexture(gl.TEXTURE_2D, tex); gl.uniform1i(u(name), unit); unit++; };
      const base = src ? src : images[fr.inputs.uTexSampler];
      if (fr.inputs.uTexA) { bind('uTexA', images[fr.inputs.uTexA]); bind('uTexB', images[fr.inputs.uTexB]); }
      else { bind('uTexSampler', base); for (const h of ['uHistory0','uHistory1','uHistory2']) bind(h, base); }
      if (u('uResolution')) gl.uniform2f(u('uResolution'), job.w, job.h);
      for (const [k,v] of Object.entries(job.passes[i].uniforms||{})) { const l=u(k); if (!l) continue; if (Array.isArray(v)) gl['uniform'+v.length+'f'](l, ...v); else gl.uniform1f(l, v); }
      for (const [k,v] of Object.entries(fr.uniforms||{})) if (u(k)) gl.uniform1f(u(k), v);
      const dst = rt[i % 2];
      gl.bindFramebuffer(gl.FRAMEBUFFER, dst.f);
      gl.viewport(0,0,job.w,job.h); gl.clearColor(0,0,0,1); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
      src = dst.t;
    });
    const px = new Uint8Array(job.w*job.h*4); gl.readPixels(0,0,job.w,job.h,gl.RGBA,gl.UNSIGNED_BYTE,px);
    const err = gl.getError(); if (err) return {error:'gl error '+err};
    let s=''; for (let i=0;i<px.length;i+=8192) s+=String.fromCharCode.apply(null, px.subarray(i,i+8192));
    out.push(btoa(s));
  }
  progs.forEach(p => gl.deleteProgram(p)); rt.forEach(r => { gl.deleteTexture(r.t); gl.deleteFramebuffer(r.f); });
  return {frames: out};
};
</script></body></html>"""


def png_url(path):
    return 'data:image/png;base64,' + base64.b64encode(open(path, 'rb').read()).decode()


async def render(images, jobs):
    from playwright.async_api import async_playwright
    results = {}
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(args=['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'])
        page = await browser.new_page()
        await page.set_content(PAGE)
        await page.evaluate('m => loadImages(m)', {k: png_url(v) for k, v in images.items()})
        for i, job in enumerate(jobs):
            try:
                results[job['id']] = await page.evaluate('j => run(j)', job)
            except Exception as e:
                results[job['id']] = {'error': 'page: ' + str(e)[:300]}
            if i % 50 == 49:
                print(f'  {i + 1}/{len(jobs)}', flush=True)
        await browser.close()
    return results


def decode(b64, w, h):
    return np.frombuffer(base64.b64decode(b64), dtype=np.uint8).reshape(h, w, 4)[::-1][..., :3]


def center_crop(img, aspect):
    w, h = img.size
    if w / h > aspect:
        nw = int(h * aspect); return img.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
    nh = int(w / aspect); return img.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))


def extract_loop(video, work):
    os.makedirs(f'{work}/loop', exist_ok=True)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-ss', str(LOOP_START_S), '-i', video, '-frames:v', str(LOOP_FRAMES),
                    '-vf', f'fps={LOOP_FPS}', f'{work}/loop/full%02d.png'], check=True)
    full = [Image.open(f'{work}/loop/full{i + 1:02d}.png').convert('RGB') for i in range(LOOP_FRAMES)]
    return full


def effect_fragment(definition, shader):
    header = open(f'{RAW}/effect_header.glsl').read(); footer = open(f'{RAW}/effect_footer.glsl').read()
    return header + '\n' + '\n'.join(f"uniform float p_{p['id']};" for p in definition['parameters']) + '\n' + shader + '\n' + footer


def transition_fragment(t):
    header = open(f'{RAW}/transition_header.glsl').read(); footer = open(f'{RAW}/transition_footer.glsl').read()
    body = open(f"{ED}/transitions/{t['shaderFile']}").read()
    body_uni = set(UNI.findall(body))
    hdr = '\n'.join(l for l in header.splitlines() if not (UNI.search(l) and UNI.search(l).group(1) in body_uni))
    declared = {u[2:] for u in body_uni if u.startswith('p_')}
    ids = sorted(({p['id'] for p in t['parameters']} | set(re.findall(r'\bp_([a-zA-Z0-9_]+)\b', body))) - declared)
    extra = {'perspective': 1.0, 'radius': 16.0, 'softness': 0.1, 'intensity': 1.0, 'strength': 1.0, 'blur': 1.0}
    uniforms = {f'p_{i}': next((p['default'] for p in t['parameters'] if p['id'] == i), extra.get(i, 0.0)) for i in ids}
    return f"{hdr}\n{chr(10).join(f'uniform float p_{i};' for i in ids)}\n{body}\n{footer}", uniforms


def ease(x):
    return x * x * (3 - 2 * x)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('video'); ap.add_argument('photo'); ap.add_argument('work')
    ap.add_argument('--presets-sample', type=int, default=0, help='renderiza N presets por categoria em WORK/presets (conferência)')
    ap.add_argument('--effects-sample', type=int, default=0, help='renderiza N efeitos avulsos por categoria em WORK/presets (conferência)')
    ap.add_argument('--skip-transitions', action='store_true')
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True); os.makedirs(f'{OUT}/transitions', exist_ok=True)

    # 1. Loop do vídeo: faixa vertical de quadros quadrados.
    full = extract_loop(a.video, a.work)
    squares = [center_crop(f, 1.0).resize((LOOP_SIZE, LOOP_SIZE), Image.LANCZOS) for f in full]
    strip = Image.new('RGB', (LOOP_SIZE, LOOP_SIZE * LOOP_FRAMES))
    for i, s in enumerate(squares):
        strip.paste(s, (0, i * LOOP_SIZE)); s.save(f'{a.work}/loop/sq{i:02d}.png')
    strip.save(f'{OUT}/reference_loop.webp', quality=82, method=6)
    print('reference_loop.webp', os.path.getsize(f'{OUT}/reference_loop.webp'), 'bytes')

    images = {f'sq{i}': f'{a.work}/loop/sq{i:02d}.png' for i in range(LOOP_FRAMES)}
    photo = center_crop(Image.open(a.photo).convert('RGB'), TR_W / TR_H).resize((TR_W, TR_H), Image.LANCZOS)
    photo.save(f'{a.work}/photo_tr.png'); images['photo'] = f'{a.work}/photo_tr.png'
    for i, f in enumerate(full):
        center_crop(f, TR_W / TR_H).resize((TR_W, TR_H), Image.LANCZOS).save(f'{a.work}/loop/tr{i:02d}.png')
        images[f'tr{i}'] = f'{a.work}/loop/tr{i:02d}.png'

    jobs, meta = [], {}
    # 2. Transições: foto parada → transição → vídeo andando.
    if not a.skip_transitions:
        for t in json.load(open(f'{ED}/transitions.json')):
            frag, uni = transition_fragment(t)
            frames = []
            total = TR_HOLD * 2 + TR_STEPS
            for k in range(total):
                p = 0.0 if k < TR_HOLD else 1.0 if k >= TR_HOLD + TR_STEPS else ease((k - TR_HOLD + 1) / (TR_STEPS + 1))
                frames.append({'inputs': {'uTexA': 'photo', 'uTexB': f'tr{max(0, k - TR_HOLD) % LOOP_FRAMES}'},
                               'uniforms': {'uProgress': p}})
            jobs.append({'id': 'tr:' + t['id'], 'w': TR_W, 'h': TR_H, 'passes': [{'fragment': frag, 'uniforms': uni}], 'frames': frames})

    # 3. Amostra de presets (só conferência; no app eles são renderizados na GPU do aparelho).
    if a.presets_sample or a.effects_sample:
        effects = {e['id']: e for e in json.load(open(f'{ED}/effects.json'))}
        presets = json.load(open(f'{ED}/effect_presets.json'))[:0 if not a.presets_sample else None]
        # Efeito avulso = preset de um nó com os valores padrão e intensidade 0,7 (como no app).
        seen = {}
        for e in effects.values():
            if a.effects_sample and seen.setdefault(e['category'], 0) < a.effects_sample:
                seen[e['category']] += 1
                presets.append({'id': 'fx_' + e['id'], 'name': e['name'], 'category': 'Efeito · ' + e['category'],
                                'nodes': [{'assetId': e['id'], 'intensity': .7}]})
        per_cat = {}
        for p in presets:
            per_cat.setdefault(p['category'], [])
            if p['id'].startswith('fx_') or len(per_cat[p['category']]) < a.presets_sample: per_cat[p['category']].append(p)
        for cat, ps in per_cat.items():
            for p in ps:
                passes = []
                for n in p['nodes']:
                    d = effects.get(n['assetId'])
                    if not d: continue
                    uni = {f"p_{q['id']}": min(max(n.get('values', {}).get(q['id'], q['default']), q['min']), q['max']) for q in d['parameters']}
                    uni.update({'uIntensity': min(max(n.get('intensity', .7), 0), 1), 'uBlendMode': 0.0,
                                'uEffectMask': [0, 0, 0, 0], 'uEffectMaskTransform': [0, 0, 0, 1], 'uEffectMaskAspect': 1.0})
                    passes.append({'fragment': effect_fragment(d, open(f"{ED}/effects/{d['shader']}").read()), 'uniforms': uni})
                frames = [{'inputs': {'uTexSampler': f'sq{i}'}, 'uniforms': {'uTime': 0.5 + i / LOOP_FPS, 'uProgress': i / LOOP_FRAMES}}
                          for i in range(LOOP_FRAMES)]
                jobs.append({'id': 'pr:' + p['id'], 'w': LOOP_SIZE, 'h': LOOP_SIZE, 'passes': passes, 'frames': frames})
                meta[p['id']] = {'name': p['name'], 'category': cat}
        os.makedirs(f'{a.work}/presets', exist_ok=True)
        json.dump(meta, open(f'{a.work}/presets/_meta.json', 'w'), ensure_ascii=False, indent=1)

    print('renderizando', len(jobs), 'prévias')
    res = asyncio.run(render(images, jobs))
    failed, sizes = [], []
    for job in jobs:
        r = res[job['id']]
        kind, ident = job['id'].split(':', 1)
        if 'error' in r: failed.append((job['id'], r['error'][:200])); continue
        frames = [Image.fromarray(decode(b, job['w'], job['h'])) for b in r['frames']]
        if kind == 'tr':
            path = f'{OUT}/transitions/{ident}.webp'
            durations = [TR_FRAME_MS * (4 if k in (TR_HOLD - 1, len(frames) - 1) else 1) for k in range(len(frames))]
            frames[0].save(path, 'WEBP', save_all=True, append_images=frames[1:], duration=durations, loop=0,
                           quality=55, method=6, minimize_size=True, allow_mixed=True)
            sizes.append(os.path.getsize(path))
        else:
            path = f'{a.work}/presets/{ident}.webp'
            frames[0].save(path, 'WEBP', save_all=True, append_images=frames[1:], duration=1000 // LOOP_FPS, loop=0, quality=70, method=4)
    if sizes: print('transições:', len(sizes), 'arquivos,', sum(sizes) // 1024, 'KB no total, média', sum(sizes) // len(sizes) // 1024, 'KB')
    for f in failed: print('FALHOU', *f)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
