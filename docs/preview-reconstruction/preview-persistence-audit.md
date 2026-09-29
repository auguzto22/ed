# Diagnóstico do preview persistente — 2026-09-27

Inspeção anterior às correções: o preview interativo usa EditorPreviewEngine →
VideoDecoderManager → VideoDecoderSession (MediaCodec/MediaExtractor) →
GpuPreviewRenderer (SurfaceTexture/OES, textura 2D persistente, EGL/SurfaceView).
Media3 1.11.1 está nas dependências, mas ProjectComposition/MotionMatrix3D pertencem
à exportação. Não há CompositionPlayer, MultipleInputVideoGraph, replace de
Composition ou experimentalRedrawLastFrame no preview atual.

## Mapa de responsabilidades

- EditorActivity.buildScreen cria uma SurfaceView e EditorPreviewEngine; onDestroy libera.
- previewEdit chama update desde o primeiro movimento; cancelamento restaura o projeto.
- EditorChangeClassifier classifica transformações/efeitos/textos como RENDER_ONLY.
- EditorPreviewEngine.update avalia PreviewRenderState; render-only não pede decoder.
- RenderStateEvaluator calcula movimento/keyframes; GpuPreviewRenderer.drawLayer envia matrix/uniforms.
- VideoDecoderManager.create cria sessões; GpuPreviewRenderer.createInput cria SurfaceTexture/Surface.
- VideoDecoderSession.open configura MediaCodec com a Surface; step faz seek/flush.
- GpuPreviewRenderer.attach/detachWindow gerenciam EGLWindowSurface.
- requestRender redesenha texturas 2D existentes, inclusive pausado; nenhum seek é necessário.
- VideoLayerHandlesView.onDraw desenha UI independente da Surface de vídeo.

## Causas confirmadas por leitura de código

1. VideoDecoderManager.update envia Target somente quando decoder != null. createInput
   é assíncrono e seu callback não reenvia o Target. Abrir/seek pausado pode ficar sem
   nenhum frame até outro evento. Correção: guardar último Target e entregar ao ficar pronto.
2. remove fecha codec assincronamente, enquanto create pode reutilizar o mesmo slot.
   O callback antigo removeInput(key) pode destruir a Surface nova. Correção: serializar
   aposentadoria/criação por chave e invalidar callbacks por identidade da sessão.
3. GpuPreviewRenderer.draw limpa e apresenta o fundo antes de verificar disponibilidade
   de texturas. Os handles não dependem disso. Correção: não apresentar fundo como se
   fosse frame válido enquanto uma camada de vídeo solicitada ainda está carregando.
4. attach recria EGLWindowSurface também em surfaceChanged com a mesma Surface.
   Correção: reusar attachment e apenas atualizar dimensões/redesenhar.
5. requestRender mantém flag true durante draw: uma atualização concorrente pode ser
   perdida no finally. Correção: consumir a marca antes de desenhar e conflar no vsync.
6. Passes de cópia/efeitos/transições herdam blending do framebuffer final. Correção:
   passes intermediários sobrescrevem pixels; composição final habilita blending.
7. applyEffects usa índice original para ping-pong; um shader inválido entre dois
   válidos pode fazer o próximo ler e escrever a mesma textura. Correção: alternar
   somente após passes executados; cache de falhas evita recompilar a cada frame.

Não se observou execução em dispositivo durante o diagnóstico; estes são defeitos
verificáveis no código, sem atribuir todos os sintomas a uma única causa comprovada em runtime.
8. consume usava pacing de reprodução também pausado: um keyframe anterior ao playhead
   no fast scrub era marcado como atrasado e nunca pedia redraw. Agora pausado sempre
   redesenha após receber textura; reprodução mantém o scheduler existente.
9. TextOverlayRenderer rejeitava rasterização pela revisão global do projeto mesmo
   quando só a posição havia mudado. Aceitação agora depende de conteúdo e dimensões.

## Implementação e limites

- Redraw: Choreographer no thread GL, estado imutável mais recente; marca de agendamento
  consumida antes do draw para não perder uma atualização concorrente.
- Seek: Choreographer no main; vários pedidos antes do vsync produzem um único pedido
  ao decoder. endScrub cancela o callback pendente e executa o destino preciso final.
- Operações EGL/mapas/texturas pertencem ao thread GL. O decoder recebe sua Surface
  diretamente; não consulta um LinkedHashMap de outro thread. Callback SurfaceTexture
  também verifica identidade, impedindo um callback antigo de consumir o slot novo.
