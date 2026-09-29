# Recly Studio 2.1

Pesquisa e implementacao de 8 de setembro de 2026. O APK tem teto de 200 MB;
nao ha preenchimento artificial de tamanho. Os fontes, shaders e recursos
incluidos funcionam localmente. Os videos e arquivos de projeto continuam no aparelho.

## Pesquisa

Foram consultadas paginas oficiais de recursos e implementacao:

- [DaVinci Resolve Edit](https://www.blackmagicdesign.com/products/davinciresolve/edit): montagem, timeline, cortes, marcadores e organizacao das ferramentas.
- [CapCut Keyframe Animation](https://www.capcut.com/tools/keyframe-animation): controles de animacao por pontos no tempo.
- [Adobe Premiere](https://www.adobe.com/products/premiere.html): organizacao de montagem, cor, audio, efeitos e legendas.
- [Android Media3 Transformer](https://developer.android.com/media/media3/transformer/customization): efeitos compartilhados entre composicao e exportacao.
- [Google Fonts](https://github.com/google/fonts): Outfit, Montserrat, Bebas Neue, Playfair Display, Caveat e Space Mono. Licencas OFL incluidas em assets/fonts.

As interfaces foram implementadas como controles Android. Os recursos comerciais
consultados nao foram copiados nem incorporados como pacotes proprietarios.

## Implementado nesta versao

| Area | Recurso e comportamento |
| --- | --- |
| Interface | Layout Studio para retrato/paisagem, monitor, ferramentas diretas, paineis, tipografia Outfit e icones desenhados |
| Timeline | Trilhas independentes de audio, separacao de textos/imagens simultaneos, rolagem vertical, zoom, encaixe, marcadores coloridos |
| Precisao | Avanco/recuo de um intervalo de quadro, corte/divisao acessiveis diretamente |
| Animacao | Keyframes de zoom, posicao, rotacao e opacidade; linear, suave, acelerar, desacelerar e manter |
| Cor | Exposicao, sombras, altas luzes, curva de cinco pontos, nitidez, vinheta e grao |
| LUT | Importacao .cube 3D, 2 a 64 pontos, dominio normalizado 0..1, intensidade e copia local |
| Composicao | Mascara circular/retangular/cinema, borda suave, inversao e chroma key sobre cor de fundo |
| Fundo | Cor personalizada, ajuste/preenchimento e proporcoes Original, 9:16, 16:9, 1:1, 4:5, 21:9, 4:3 |
| Montagem | Congelar quadro como foto PNG de tres segundos, inserida no corte do cursor quando possivel |
| Filtros | Galeria categoriza os 32 looks existentes e permite aplicar em um ou todos os clipes |
| Texto | Galeria visual com 15 fontes, seis familias reais incorporadas, estilos e animacoes anteriores preservados |
| Legendas | Ate 2.000 textos/legendas, busca, importacao/exportacao SRT; exceder o limite gera erro em vez de descartar frases |
| Projetos | Formato 6 com leitura das versoes 1..5; novos dados participam do salvar, desfazer e refazer |
| Exportacao | MP4 H.264/AAC com resolucao, FPS e bitrates configuraveis segundo o hardware |

## Limites reais

- Nao implementa reconhecimento automatico de fala, remocao de objetos por IA,
  rastreamento, multicamera, video sobre video/PiP ou uma curva continua de velocidade.
- Chroma key e mascaras revelam o fundo colorido; ainda nao compoem outro video por baixo.
- Ha uma sequencia principal de video, ate oito faixas adicionais de audio e 24 imagens sobrepostas.
- Os cartoes da galeria representam looks por nome/cor; nao sao miniaturas renderizadas do clipe com cada filtro.
- Fontes incorporadas e sistema compoem 15 familias; nao ha importacao arbitraria de TTF nesta versao.
- A previa padrao e 720p, configuravel no menu Buscar > Qualidade da previa.
  Exportar usa a qualidade de exportacao, independentemente da previa.
- Sem aparelho conectado, nao foi possivel validar a interface ou executar a exportacao no Galaxy S9.

## Validacao

Testes unitarios em StudioEditingTest cobrem interpolacao, cortes com keyframes,
formato de projeto, compatibilidade com v5, ordem da LUT e leitura/escrita SRT.
Os testes existentes de editor, captura e buffer continuam no mesmo conjunto.

O teste GLES executa os shaders reais em Mesa, verificando pixels para identidade,
exposicao, LUT neutra, opacidade, mascara, inversao e chroma key:

```sh
cc tools/test_studio_shader.c -o /tmp/recly-studio-shader-test -lEGL -lGLESv2 -lm
EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 /tmp/recly-studio-shader-test
```

Minificacao e remocao de recursos permanecem desativadas. O numero de trabalhadores
e a memoria do Gradle controlam a compilacao no computador, nao a qualidade do app.
