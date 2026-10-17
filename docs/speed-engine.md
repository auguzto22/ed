# Motor de velocidade do Blitz

Velocidade constante, curvas, câmera lenta, motion blur e áudio retemporizado. A lógica fica em
Rust (`blitz-speed/`, biblioteca `libblitz_speed.so`); o Kotlin só liga o motor às telas do
Android, ao Media3 (prévia e exportação) e ao formato do projeto.

## Onde está cada coisa

| Parte | Rust (`blitz-speed/src`) | Kotlin (ponte) |
|---|---|---|
| Curvas, easing, Bézier, limites | `curve.rs` | `domain/TimeMapping.kt` (`SpeedPoint`) |
| Mapa de tempo timeline ↔ mídia | `timemap.rs` | `ClipTimeMap` (tabela `LongArray`) |
| Presets | `presets.rs` | painel `ui/SpeedTools.kt` |
| Reajuste de textos, legendas, stickers, áudios, keyframes | `warp.rs` | `speed/SpeedEdit.kt` |
| Escolha de técnica por aparelho/situação | `policy.rs` | `speed/MotionPolicy.kt`, `PreviewMotion.kt` |
| Grade de quadros da exportação | `scheduler.rs` | `render/MotionRetimeEffect.kt` (GlEffect do Media3) |
| Optical flow, interpolação, motion blur, acumulação (GLES2) | `gpu/` | `preview/engine/PreviewMotionStage.kt`, `MotionRetimeEffect.kt` |
| Áudio retemporizado da prévia | `audio/` | `preview/engine/audio/AudioDecoderSession.kt` |
| Conferência do MP4 exportado | `validate.rs` | `export/ExportValidator.kt` |
| JNI | `jni.rs`, `api.rs`, `gpu/api.rs`, `audio/api.rs` | `speed/NativeSpeed.kt` |

A JNI é escrita à mão (sem dependências externas): o crate compila offline, no computador
(testes) e no Android. Erros viram `IllegalArgumentException`/`IllegalStateException` no Kotlin;
pânicos são capturados e nunca derrubam o app.

## Tempo

Uma curva é uma lista de até 32 pontos `(tempo da mídia, velocidade)` entre 0,1× e 16×. Cada
trecho sai de um ponto com uma forma: linear, suave, acelerando, freando, entrada e saída,
Bézier (x1, y1, x2, y2) ou degrau. A duração na timeline é a integral exata
`t(s) = ∫ ds / v(s)`:

- trecho constante ou degrau: `Δs / v`;
- linear: forma fechada `Δs · ln(v1/v0) / (v1 − v0)`;
- suave, entrada/saída e Bézier: Gauss–Legendre de 8 pontos adaptativo (abaixo de 0,5 µs de
  diferença contra uma integração de referência nos testes).

O Media3 aplica velocidade por degraus constantes, então o mapa é guardado como degraus numa grade
fixa da mídia (20 ms ou mais, até 2048 degraus por clipe) com a velocidade **média harmônica** de
cada degrau: a duração de cada degrau é exatamente a integral da curva nele. Prévia e exportação
leem a mesma tabela; os testes conferem que a soma do `SpeedProviderMapper` do Media3 e a duração
da prévia diferem no máximo 1 µs. Cortes e splits reaproveitam a velocidade do degrau inteiro,
então as duas metades somam o clipe original.

Ao mudar a velocidade, `SpeedEdit` move tudo o que vive no tempo do projeto pelo mapa
`antigo → mídia → novo` (monótono, não troca ordem): legendas ficam na palavra, efeitos sonoros no
impacto, textos e stickers sobre o mesmo instante, e o que vem depois anda junto (ripple). Keyframes,
efeitos e máscaras do próprio clipe já estão no tempo da mídia e acompanham por construção.
Camadas sobrepostas mudam no lugar e empurram só a própria faixa.

## Câmera lenta: três técnicas diferentes

Quando a mídia não tem quadros suficientes para a cadência de saída (`velocidade × fps da fonte <
fps de saída`), cada quadro de saída é criado entre os dois quadros vizinhos:

