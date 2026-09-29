# Recly Originals source

Copyright 2026 Recly. Authorship: Recly Type Lab.

The editable source is `tools/fonts/build_recly_fonts.py` plus each family's
`design.json`. The glyph construction grids and every family renderer were drawn
for Recly without loading, tracing, transforming, or extracting any existing font.

Rebuild from the repository root:

```sh
python3 -m venv .font-build
.font-build/bin/pip install -r tools/fonts/requirements.txt
.font-build/bin/python tools/fonts/build_recly_fonts.py
```

TTF output goes to `app/src/main/assets/fonts/`. Development-only PNG specimens
and the machine-readable validation report go to `build/font-specimens/`, which is
not packaged into the Android application.
