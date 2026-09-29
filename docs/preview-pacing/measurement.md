# Instrumentação temporária — como medir

A instrumentação é opt-in: abrir EditorActivity em build debug com extra booleano
`profile_preview=true`, mantendo o extra `project` do projeto a investigar. Ela não altera
qualidade, FPS, player, surface, cache ou clock de playback. Remover após a investigação.

Exemplo, após instalar o APK e identificar o ID do projeto (não usar um ID inventado):

```sh
adb shell am start -n com.recly.editor/com.termex.replay15.editor.ui.EditorActivity --es project ID_REAL --ez profile_preview true
adb logcat -v monotonic ReclyPacing:I ReclyPreview:D ReclyPreviewPerf:D '*:S'
```

Iniciar playback pela UI. Pausar para fechar a janela e imprimir distribuições. Cada
transição isPlaying false→true abre uma janela nova. Buffering fecha a janela; portanto
preservar TODOS os eventos begin/report e logs de state, inclusive pausas de buffering.
Não comparar apenas a melhor janela. Startup continua nos logs antigos, separado.

Medidas novas:

- PTS da mídia, releaseTimeNs programado, callback VSYNC e player position em counters
  Android Trace (capturar `atrace_apps: com.recly.editor` no Perfetto).
- Intervalos de callbacks de vídeo, PTS e release programado: avg, p50/90/95/99, max,
  contagens >4/8/16,67/33,33/50/100 ms. Não interpretar intervalo normal de 33 ms de
  conteúdo 30 FPS como processamento lento.
- FrameMetrics TOTAL_DURATION da janela, GPU_DURATION da UI em API31+, jank conforme
  DEADLINE (fallback período do display em API29/30), contagem de callbacks perdidos.
- Draws da timeline e três handles, updatePosition, submissão CPU Studio/Package:
  distribuições e quantidade (dividir samples por duração da janela para frequência).
- prepare, seek, setComposition, release, stop, eventos de player/surface/decoder:
  timestamps, reason/detail e contadores por janela.
- GC delta, heap Java usado, native heap, dropped frames reportados pelo Media3.
- Buffers limitados a 65.536 amostras por série e 8.192 por estágio; `overwritten` indica
  que os percentis só descrevem a cauda retida. Nenhum logcat por frame novo.

Limites explícitos:

- VideoFrameMetadataListener NÃO é callback de frame apresentado ou saída exata do decoder.
  `scheduledReleaseInterval_NOT_PRESENTED` é só agendamento.
- FrameMetrics da Window NÃO mede cadência de apresentação do vídeo no SurfaceView.
- submitCPU mede CPU/submissão GL, não conclusão da GPU; não existe glFinish invasivo.
- Ainda faltam hooks internos Media3 para decoder output/frame available; usar Perfetto
  com FrameTimeline/SurfaceFlinger, sched_switch/sched_waking, gfx/view, dalvik e memória
  no aparelho compatível. Correlacionar timestamps e track do SurfaceView correto.
- Contagem de GC não localiza eventos; correlação temporal requer trace dalvik/ART.
- GPU passes reais, locks, allocations por tipo, memória graphics/PSS, shader compile
  duration e VFR da fonte ainda não foram medidos. Não substituir por números estimados.
- As métricas antigas continuam inalteradas para preservar o comportamento da baseline;
  a política antiga de qualidade pode reagir a intervalos de callback. Registrar tier.
- Esta primeira instrumentação não implementa o harness de isolamento A–F. Esses testes
  e um build profile/release sem debugger ainda precisam ser preparados e executados.

Protocolo antes/depois: mesmo aparelho, mídia, timestamps de início/fim, qualidade,
refresh rate e condições térmicas. Registrar primeiro frame separado de steady-state.
Capturar source PTS via ffprobe no arquivo original; não converter a mídia para o teste.
Comparar vídeo simples com player direto e composição antes de atribuir falha ao editor.
Só então corrigir uma causa, repetir as capturas e revisar o frame pacing visual.