- **Econômico (repetir)**: mostra o quadro original mais próximo. Custo zero e nenhum artefato,
  mas o movimento anda em degraus.
- **Mistura de quadros**: funde os vizinhos pela distância no tempo. Some o degrau, mas o que se
  move mais que o próprio tamanho aparece duas vezes (fantasma). O controle de fantasmas puxa para o
  quadro mais próximo onde os dois diferem muito.
- **Optical flow**: estima um vetor de movimento por pixel nos dois sentidos (Lucas–Kanade em
  pirâmide, 7×7, regularizado, suavização bilateral guiada pela luminância), mede a confiança
  (ida e volta + fotométrica), preenche oclusões a partir de vizinhos confiáveis e desenha o quadro
  intermediário por warping para trás nos dois sentidos, com pesos de fechamento. Onde nada
  concorda cai pixel a pixel para o quadro mais próximo, então uma falha parece o modo econômico,
  não uma mancha. Cortes de cena (correlação da luminância + confiança) nunca são interpolados.

**Automático** escolhe por aparelho e situação (`policy.rs`): durante o arraste da agulha sempre
repete (rapidez primeiro); na prévia usa optical flow quando o aparelho tem `highp` no fragment
shader, textura ≥ 2048, RAM suficiente, não está aquecido (status térmico ≥ moderado) e o
controlador de desempenho não reduziu a qualidade; senão mistura, e a interface mostra o motivo.
A exportação ignora a temperatura (usa sempre o melhor que o aparelho suporta) e pode ser
“Econômica” (mistura, flow menor) ou “Alta qualidade” (flow com metade do lado menor até 540 px,
5 níveis, 5 iterações). Sem `highp` real o flow não é confiável e a técnica cai para mistura.

Interpolação por redes neurais (RIFE/FILM) foi avaliada e não entrou: exigiria um runtime de
inferência e modelos de dezenas de MB, e não roda em tempo real na prévia da maioria dos aparelhos.
O pipeline aceita uma etapa assim no futuro, só na exportação.

## Motion blur

Blur real de câmera, não gaussiano: só o que se move é borrado, na direção do próprio movimento.

- **Vetores (padrão)**: usa o flow do par de quadros. Reconstrução por amostras ao longo do
  movimento (no estilo McGuire): máximo de velocidade da vizinhança para objetos que passam por
  cima do fundo, comparação de velocidades para decidir quem cobre quem. O fundo parado continua
  nítido.
- **Obturador**: 90°, 180° (padrão de cinema), 270°, 360°, ou valor livre de 1° a 360° (modo
  avançado). A
  exposição é `ângulo / 360 × intervalo do quadro de saída`, convertida para o tempo da mídia pela
  velocidade do instante — a 0,25× o rastro é 4× menor, como numa câmera lenta de verdade.
- **Intensidade** (0–200 %) e **qualidade** (6, 12 ou 24 amostras; a prévia limita a 6 ou 12).
- **Aceleração**: quando vários quadros caem no obturador aberto, a exportação integra de verdade
  (acumulação). Com vetores, cada amostra cobre o intervalo até a seguinte, então as cópias viram um
  rastro contínuo (no ensaio, 3,4 dB mais perto da exposição real que a soma simples).
- **Animação da camada**: para camadas animadas por keyframes (ou aparelhos sem flow), o blur vem
  da velocidade da camada na tela (`MotionBlurEngine`), uniforme na camada.

## Prévia

O renderizador guarda o quadro anterior de cada camada. Com síntese ou blur por vetores ativos, a
camada é desenhada pelo pipeline do motor a partir do par (anterior, atual) na fase do instante
pedido; tudo na GPU, sem arquivos temporários. Durante o arraste de um controle (curva, régua) a
prévia faz buscas rápidas; ao soltar, uma busca precisa. Logo depois de uma busca, com a prévia
parada, aparece o quadro original mais próximo até existir um par.

