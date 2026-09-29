#!/usr/bin/env python3
"""Compile production transition fragment programs for GLES 2.0 on the host."""
import json
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
assets = root / 'editor-engine/src/main/assets/editor'
raw = root / 'editor-engine/src/main/res/raw'
definitions = json.loads((assets / 'transitions.json').read_text())
header = (raw / 'transition_header.glsl').read_text()
footer = (raw / 'transition_footer.glsl').read_text()
out = root / 'editor-engine/build/transition-shader-check'
out.mkdir(parents=True, exist_ok=True)
failures = []
for definition in definitions:
    body = (assets / 'transitions' / definition['shaderFile']).read_text()
    declared = set(re.findall(r'uniform\s+\w+\s+(\w+)\s*;', body))
    parameters = {param['id'] for param in definition['parameters']}
    parameters.update(re.findall(r'\bp_(\w+)\b', body))
    params = '\n'.join('uniform float p_'+param+';' for param in sorted(parameters) if 'p_'+param not in declared)
    safe_header = '\n'.join(line for line in header.splitlines() if not any('uniform ' in line and re.search(r'\b'+re.escape(name)+r'\b',line) for name in declared))
    source = safe_header + '\n' + params + '\n' + body + '\n' + footer
    path = out / (definition['id']+'.frag')
    path.write_text(source)
    result = subprocess.run(['glslangValidator','-S','frag',str(path)], capture_output=True, text=True)
    if result.returncode: failures.append((definition['id'],result.stdout+result.stderr))
print(f'{len(definitions)-len(failures)}/{len(definitions)} production transition shaders compile')
for name,error in failures: print(name,error[:600])
if failures: raise SystemExit(1)
