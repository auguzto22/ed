# Recly Originals — implemented design specification

Copyright 2026 Recly. Authorship: Recly Type Lab.

No third-party font file, outline, metric, kerning table, curve, or internal table
is used as source material. The editable originals live in `fonts-src/` and
`tools/fonts/build_recly_fonts.py`; all eleven families build to real TrueType
fonts with independently generated outlines.

| Family | Personality | Width / proportions | Contrast / weight | Terminals / corners | Spacing | Recommended use |
|---|---|---|---|---|---|---|
| Recly Sans | neutral humanist, open rhythm | normal, x-height 500 | low, regular | soft diagonal, rounded joins | relaxed proportional | text and captions |
| Recly Wide | geometric and impactful | expanded 142%, x-height 480 | uniform, bold | straight, broad counters | tight display | gaming and titles |
| Recly Poster | dense and direct | condensed 78%, x-height 520 | uniform, bold | square trapezoid modules | compact | huge headlines |
| Recly Pixel | crisp modular | normal grid, x-height 500 | uniform, regular | square pixels | monospaced visual rhythm | small retro captions |
| Recly Arcade | playful retro | slightly expanded, x-height 500 | uniform, regular | separately chamfered modules | open display | videogame titles |
| Recly Mono | technical and calm | fixed 680-unit advance, x-height 500 | low, regular | rounded-square; distinct I/l/1/O/0 | monospaced | HUD, terminal and VHS |
| Recly Signature | flowing and elegant | normal, x-height 470, forward slant | monoline, regular | entry/exit line and soft joins | connected/tight | short signatures |
| Recly Brush | energetic and informal | slightly expanded, x-height 510 | asymmetric/high, heavy | dry angled wedges | active but readable | aggressive display |
| Recly Gothic | contemporary blackletter | slightly condensed, x-height 510 | medium, regular | angular diamond and split terminals | compact | gothic titles |
| Recly Editorial | refined and upright | normal, x-height 480 | high, regular | fine independent wedge serifs | airy | fashion/editorial titles |
| Recly Urban | forward marker/graffiti | expanded 110%, x-height 500 | medium, heavy | marker wedges and offset tags | tight | legible urban titles |

## Metrics and coverage  

All families use 1000 units/em, ascender 900, descender -250, line gap 100,
cap height 700, family-specific x-height, family-specific advance widths and
sidebearings, and explicit kerning for `AV`, `VA`, `To`, `Ta`, `Wa`, `Yo`, `LT`,
and `PA`.

Release coverage includes `A-Z`, `a-z`, `0-9`, the requested punctuation,
Portuguese precomposed characters (including grave, circumflex, tilde, dieresis,
and cedilla variants), bullet and em dash. The build reopens every output with
fontTools and produces a specimen for visual review.