Áudio: o motor tem dois processadores, escolhidos por clipe — **manter o tom** (WSOLA: segmentos de
40 ms, 50 % de sobreposição, busca de ±12 ms por correlação) e **tom acompanha** (varispeed com
filtro Lanczos de 4 lóbulos, corte ajustado à velocidade para não criar aliasing) — ou **silenciar**
o clipe enquanto estiver retemporizado. A posição na mídia é medida, não estimada, então rampas
não acumulam atraso; no fim da mídia o que ficou nos buffers é entregue.

## Exportação

O Media3 aplica a velocidade na fonte (`SpeedProvider` do motor). O `MotionRetimeEffect` fica no
começo da cadeia de efeitos do clipe: o agendador decide os quadros na grade absoluta da saída
(evita duplicar ou pular quadros entre clipes), guarda três quadros de histórico e o pipeline
sintetiza cada saída. Máscaras, cor, keyframes, textos e stickers vêm depois e recebem os quadros
novos com os próprios instantes. O último quadro de um clipe cobre o mesmo intervalo que o
anterior, então a duração bate com o mapa. O áudio da exportação usa o processador de velocidade
do Media3 alimentado pelo mesmo mapa (mesmos degraus, mesma duração). Ao terminar, o MP4 é lido
de volta e o motor confere a duração do vídeo (tolerância de 2 quadros + 40 ms) e a sincronia do
áudio (±100 ms); a mensagem final avisa se algo não bater.

## Persistência

Esquema 31 do projeto (`project/RetimeCodec.kt`): easing e Bézier de cada ponto, modo de câmera
lenta, configuração de motion blur e “silenciar retemporizado” por clipe, e a qualidade de movimento
da exportação. Projetos antigos abrem com os padrões (Automático, blur desligado).

## Compilar

Requisitos, além do Android SDK:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
# NDK instalado pelo SDK Manager (ou ANDROID_NDK_HOME)
```

`./gradlew :editor-app:assembleDebug` roda `cargoBuildAndroid` (cargo-ndk, API 29) e empacota as
`.so` como jniLibs geradas. Os testes da JVM (`./gradlew :editor-engine:testDebugUnitTest`)
compilam antes a biblioteca do computador (`cargoBuildHost`) e a encontram por `java.library.path`.

## Testes

- `cd blitz-speed && cargo test` — curvas contra Simpson, limites, mapa invertível, degraus do
  Media3, split, reversão, presets, reajuste, política, agendador, áudio (tom de 0,1× a 8×, rampas
  sem atraso, sem cliques), conferência.
- `cargo test --features gpu-tests` — os shaders de verdade numa GPU virtual (Mesa llvmpipe, EGL
  surfaceless): precisão do flow, interpolação melhor que mistura (com oclusão), cortes, fantasmas,
  direção e comprimento do blur por ângulo do obturador, acumulação, estado GL restaurado.
- `tools/speed-e2e.sh` — ensaio de ponta a ponta com vídeo e áudio reais: gera uma cena a 120 fps,
  entrega 30 fps ao motor, roda câmera lenta, rampa e aceleração pelo caminho da exportação,
  codifica em MP4 e mede. Na câmera lenta 0,25× compara os quadros criados com os quadros reais que
  o motor nunca viu (optical flow 21,6 dB × mistura 17,6 × repetição 16,7 no último ensaio).
- Testes da JVM (`SpeedEngineTest`, `MotionBlurEngineTest`, `TimeMappingTest`,
  `ProjectPersistenceTest`) passam pela JNI de verdade.

## Limitações conhecidas

- Reversão existe no mapa de tempo, mas não está exposta no painel nem na exportação.
- A prévia logo após uma busca (parada) mostra o quadro original até haver um par.
- O pipeline trabalha em RGBA8; vídeos HDR passam pelo efeito em 8 bits por canal.
- Sem `highp` no fragment shader (GPUs antigas), optical flow e blur por vetores caem para mistura
  e para o blur da animação da camada.
- Interpolação por IA (RIFE/FILM) não implementada (ver acima).
