# Relatório de andamento — não é aceite de correção

A tarefa NÃO está concluída. Não houve medição em aparelho, correção do gargalo nem
comprovação de melhoria visual. ADB não detectou aparelho; arquivo/projeto da ocorrência
não foi identificado. A alteração entregue é instrumentação temporária opt-in, além da
auditoria. Não foi alterado Motion 3D nem aplicado limite de FPS/cache adicional.

| Item solicitado | Evidência/status |
|---|---|
| 1. Causa principal | Não determinada por medição. |
| 2. Causas secundárias | Hipóteses: cadência/export frame-drop, concorrência/reconfiguração, redraw UI. Ver audit.md. |
| 3. Categoria do gargalo | Não classificada; Compose não participa deste editor. UI/Media3/GPU/GC/I/O/locks ainda precisam ser isolados. |
| 4. Recomposition antes/depois | Não aplicável: Views/Canvas. Frequência real de redraw ainda não medida. |
| 5. Composables afetados | Nenhum neste caminho. TimelineView, TextHandlesView, StickerHandlesView, VideoLayerHandlesView invalidam via posição. |
| 6. Composition rebuilds | Antes/depois não medidos; SET_COMPOSITION rastreado. |
| 7. prepare | Antes/depois não medidos; PREPARE rastreado com reason. |
| 8. seek durante playback | Antes/depois não medidos; existe seek automático de boundary guard. |
| 9. Surface recreation | Antes/depois não medidos; callbacks rastreados. |
| 10. Decoder recreation | Antes/depois não medidos; analytics init/release rastreados. |
| 11. Average frame time | Sem amostras de aparelho. |
| 12. p50 | Sem amostras de aparelho. |
| 13. p95 | Sem amostras de aparelho; p95 antigo é seek, não frame time. |
| 14. p99 | Sem amostras de aparelho. |
| 15. Max | Sem amostras de aparelho. |
| 16. Dropped frames | Sem amostras de aparelho. |
| 17. Janky frames | Sem amostras de aparelho; jank UI não substitui SurfaceFlinger. |
| 18. GC count | Sem amostras de aparelho; novo coletor calcula delta da janela. |
| 19. Allocations removidas | Nenhuma; correção depende do profiling. |
| 20. Shader compilations | Sem contagem real; tempo de construção Studio/Package instrumentado. Não cobre shaders internos Media3. |
| 21. GPU passes | Cadeia lógica mapeada em audit.md; quantidade/custo GPU reais pendentes. |
| 22. Cache/preload | Thumbnail e waveform agendam misses em workers durante playback; impacto não medido. |
| 23. I/O | Extração de thumbs, waveform RAM/disco, LUT/stickers, proxy e save existentes. Autosave fora da main. Correlação com stalls pendente. |
| 24. Arquivos modificados | Lista abaixo. |
| 25. Testes executados | Ver seção de validação abaixo. |
| 26. Surface-only | Não executado; harness A–F pendente. |
| 27. Editor completo | Não executado em aparelho. |
| 28. Bugs restantes | Queixa original não resolvida. Instrumentação parcial não fornece scanout/decoder output/frame available. |
| 29. Próximos gargalos | Sem ranking medido. Priorizar baseline de um vídeo 1080p30, comparação direta e captura Perfetto. |

## Arquivos

Novos:
- editor-engine/src/main/java/com/termex/replay15/editor/core/PacingSamples.kt
- editor-engine/src/main/java/com/termex/replay15/editor/core/PreviewPacingProbe.kt
- editor-engine/src/test/java/com/termex/replay15/editor/PacingSamplesTest.kt
- docs/preview-pacing/{audit,measurement,status}.md

Modificados (somente hooks de diagnóstico):
- editor-app/src/main/java/com/termex/replay15/editor/ui/EditorActivity.kt
- editor-engine/src/main/java/com/termex/replay15/editor/preview/EditorPreview.kt
- editor-engine/src/main/java/com/termex/replay15/editor/preview/TextHandlesView.kt
- editor-engine/src/main/java/com/termex/replay15/editor/preview/StickerHandlesView.kt
- editor-engine/src/main/java/com/termex/replay15/editor/preview/VideoLayerHandlesView.kt
- editor-engine/src/main/java/com/termex/replay15/editor/timeline/TimelineView.kt
- editor-engine/src/main/java/com/termex/replay15/editor/render/StudioEffect.kt
- editor-engine/src/main/java/com/termex/replay15/editor/render/PackageEffect.kt

Backups anteriores aos hooks: /tmp/recly-pacing-before (sessão atual).

## Validação concluída no host

Comando retomado após a sessão de build anterior ser interrompida sem resultado final:

```sh
JAVA_HOME=/home/jn/.cache/recly-pacing-jdk ./gradlew :editor-app:assembleDebug :editor-engine:testDebugUnitTest :editor-app:testDebugUnitTest --offline --max-workers=2 --console=plain
```

Resultado da execução retomada: BUILD SUCCESSFUL em 4m55s.
- editor-engine: 234 testes, 0 falhas, 0 erros, 0 ignorados (34 suítes).
- editor-app: 1 teste, 0 falhas, 0 erros, 0 ignorados.
- PacingSamplesTest: 2 testes incluídos nos 234, ambos passaram.
- APK atualizado: editor-app/build/outputs/apk/debug/editor-app-debug.apk (15:51 local).
- Relatórios XML/HTML: editor-engine/build/test-results/testDebugUnitTest e
  editor-engine/build/reports/tests/testDebugUnitTest; equivalentes em editor-app.
- Log da execução: /tmp/recly-pacing-tests.log.
- ADB consultado tanto no ambiente quanto via flatpak-spawn no host: nenhum aparelho.
Estes resultados NÃO comprovam melhoria de frame pacing; nenhuma correção de playback
foi aplicada. Falta medir a baseline e executar o isolamento no mesmo aparelho/mídia.
A suíte existente PreviewOptimizationScenariosTest usa dados simulados/políticas;
mesmo passando, seus nomes A–H NÃO significam execução dos cenários de vídeo em aparelho.
A nova PacingSamplesTest verifica percentis de cauda longa, limites, overwrite e reset.
