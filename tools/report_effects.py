import json

with open("app/src/main/assets/editor/effects.json") as f:
    effects = json.load(f)

print(f"Total effects in catalog: {len(effects)}")

reused_ids = {
    "recly_glow", "recly_pixel", "recly_rgb", "recly_scanlines", "recly_shake",
    "recly_vhs", "recly_gaussian", "recly_zoom_pulse", "recly_ripple", "recly_mirror",
    "recly_posterize", "recly_noise", "recly_wave", "recly_directional_blur",
    "recly_flash", "recly_punch_zoom", "recly_zoom_blur", "recly_bloom", "recly_halation",
    "recly_chromatic", "recly_glitch", "recly_neon_edge", "recly_lens", "recly_vignette",
    "recly_film_grain", "recly_flicker", "recly_fisheye", "recly_mosaic", "recly_sharpen", "recly_crt"
}

by_engine = {}
for e in effects:
    eng = e.get("engine", "COMPOSITOR")
    by_engine.setdefault(eng, []).append(e["id"])

print("\n--- By Engine ---")
for eng, ids in sorted(by_engine.items()):
    print(f"{eng} ({len(ids)}): {', '.join(ids[:5])}...")

print(f"\nReused existing built-in IDs: {len(reused_ids)}")
print(f"New effect IDs: {len(effects) - len(reused_ids)}")
