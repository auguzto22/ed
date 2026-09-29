#!/usr/bin/env python3
"""Render the production camera shaders with matrices emitted by CameraExecutionTest.
Requires gcc, Mesa EGL/GLES2 and :editor-engine:testDebugUnitTest.
Outputs actual GPU frames under editor-engine/build/camera-gpu/.
"""
import json
import os
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
out = root / 'editor-engine/build/camera-gpu'
out.mkdir(parents=True, exist_ok=True)
preview = (root / 'editor-engine/src/main/java/com/termex/replay15/editor/preview/engine/GpuPreviewRenderer.kt').read_text()
export = (root / 'editor-engine/src/main/java/com/termex/replay15/editor/render/SceneCameraEffect.kt').read_text()
vertex = re.search(r'private const val VERTEX="([^"]+)"', preview)[1]
fragment = re.search(r'private const val OVERLAY="([^"]+)"', preview)[1]
export_shaders = re.findall(r'"((?:attribute vec4|precision mediump)[^"\n]+)"', export)
assert len(export_shaders) == 2
(out / 'shaders.h').write_text('\n'.join(f'const char *{name} = {json.dumps(shader)};' for name, shader in zip(
    ['preview_vertex', 'preview_fragment', 'export_vertex', 'export_fragment'], [vertex, fragment, *export_shaders])))
source = root / 'tools/test_camera_gpu.c'
subprocess.run(['gcc', str(source), '-I', str(out), '-o', str(out / 'camera-test'), '-lEGL', '-lGLESv2', '-lm'], check=True)
subprocess.run([str(out / 'camera-test'), str(root / 'editor-engine/build/camera-gpu-matrices.txt'), str(out)],
               env={**os.environ, 'EGL_PLATFORM': 'surfaceless', 'LIBGL_ALWAYS_SOFTWARE': '1'}, check=True)
