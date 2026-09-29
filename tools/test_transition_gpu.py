#!/usr/bin/env python3
"""Compile and render catalog transition shaders using surfaceless Mesa GLES2.
This checks host GLSL pixels, not Android decoder/SurfaceTexture integration.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, str(root / 'tools/test_transition_shaders.py')], check=True)
assets = root / 'editor-engine/src/main/assets/editor'
out = root / 'editor-engine/build/transition-shader-check'
definitions = json.loads((assets / 'transitions.json').read_text())
priority = ['cross_dissolve', 'cube_left', 'cube_right', 'flip_horizontal', 'flip_vertical']
definitions.sort(key=lambda d: priority.index(d['id']) if d['id'] in priority else len(priority))
lines = ['static const char *names[] = {' + ','.join(json.dumps(d['id']) for d in definitions) + '};',
         'static void setDefaults(int effect, GLuint program) { switch(effect) {']
for index, definition in enumerate(definitions):
    params = {p['id']: p['default'] for p in definition['parameters']}
    body = (assets / 'transitions' / definition['shaderFile']).read_text()
    for param in set(re.findall(r'\bp_(\w+)\b', body)) - params.keys():
        params[param] = {'perspective': 1, 'radius': 16, 'softness': .1}.get(param, 1)
    if definition['id'] == 'cross_dissolve':
        params['softness'] = 0
    lines.append(f'case {index}:')
    for name, value in params.items():
        lines.append(f'glUniform1f(glGetUniformLocation(program, "p_{name}"), {float(value):.9f}f);')
    lines.append('break;')
lines.append('}}')
preview = (root / 'editor-engine/src/main/java/com/termex/replay15/editor/preview/engine/GpuPreviewRenderer.kt').read_text()
for name in ['VERTEX', 'DRAW', 'OVERLAY']:
    pattern = r'private const val ' + name + r'=(?:"""(.*?)"""|"([^"\n]+)")'
    match = re.search(pattern, preview, re.DOTALL)
    shader = next(group for group in match.groups() if group is not None)
    lines.append(f'static const char *production_{name.lower()} = {json.dumps(shader)};')
(out / 'transition_cases.h').write_text('\n'.join(lines))
subprocess.run(['gcc', str(root / 'tools/test_transition_gpu.c'), '-I', str(out),
                '-o', str(out / 'test-gpu'), '-lEGL', '-lGLESv2', '-lm'], check=True)
subprocess.run([str(out / 'test-gpu'), str(out), str(out)],
               env={**os.environ, 'EGL_PLATFORM': 'surfaceless', 'LIBGL_ALWAYS_SOFTWARE': '1'}, check=True)