- Encerramento: codec para antes de liberar Surface; renderer encerra após sessões;
  trabalho de bitmaps é drenado antes de liberar memória/GL. surfaceDestroyed aguarda
  o desanexo EGL, conforme o contrato do SurfaceHolder.
- Renderização preserva o buffer apresentado quando nenhuma textura da composição
  solicitada está disponível. Uma composição intencionalmente vazia continua mostrando
  o fundo. A proteção não equivale a guardar um snapshot completo do compositor após
  perda real da Surface/contexto EGL.
- Um shader defeituoso usa fallback existente (pass-through para efeito; dissolve para
  transição), com erro identificado e cache de falha por sessão. Shaders válidos continuam
  ativos. Uniforms de transição continuam passando pelo TransitionShaderBuilder existente.
- Logs ReclyPreview incluem tempo monotônico. FRAME_* só aparece com a propriedade Java
  `recly.preview.traceFrames=true`; métricas agregadas continuam disponíveis no debug.

Referências públicas conferidas: [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec),
[Choreographer](https://developer.android.com/reference/android/view/Choreographer).
Nenhuma API de redraw de Media3 foi introduzida: este preview usa EGL próprio.

## Matriz de validação em dispositivo (pendente)

O ambiente desta tarefa não tem dispositivo ADB nem Android Emulator instalado.
Não confundir testes JVM/compilação com comprovação visual de GPU/Surface.

| Cenário obrigatório | Verificação no dispositivo |
| --- | --- |
| Abrir projeto pausado | Primeiro frame sem precisar tocar Play |
| Play e pause | Frame continua visível; decoder não é recriado |
| Mover playhead / scrub rápido | Último destino vence; liberação encerra com seek preciso |
| Mover, escalar e rotacionar clipe | PROJECT_UPDATE RENDER_ONLY, sem DECODER_CREATE/SEEK_EXECUTE |
| Editar pausado | Última transformação aparece sem Play/seek auxiliar |
| Editar em reprodução | Movimento contínuo, mesmo decoder/Surface |
| Aplicar/remover transição | Ambos os inputs presentes; sem Surface inválida |
| Aplicar efeito / editar parâmetro | Shader válido ativo; alteração por uniform |
| Selecionar/deselecionar | Vídeo permanece; handles independentes |
| Texto | Rasterização aceita ao mover; GPU atualiza posição |
| Keyframe | Geometria atualizada sem recriar decoder |
| Sair/voltar ao editor | Sessões encerram antes das Surfaces; nova abertura apresenta frame |

Teste instrumentado adicional: PreviewPersistenceInstrumentedTest usa o MP4 de teste
existente, sem View de apresentação (EGL pbuffer). Exercita cold start pausado, 50
pedidos e troca de URI da mesma chave enquanto a sessão antiga encerra. Não substitui
os testes visuais da matriz acima. VideoDecoderSessionInstrumentedTest permanece intacto.

A revisão também impede limpeza de decoders quando a qualidade efetiva não mudou e
contabiliza sessões em encerramento no orçamento, evitando exceder o limite em scrubs
entre fontes. Mudanças reais da resolução de preview ainda podem realocar fontes;
transformações RENDER_ONLY não passam por esse setter.

## Resultado final da validação local

- `:editor-engine:testDebugUnitTest`: **252 testes, zero falhas/erros/ignorados**.
- `:editor-engine:assembleDebugAndroidTest`: APK de testes instrumentados gerado.
- `:editor-app:assembleDebug`: APK debug do editor gerado.
- Build conjunto: **BUILD SUCCESSFUL**, 6m16s. Log: `.gradle/preview-validation.log`.
- A execução final usou `--max-workers=1` e `-Dorg.gradle.jvmargs="-Xmx1280m -Dfile.encoding=UTF-8"`
  para caber na máquina de aproximadamente 4 GB de RAM, sem alterar gradle.properties.
- Testes instrumentados/visuais: **não executados**, pois `adb devices -l` não lista dispositivos
  e o SDK disponível não contém Android Emulator. Não é possível certificar a eliminação
  definitiva de tela preta em GPU real apenas com esses resultados locais.

Os testes novos de classificação cobrem 50 eventos de transformação, commit/cancelamento,
visibilidade de faixa e seleção por bloqueio. Os testes existentes de shaders/transições,
resolução temporal, keyframes, áudio e demais componentes passaram na mesma suíte.
