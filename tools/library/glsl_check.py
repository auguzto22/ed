"""Compiles every library shader in headless Chromium WebGL1 (GLSL ES 1.0, same dialect as Android GLES2)."""
import html, json, os, re, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(__file__))
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RAW = os.path.join(ROOT, "editor-engine/src/main/res/raw")
SHELL = "/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell"


def check(programs):
    """programs: {name: full fragment source}. Returns list of failure strings."""
    page = """<html><body><pre id=o>pending</pre><script>
const S=__DATA__;
const out=[];
try{
const gl=document.createElement('canvas').getContext('webgl');
if(!gl){out.push('NOGL')}else{
const vs=gl.createShader(gl.VERTEX_SHADER);
gl.shaderSource(vs,'attribute vec4 a;varying vec2 vUv;void main(){vUv=a.xy;gl_Position=a;}');gl.compileShader(vs);
for(const k in S){const sh=gl.createShader(gl.FRAGMENT_SHADER);gl.shaderSource(sh,S[k]);gl.compileShader(sh);
 if(!gl.getShaderParameter(sh,gl.COMPILE_STATUS)){out.push('FAIL '+k+': '+gl.getShaderInfoLog(sh));continue;}
 const p=gl.createProgram();gl.attachShader(p,vs);gl.attachShader(p,sh);gl.linkProgram(p);
 if(!gl.getProgramParameter(p,gl.LINK_STATUS))out.push('LINKFAIL '+k+': '+gl.getProgramInfoLog(p));
 gl.deleteProgram(p);gl.deleteShader(sh);}
}}catch(e){out.push('EXC '+e)}
document.getElementById('o').textContent='DONE\\n'+out.join('\\n');
</script></body></html>""".replace("__DATA__", json.dumps(programs).replace("</", "<\\/"))
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "t.html")
        open(path, "w").write(page)
        res = subprocess.run([SHELL, "--no-sandbox", "--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
                              "--virtual-time-budget=20000", "--dump-dom", "file://" + path], capture_output=True, text=True, timeout=300)
    m = re.search(r'<pre id="o">(.*?)</pre>', res.stdout, re.S)
    text = html.unescape(m.group(1)) if m else "NOPAGE"
    assert text.startswith("DONE"), text[:400]
    return [l for l in text.split("\n")[1:] if l.strip()]


def full_source(params, body):
    hdr = open(os.path.join(RAW, "effect_header.glsl")).read()
    ftr = open(os.path.join(RAW, "effect_footer.glsl")).read()
    return hdr + "\n" + "\n".join("uniform float p_%s;" % p[0] for p in params) + "\n" + body + "\n" + ftr


if __name__ == "__main__":
    import shaders
    fails = check({k: full_source(p, b) for k, (p, b) in shaders.S.items()})
    print("\n".join(fails) or "all %d shaders compile" % len(shaders.S))
