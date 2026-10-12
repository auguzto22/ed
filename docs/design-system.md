# Recly — sistema visual

Editor de vídeo, não gravador. Plano, escuro, sem vidro nem gradiente.

## Cores (`EditorStyle` em `editor-engine/.../ui/EditorWidgets.kt`, espelhadas em `res/values/colors.xml`)

| Token | Hex | Uso |
|---|---|---|
| `BG` | `#121214` | chão de todas as telas |
| `CARD` / `PANEL` | `#1C1C1F` | cartões, botões secundários |
| `CARD_ELEVATED` | `#26262A` | superfícies sobre cartão |
| `TIMELINE` | `#1A1A1D` | área da linha do tempo |
| `BORDER` | `#2C2C30` | linhas de 1.5dp |
| `BORDER_LIGHT` | `#4A4A50` | linha em hover / alça |
| `INK` | `#EDEBE6` | texto principal (branco quente) |
| `MUTED` / `DIM` | `#9A968C` / `#6E6B64` | texto secundário / régua |
| `PRIMARY` | `#FF5A36` | ação principal (Exportar, Novo projeto, playhead, clipe selecionado) |
| `ACCENT` | `#FFD23F` | seleção e legendas |
| `BLUE` / `BLUE_DEEP` / `BLUE_LIGHT` | `#7F92B8` / `#4F6290` / `#C9D3E8` | faixa de vídeo, Auto Edit, chips selecionados, "Analisar" |
| `ON_ACCENT` | `#141414` | texto sobre coral/amarelo/claro |

## Tipografia (`ReclyType`)

- `display` — Montserrat 800 (títulos, nome do projeto, rótulos dos blocos).
- `body(peso)` — Outfit 400–700 (texto corrido, botões).
- `mono` — Space Mono (timecode, duração).

## Formas

- Linha de 1.5dp (`EditorStyle.STROKE`), cantos 10dp (controles), 12dp (tiles), 14dp (cartões), 20dp (topo das folhas).
- Alvos de toque ≥ 44dp.
- Helpers: `reclySurface()`, `action()`, `pillButton()`, `heading()`, `toolAction()`.

## Telas

- **Início** (`activity_editor_library.xml`): Novo projeto (coral), Auto Edit (azul), Analisar vídeo que vende, Recentes em grade 2×. Nada de Gravações/Replays.
- **Editor**: topo com Voltar · nome + formato · Analisar · Exportar; transporte com relógio mono, play central claro, desfazer/refazer; barra de ferramentas em tiles 44dp com rótulo, Auto Edit em coral.
- **Auto Edit**: chips de estilo/intensidade/limpeza, cartão de opções com caixas, CTA "Montar minha edição".
