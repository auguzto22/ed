import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import stickers, validate

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "editor-engine/src/main/assets/editor/lottie/lib")
KT = os.path.join(ROOT, "editor-engine/src/main/java/com/termex/replay15/editor/stickers/GeneratedStickers.kt")


def main():
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    lines = []
    for sid, name, cat, comp, tags in stickers.stickers:
        validate.lottie(comp, sid)
        with open(os.path.join(OUT, sid + ".json"), "w") as fh:
            json.dump(comp, fh, separators=(",", ":"))
        tag_s = ", ".join(json.dumps(t, ensure_ascii=False) for t in sorted(set(tags)))
        lines.append(f'        d("{sid}", {json.dumps(name, ensure_ascii=False)}, StickerCategory.{cat}, {tag_s}),')
    kt = f'''package com.termex.replay15.editor.stickers

/** GERADO por tools/library/gen_stickers.py. Nao edite a mao. */
internal object GeneratedStickers {{
    private fun d(id: String, name: String, category: StickerCategory, vararg tags: String) = StickerDef(
        id = id,
        name = name,
        category = category,
        uri = "asset:///editor/lottie/lib/$id.json",
        defaultDurationUs = 2_000_000L,
        defaultSize = 0.26f,
        author = "Recly Original",
        license = "Recly original",
        licenseUrl = "",
        sourceUrl = "",
        tags = tags.toList(),
    )

    val all: List<StickerDef> = listOf(
{chr(10).join(lines)}
    )
}}
'''
    open(KT, "w").write(kt)
    print(len(lines), "stickers")

main()
