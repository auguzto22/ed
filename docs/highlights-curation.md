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

## Fase 2: trocas de cena e câmera com salto

- `SceneCutDetector` lê o `MotionSample` que o `ClipAnalyzer` já mede (diferença de luminância a cada
  500 ms) e marca troca de cena quando há um pico isolado: diferença ≥ 0,30 **e** ≥ 3× a mediana dos
  3 vizinhos de cada lado, com no mínimo 1 s entre trocas. Movimento contínuo não dispara.
- "Melhores momentos" passa essas trocas (e as fronteiras entre clipes da timeline) ao Gemini como
  `✂cena`. A energia e as trocas agora são convertidas para o tempo do projeto (`startOf`); antes, a
  energia ficava no tempo local de cada clipe e desalinhava em projetos com mais de um clipe.
- O Auto Reframe já existente (ML Kit rosto → corpo, suavização, zona morta) ganhou o salto do
  ClipStudio: com `sceneCutsUs`, a câmera segura o enquadramento até ~40 ms antes do corte e salta
  para o novo sujeito, em vez de deslizar entre duas cenas.
- Correção no `AutoReframeEngine.toKeyframes`: o lead-in de 180 ms criava, para clipes sem animação
  do usuário, um keyframe com o transform base (zoom 1, centro) antes de cada ponto, e a câmera
  "respirava" de volta ao quadro inteiro. Agora o lead-in só é criado quando o clipe já tem keyframes.

## Fase 3: momento → projeto 9:16 editável

- Cada destaque da lista tem o botão **Criar corte 9:16 editável**.
- `HighlightProjectBuilder` monta um `Project` novo com `splitAt` (as mesmas operações do editor,
  respeitando `ClipTimeMap`, velocidade e keyframes), mantém só os clipes do trecho e leva as
  legendas/textos do trecho deslocados para o novo início (com `wordCues`). Sai em 9:16 com
  preenchimento (corte central).
- Cada clipe passa pelo Auto Reframe 9:16 com as trocas de cena; sem sujeito, fica o corte central.
- O projeto é salvo no `ProjectStore` e aberto no editor. O projeto original não muda.
- Fica de fora, de propósito: trilhas sobrepostas, áudio extra, stickers, ajustes, marcadores e
  grupos, porque dependem do tempo do projeto original.

Testes: `HighlightProjectTest` (JVM).

## Ainda não feito

- **Avaliação visual (opcional).** Folha de contato 3×3 por candidato enviada ao Gemini para
  reordenar pelos aspectos visuais, como o passo 2 do ClipStudio. Custa uma chamada a mais e envia
  imagens; só vale se a avaliação com vídeos reais mostrar ganho.
