# Geradores da biblioteca de transições, filtros, animações e elementos

Scripts usados para montar e validar a biblioteca. Eles usam um diretório de trabalho (`S` no topo de
cada script); ajuste antes de rodar.

| Script | O que faz |
|---|---|
| `convert_gl.py` | Converte os `.glsl` do gl-transitions para o formato `reclyTransition` (uniforms float → parâmetros `p_*`). |
| `integrate_transitions.py` | Adiciona as convertidas em `transitions.json` com categoria, duração, autor e licença. |
| `harness.py` | Compila e renderiza shaders em WebGL1 (GLSL ES 1.0, o mesmo do Android) num Chromium headless. |
| `check_transitions.py` | Valida cada transição: compila, progresso 0 = clipe A, progresso 1 = clipe B. |
| `atlas.py` | Renderiza o meio de cada transição e gera `transitions_atlas_NN.webp` + `preview_registry.json`. A ordem precisa bater com a lista em `AtlasPreviewManager`. |
| `filters.py` | Cria os filtros de cor (categoria Color) como presets de `recly_color_grade.frag` e `recly_color_style.frag`. |
| `check_effects.py` | Compila e renderiza os filtros novos. |
| `lottie_gen.py` | Gera os elementos Lottie vetoriais (primeira camada = topo). |
| `lottie_preview.py` | Rasterizador mínimo para conferir a geometria dos Lottie. |

Requisitos: Python 3 com Playwright, Pillow, NumPy e Matplotlib; Chromium do Playwright.
