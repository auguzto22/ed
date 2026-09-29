import json
import os

catalog_path = "app/src/main/assets/editor/transitions.json"
if not os.path.exists(catalog_path):
    catalog_path = "src/main/assets/editor/transitions.json"

with open(catalog_path) as f:
    transitions = json.load(f)

print(f"Total transitions in catalog: {len(transitions)}")

by_engine = {}
by_category = {}
for t in transitions:
    eng = t.get("engine", "BLEND")
    cat = t.get("category", "Basic")
    by_engine.setdefault(eng, []).append(t["id"])
    by_category.setdefault(cat, []).append(t["id"])

print("\n--- By Engine ---")
for eng, ids in sorted(by_engine.items()):
    print(f"{eng} ({len(ids)}): {', '.join(ids[:5])}...")

print("\n--- By Category ---")
for cat, ids in sorted(by_category.items()):
    print(f"{cat} ({len(ids)}): {', '.join(ids[:5])}...")

shaders_dir = "app/src/main/assets/editor/transitions"
if not os.path.exists(shaders_dir):
    shaders_dir = "src/main/assets/editor/transitions"

shaders = [f for f in os.listdir(shaders_dir) if f.endswith(".frag")]
print(f"\nTotal GLSL transition shaders: {len(shaders)}")
for s in sorted(shaders):
    print(f" - {s}")
