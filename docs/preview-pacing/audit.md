# Auditoria de playback Recly — 2026-09-22

Status: investigação aberta, sem baseline em aparelho; nenhuma causa principal medida.
Auditoria feita antes das alterações de instrumentação. Projeto ativo: settings.gradle.kts
na raiz, editor-app + editor-engine. Replay15/app é uma cópia fora desse build.
Não há repositório .git na raiz. ADB /home/jn/Android/Sdk/platform-tools/adb disponível,
mas `adb devices -l` não encontrou dispositivo. A mídia/projeto da ocorrência não foi fornecida.

## Caminho atual

EditorActivity (Views, sem Compose) → EditorPreview → CompositionPlayer → Media3
MultipleInputVideoGraph → efeitos por item → TrackCompositor → efeitos de composição
→ SurfaceView/BufferQueue → SurfaceFlinger → display.
O decoder MediaCodec interno do Media3 produz frames com PTS na surface de entrada do
video graph; o processamento GL e a liberação na surface de saída são controlados pelo
Media3. Não existe scheduler de frames implementado pela Activity. A identificação do
codec, hardware/software, profile/level e os tempos internos dependem de execução real.

ProjectComposition constrói sequências de vídeos e um input de imagem para o fundo.
Por item: crop/rotação, GaussianBlur quando configurado, StudioEffect (sempre no preview),
PackageEffect por efeito habilitado, Presentation, MotionMatrix3D e efeitos de transição
quando aplicáveis. Depois do compositor: adjustment effects, TextCanvasOverlay e
FrameDropEffect com FPS de exportação. Quantidade de passes GL efetivos não é igual à
quantidade de objetos Effect: Media3 pode combinar transformações. Precisa capturar.
MotionMatrix3D foi apenas localizado; não será modificado nesta tarefa.

Clock UI: Handler a cada 200 ms → currentPosition → campo position → updatePosition →
TimelineView.positionUs + três Views de handles + clock.text. Timeline invalida o Canvas
inteiro; a coordenada de todos os clips depende da posição (playhead central fixo).
Não há EditorScreen composable, collectAsState, MutableState nem StateFlow nesse caminho.
A frequência nominal é 5 ticks/s, não 60 recomposições/s; frequência efetiva não medida.

## Estrutura, recursos e trabalho concorrente

- EditorChangeClassifier separa parâmetros realtime de estrutura. RealtimeProjectState
  publica snapshots por AtomicReference, lidos pelos shaders. Não há rebuild por tick UI.
- EditorPreview.replace chama setComposition + prepare no mesmo player; queueLoad libera
  player/decoder e reconstrói. Recovery dispara após buffering de 900/1200 ms. Destruição
  da surface libera player. Stop da Activity também libera.
- boundaryGuard (40 ms) executa seek deliberado ao cruzar fronteiras mesmo em STATE_READY.
  Suspeita para descontinuidades em cortes, não prova de jank dentro de um único clip.
- Callback de proxy em EditorActivity substitui composição se preview.isPlaying. Pode
  interromper reprodução quando conversão em background termina; precisa correlacionar.
- ThumbnailCache.get é chamado no desenho; cache miss agenda MediaMetadataRetriever em
  executor único, até 12 pendentes. Não bloqueia main esperando frame, mas pode competir
  por decoder/I/O; não há pausa de geração durante playback nem cancelamento por viewport.
- WaveformCache mantém RAM/disco e executor único, até 4 pendentes. Decodifica PCM fora
  da main; misses podem iniciar durante playback. Não recalcula amostras a cada tick.
- TextCanvasOverlay reutiliza bitmap, canvas e layout; animações podem redesenhar/uploadar.
  Stickers são decodificados na construção do overlay (chamada do build na main).
- Shader programs Studio/Package são criados na construção do programa; uniforms são
  atualizados por frame. LUT faz leitura/bitmap na inicialização. Não foi encontrado
  compile explícito por tick UI. Programas temporais mantêm três history textures.
- GlResourcePool possui criação/reuso/liberação de texturas/FBO; não basta presença de pool
  para comprovar ausência de churn. É preciso capturar GL/driver e chamadas por cenário.
- Autosave: debounce de 1200 ms após edição, snapshot salvo no executor io. Tick não salva.
- Outros workers: AdaptiveProxyManager (um), efeitos/thumbnails (um + coroutines Default),
  AtlasPreviewManager (IO), análise/autoedit/reference, export; atividade real não medida.
- Locks encontrados incluem contadores antigos de latência e janela do controlador;
  espera por lock, GC, allocations e render-thread stalls não foram medidos.
- Alocações visíveis: RectF/Path no desenho da timeline, matriz da transição fade, listas
  de avaliação/consultas. Nenhuma foi removida sem correlação com jank.
- Seek de scrub usa LatestSeekWinsQueue drenada via Choreographer; não é clock de playback.

## Hipóteses prioritárias (não ranking medido)

1. Cadência: FrameDropEffect da exportação também no preview; política antiga confunde
   intervalo de callbacks de vídeo com custo de processamento (30 FPS saudável >25 ms).
2. Concorrência/reconfiguração: thumbnail/waveform/proxy e troca de proxy durante play;
   seeks de fronteira/recovery. Contar operações apenas dentro da janela steady-state.
3. UI: timeline/handles inteiros redesenhados a cada tick; timeline longa amplia custo.

## Métricas antigas não servem como baseline

PREVIEW_PERF p95Ms era p95 de seek. FIRST_FRAME usa callback anterior à apresentação,
não o instante de scanout. Choreographer mede atraso de callbacks da main, não cadência
do SurfaceView. Heap delta não é allocation rate; art.gc.gc-count é acumulado do processo.
VideoFrameMetadataListener informa frame prestes a renderizar e releaseTimeNs programado,
não conclusão do decoder/GPU nem apresentação física.

Referências: https://developer.android.com/reference/androidx/media3/exoplayer/video/VideoFrameMetadataListener
https://developer.android.com/reference/android/view/FrameMetrics

## Gate para correção

Preservar baseline antes de mudar scheduler, efeitos, caches ou UI. Medir o mesmo arquivo
no player simples e no editor, sem debugger, com PTS reais (ffprobe por frame para VFR).
Executar A player+Surface; B +timeline estática; C +posição; D +overlays; E +effects;
F completo. Harness de isolamento ainda não implementado/executado.
Depois testar 1080p30, 1080p60, transform, três efeitos simples, blur/glow, scrub,
5 minutos e timeline longa. As 8 execuções estão pendentes de aparelho/mídia.
