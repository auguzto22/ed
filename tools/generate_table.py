import json

with open("app/src/main/assets/editor/effects.json") as f:
    effects = json.load(f)

reused_ids = {
    "recly_glow", "recly_pixel", "recly_rgb", "recly_scanlines", "recly_shake",
    "recly_vhs", "recly_gaussian", "recly_zoom_pulse", "recly_ripple", "recly_mirror",
    "recly_posterize", "recly_noise", "recly_wave", "recly_directional_blur",
    "recly_flash", "recly_punch_zoom", "recly_zoom_blur", "recly_bloom", "recly_halation",
    "recly_chromatic", "recly_glitch", "recly_neon_edge", "recly_lens", "recly_vignette",
    "recly_film_grain", "recly_flicker", "recly_fisheye", "recly_mosaic", "recly_sharpen", "recly_crt"
}

rows = []
for e in effects:
    eid = e["id"]
    name = e["name"]
    status = "REUSED" if eid in reused_ids else "IMPLEMENTED"
    engine = e.get("engine", "COMPOSITOR")
    multi_pass = e.get("multiPass", False)
    passes = "2 (Multi-pass)" if multi_pass else "1"
    keyframes = "SIM" if e.get("supportsKeyframes", True) else "NÃO"
    temporal = "SIM (Ring buffer)" if (e.get("temporal", False) or engine == "TEMPORAL_TRAIL") else "NÃO"
    preview = "SIM (Realtime + Pausa)"
    export = "SIM (Media3 Transformer)"
    rows.append(f"| {name} (`{eid}`) | {status} | {engine} | {passes} | {keyframes} | {temporal} | {preview} | {export} |")

with open("tools/table_output.md", "w") as out:
    out.write("| EFFECT | STATUS | ENGINE | GPU PASSES | KEYFRAMES | TEMPORAL | PREVIEW | EXPORT |\n")
    out.write("|---|---|---|---|---|---|---|---|\n")
    out.write("\n".join(rows))
    out.write("\n")

print(f"Generated table with {len(rows)} rows.")
