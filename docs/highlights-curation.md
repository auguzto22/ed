# Melhores momentos: curadoria do ClipStudio no Recly

O analisador de "Melhores momentos" usa a curadoria que foi desenvolvida e medida no ClipStudio
(`auguzto22/ty`, backend Python), portada para Kotlin e rodando no aparelho. Nenhum vídeo, áudio
ou quadro sai do celular: só o texto das legendas vai ao Gemini, como antes.

## Fase 1 (esta): seleção dos cortes

Código: `editor-engine/.../highlights/HighlightCuration.kt`, usado pelo `GeminiHighlightAnalyzer`.

1. **Frases.** Os blocos de legenda viram frases: fecham em `.`, `!`, `?` ou `…`, numa pausa maior
   que 0,7 s ou ao passar de 15 s.
2. **Sinais.** Cada frase leva marcas medidas localmente: `⏸N.Ns` (pausa antes), `↑energia` /
   `↓energia` (percentis 80/20 do RMS do `ClipAnalyzer`, com pelo menos 5 frases) e `✂cena` (troca
   de cena até 0,5 s do início, via `HighlightRequest.sceneCutsMs`).
3. **Prompt.** A transcrição vai numerada por frase (`N [mm:ss] (Ns) {marcas} texto`). O Gemini
   devolve **só os números das frases** de início e fim, além de título, gancho, motivo e notas
   (gancho, coerência, valor, tendência, geral). Ele não devolve tempos, então não consegue inventá-los.
   Pede o dobro de cortes; o refine fica com os melhores.
4. **Validação.** Índices fora da transcrição são descartados; notas são limitadas a 0..100;
   números vindos como texto são aceitos.
5. **Refine.** Encaixa nas frases: corta frases do fim acima do máximo (preserva o gancho), anexa
   frases seguintes (depois anteriores) abaixo do mínimo, limita uma frase única ao máximo e aplica
   folgas de 100 ms / 350 ms sem invadir a frase vizinha. Descarta sobreposição acima de 30% e
   conteúdo repetido (Jaccard das palavras > 0,6), ordenando pela nota geral.

Sem corte válido da IA, o `HighlightDetector` continua caindo para a análise local, como antes.
O `Highlight` ganhou `title`, `hook` e `scores` (opcionais); o marcador usa o título quando existe.

Testes: `HighlightCurationTest` (JVM).

## Próximas fases

- **Fase 2: enquadramento por rosto.** Portar a "câmera virtual" do ClipStudio (`reframe.py`: zona
  morta, velocidade máxima, salto na troca de cena, simplificação Ramer–Douglas–Peucker) usando o
  ML Kit Face Detection que o Recly já tem, gerando keyframes de transformação em vez de expressões
  do ffmpeg. Detectar trocas de cena a partir do `MotionSample` do `ClipAnalyzer` e preencher
  `sceneCutsMs`.
- **Fase 3: cortes como projetos editáveis.** Cada momento escolhido vira um `Project` 9:16 com o
  trim no `ClipTimeMap`, os keyframes de reframe e as legendas existentes no trecho, aberto no editor
  e salvo pelo autosave normal, sem MP4 intermediário.
- **Fase 4 (opcional): avaliação visual.** Folha de contato 3×3 por candidato enviada ao Gemini para
  reordenar pelos aspectos visuais, como o passo 2 do ClipStudio.
