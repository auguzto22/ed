# recly-tracker

Reconstrução em Rust do **rastreamento de movimento** e do **sistema de máscara** do editor
Recly. É uma biblioteca para usar no app (via API C/NDK) e também uma ferramenta de linha de
comando que rastreia alvos em vídeos e aplica máscaras que seguem o movimento.

Usa apenas a biblioteca padrão do Rust: compila offline, sem baixar nada, e no NDK do Android.

## O que mudou em relação ao rastreador atual

O `TemplateMotionTracker.kt` compara um template de luminância 24×24 por erro absoluto, numa
grade fixa de 22% do quadro, e prevê com a última velocidade. O novo rastreador:

| Recurso | Antigo (Kotlin) | Novo (Rust) |
|---|---|---|
| Aparência | só luminância | luminância + gradiente + 2 canais de cor, com peso automático por canal |
| Comparação | erro absoluto normalizado | ZNCC (invariante a brilho e contraste) com imagens integrais |
| Busca | grade fixa de 22% do quadro | janela que cresce/encolhe com a incerteza do filtro de Kalman, coarse-to-fine, subpixel |
| Movimento | média da última velocidade | Kalman (x, y, escala) com ruído adaptativo + suavizador RTS no final |
| Oclusão | segura a última posição | segue a trajetória prevista, casamento por metades na oclusão parcial, busca de resgate |
| Alvo perdido | fica parado | redetecção global no quadro inteiro (âncora + banco de aparências) |
| Distratores | segue o mais parecido | prior de movimento + *gating* estatístico |
| Deriva | template adaptativo sem proteção | só aprende quando o patch ainda se parece com a âncora |
| Vários alvos | um por vez | vários em paralelo, compartilhando a pirâmide do quadro |

### Resultados medidos

`cargo run --release --example benchmark` (480×270, 180 quadros por cena; o "antigo" é um port
fiel do Kotlin, com os mesmos parâmetros do app). Sucesso = quadros com IoU ≥ 0,5.

| Cena | Antigo | Novo | Erro do centro (novo) |
|---|---|---|---|
| Movimento rápido | 97% | **100%** | 0,5 px |
| Mudança de luz | 98% | **100%** | 0,7 px |
| Mudança de escala | 99% | **100%** | 0,5 px |
| Oclusão total | 97% | **98%** | 1,0 px |
| Objeto idêntico por perto | 58% | **100%** | 0,6 px |
| Ruído forte | 2% | **100%** | 1,6 px |
| Câmera tremida | 84% | **100%** | 0,8 px |
| Tudo junto | 1% | **100%** | 1,2 px |
| **Média** | **67%** | **~100%** | **0,9 px** (antigo: 40,7 px) |

Custo: cerca de 9,5 ms por quadro de 480×270 em 2 núcleos, contra 4,5 ms do antigo. É o preço
de quatro canais, pirâmide e redetecção, e ainda é muito mais rápido que tempo real na resolução
de rastreamento.

Num vídeo H.264 1280×720 de 10 s (fundo fractal em zoom contínuo, alvo com trajetória
conhecida), o erro médio do centro foi de 1,6 px (máximo de 3 px) e o IoU médio, 0,977.

## Linha de comando

```bash
cargo build --release
B=target/release/recly-track

# Rastrear e borrar um alvo (caixa em pixels: x,y,largura,altura no quadro inicial)
$B run video.mp4 --region-px 540,285,200,150 --effect blur -o borrado.mp4

# Fundo borrado, pessoa nítida (máscara invertida), mostrando a caixa rastreada
$B run video.mp4 --region 0.5,0.45,0.2,0.5 --invert --effect blur --show-track -o retrato.mp4

# Dois alvos ao mesmo tempo, pixelizados, a partir do segundo 3
$B run video.mp4 --start 3 --region-px 100,80,60,60 --region-px 400,90,70,70 --effect pixelate -o out.mp4

# Só rastrear (JSON no formato do app) + vídeo de conferência
$B track video.mp4 --region 0.3,0.4,0.1,0.15 -o tracks.json --preview conferencia.mp4

# Aplicar máscaras salvas (por exemplo, editadas no app) a um vídeo
$B render video.mp4 --mask mascaras.json --tracks tracks.json --effect cutout --color #00FF00 -o out.mp4

# Demonstração com cena sintética difícil
$B demo --scene tudo -o demo.mp4
```

