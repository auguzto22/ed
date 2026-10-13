"""Compila e renderiza shaders de transição/efeito do Recly em WebGL1 (GLSL ES 1.0) no Chromium headless."""
import asyncio, base64, json, sys, os
from playwright.async_api import async_playwright

S = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGE = """<!doctype html><html><body><canvas id=c width=128 height=128></canvas><script>
const gl = document.getElementById('c').getContext('webgl', {preserveDrawingBuffer:true, premultipliedAlpha:false});
const VS = 'attribute vec2 aPos; varying vec2 vUv; void main(){ vUv = aPos*0.5+0.5; gl_Position = vec4(aPos,0.0,1.0); }';
const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);
function tex(url){ return new Promise(r=>{ const im=new Image(); im.onload=()=>{ const t=gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D,t);
  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true); gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,im);
  for (const [k,v] of [[gl.TEXTURE_MIN_FILTER,gl.LINEAR],[gl.TEXTURE_MAG_FILTER,gl.LINEAR],[gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE],[gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D,k,v);
  r(t); }; im.src=url; }); }
function sh(type, src){ const s=gl.createShader(type); gl.shaderSource(s,src); gl.compileShader(s);
  if(!gl.getShaderParameter(s,gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; }
window.run = async (job) => {
  const A = await tex(job.texA), B = job.texB ? await tex(job.texB) : null;
  const out = {};
  let prog;
  try { prog = gl.createProgram(); gl.attachShader(prog, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, job.fragment));
    gl.linkProgram(prog); if(!gl.getProgramParameter(prog,gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog)); }
  catch(e){ return {error: String(e.message||e)}; }
  gl.useProgram(prog);
  const loc = gl.getAttribLocation(prog,'aPos'); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc,2,gl.FLOAT,false,0,0);
  const u = n => gl.getUniformLocation(prog, n);
  gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, A);
  for (const n of ['uTexA','uTexSampler']) if (u(n)) gl.uniform1i(u(n),0);
  if (B) { gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, B); if (u('uTexB')) gl.uniform1i(u('uTexB'),1); }
  if (u('uResolution')) gl.uniform2f(u('uResolution'),128,128);
  for (const [k,v] of Object.entries(job.uniforms||{})) if (u(k)) gl.uniform1f(u(k),v);
  for (const p of job.progress) {
    if (u('uProgress')) gl.uniform1f(u('uProgress'),p);
    if (u('uTime')) gl.uniform1f(u('uTime'),p*2.0);
    gl.viewport(0,0,128,128); gl.clearColor(0,0,0,0); gl.clear(gl.COLOR_BUFFER_BIT); gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
    const px = new Uint8Array(128*128*4); gl.readPixels(0,0,128,128,gl.RGBA,gl.UNSIGNED_BYTE,px);
    const err = gl.getError(); if (err) return {error:'gl error '+err};
    let s=''; for (let i=0;i<px.length;i+=8192) s+=String.fromCharCode.apply(null, px.subarray(i,i+8192));
    out[p] = btoa(s);
  }
  return {frames: out};
};
</script></body></html>"""

async def main(jobs_path, out_path):
    jobs = json.load(open(jobs_path))
    def data_url(p): return 'data:image/png;base64,' + base64.b64encode(open(p,'rb').read()).decode()
    results = {}
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(args=['--use-gl=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'])
        page = await browser.new_page()
        await page.set_content(PAGE)
        for job in jobs:
            payload = dict(job); payload['texA'] = data_url(job['texA']); payload['texB'] = data_url(job['texB']) if job.get('texB') else None
            try: results[job['id']] = await page.evaluate('j => run(j)', payload)
            except Exception as e: results[job['id']] = {'error': 'page: ' + str(e)[:300]}
        await browser.close()
    json.dump(results, open(out_path, 'w'))

asyncio.run(main(sys.argv[1], sys.argv[2]))
