# Transições

## Modelo

Uma transição (`TransitionInstance`) vive num corte entre dois clipes **adjacentes** da faixa
principal e toca **inteira dentro do clipe da direita**: o clipe da esquerda fica congelado no
último quadro durante a janela `[início do clipe B, início + duração)`. A colocação dos clipes
nunca muda por causa de uma transição.

## Invariantes (garantidas por `Project.withSanitizedTransitions`)

- Só existe transição entre pares adjacentes, no máximo uma por corte.
- Duração entre 100 ms e `min(duração do clipe B, 5 s)`; se B tiver menos de 100 ms, a transição sai.
- Aplicado em toda edição (`EditorSessionController.apply`) e ao abrir arquivos (`ProjectCodec.read`).
- Dividir um clipe leva a transição de saída para a metade da direita; duplicar leva para a cópia;
  substituir mantém grupos e transições apontando para o clipe novo.

## Prévia × exportação

As duas usam os mesmos valores:

- Parâmetros do usuário limitados à faixa da definição.
- `p_*` não declarados no catálogo usam `TransitionShaderBuilder.supplementalDefault`.
- Id desconhecido cai na primeira definição embutida; shader que não compila vira corte seco.
- Na exportação, se o quadro capturado do clipe A não estiver pronto, o clipe B é mostrado
  (corte seco), nunca o fundo.

## Áudio

O áudio do clipe A vai até o corte com um fade curto anti-clique (`TRANSITION_AUDIO_TAIL_US`,
60 ms). O clipe B entra com fade-in pela duração da transição. Antes, A sumia durante toda a
duração antes do corte e criava um buraco de silêncio.

## Painel

O painel de transições só atualiza a prévia enquanto está aberto. "Aplicar" grava um único passo
de desfazer; fechar de qualquer outro jeito restaura o projeto. Trocar de transição zera os
parâmetros, porque `mode` significa uma variante diferente em cada shader.

## Legado

Os campos antigos de `VideoClip` (`transitionIn`, `transitionOut`, `transitionDurationUs`) são
fades para cor sólida e continuam funcionando separados deste sistema.