Efeitos: `blur`, `pixelate`, `solid`, `cutout` (contrato exato do app: fora da máscara vira
fundo), `highlight`, `outline`. Formas: `rectangle`, `circle`, `ellipse`, `linear`, `mirror`,
`heart`, `star` (e `CUSTOM_PATH` via JSON). `recly-track --help` lista todas as opções.

O áudio do vídeo original é mantido. Por padrão o rastreamento roda numa cópia de 640 px de
largura (rápido e suficiente); as máscaras são aplicadas na resolução original.

## Biblioteca

```rust
use recly_tracker::{FrameFeatures, PixelFormat, Tracker, TrackerConfig, TrackingRegion};

let tracker = Tracker::new(TrackerConfig::default())?;
let first = FrameFeatures::from_pixels(&rgb0, w, h, w * 3, PixelFormat::Rgb24);
let mut session = tracker.start(&first, 0, TrackingRegion::new(0.5, 0.5, 0.2, 0.2)?);
for (t, rgb) in frames {
    let f = FrameFeatures::from_pixels(&rgb, w, h, w * 3, PixelFormat::Rgb24);
    let point = session.update(&f, t); // TrackingPoint online (com confiança)
}
let track = session.finish(None, true); // suavizado, ≤ 2000 pontos
let mask = mask.at(time_us, Some(&track)); // mesma regra do MaskState.at do Kotlin
```

Módulos: `tracker` (rastreador), `mask` (formas, keyframes, easing, SDF igual ao GLSL do app),
`render` (efeitos), `video` (ffmpeg), `json` e `codec` (formatos do app), `ffi` (API C),
`synth` e `legacy` (cenas de teste e o port do rastreador antigo, para comparação).

## Compatibilidade com o app

- **Coordenadas**: normalizadas ao quadro de origem não transformado, y para baixo — o mesmo
  "espaço de máscara" de `TrackingSpace.kt`. O track move a máscara *relativamente* ao primeiro
  ponto, como em `MaskState.at`.
- **Máscara**: `MaskState::layer_alpha` reproduz `reclyLayerMaskAlpha` (aspecto, rotação,
  expansão, feather, inversão, opacidade, coração, estrela e polígono).
- **JSON**: mesmos nomes de campos e enums das classes Kotlin (`centerX`, `trackingTrackId`,
  `"ELLIPSE"`, `"EASE_IN_OUT"`…).
- **Binário**: `codec::write_track`/`write_mask` geram exatamente os bytes de
  `TrackingCodec`/`LayerMaskCodec` (`DataOutputStream`, schema ≥ 24).
- **Limites**: tracks com até 2000 pontos (excedentes são reduzidos preservando a forma do
  movimento), até 8 alvos por vídeo, ids no padrão `[a-zA-Z0-9-]{1,80}`.

## Android

`include/recly_tracker.h` expõe `recly_tracker_new/push/finish/free` (aceita `RGBA8888`, o
layout de um `Bitmap` ARGB_8888, com stride) e `recly_mask_rasterize`. Para gerar as `.so`:

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../app/src/main/jniLibs build --release
```

Falta escrever o shim JNI que chama essas funções a partir de `TrackingTools.kt`.

## Testes

```bash
cargo test --release              # 38 testes: unidades + qualidade do rastreamento
cargo run --release --example benchmark
```

Os testes de qualidade falham se qualquer cena cair abaixo de 93% de acerto, se o alvo não for
recuperado no fim, ou se o novo rastreador não superar o antigo com folga.

## Limitações conhecidas

- Não estima rotação no plano (um alvo girando muito tende a perder confiança e é recuperado
  pela redetecção quando volta a uma aparência conhecida).
- Os tempos dos quadros vêm de `índice / fps`; em vídeos com taxa variável há pequeno desvio.
- A seleção do alvo no CLI é numérica (no app ela vem do preview).
