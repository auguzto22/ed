# Legendas em português brasileiro

## Fluxo implementado

Áudio do clipe → PCM 16 kHz → VAD com margem → blocos de 20 s (até 30 s) com overlap de 3 s → Vosk (`vosk-model-small-pt-0.3`) → palavras com tempos, confiança e alternativas quando disponíveis → reranking conservador → detector de lacunas/incoerência → novas transcrições locais com contexto maior (sinal processado e bruto) → confirmação por evidência acústica → alinhamento temporal → divisão em legendas → `TextClip` → timeline → `CaptionStyleResolver` → preview e exportação.

O padrão é o [modelo pequeno de português/português brasileiro do Vosk](https://alphacephei.com/vosk/models). A opção Inglês usa o modelo pequeno `vosk-model-small-en-us-0.15`. A opção Automático transcreve até oito segundos com os dois modelos e compara confiança e cobertura, favorecendo PT-BR em casos próximos. Essa estimativa pode errar em trechos curtos, música ou fala mista. A API do Vosk não oferece um parâmetro separado `locale=pt-BR` para o modelo; a seleção real do idioma ocorre pela escolha do modelo. A confiança por palavra vem de `conf` quando o reconhecimento normal usa `setWords(true)`. N-best é usado quando as alternativas também trazem `result` com tempos; sem isso, a alternativa é descartada e o fluxo mantém somente evidência alinhada. O `PtBrContextRescorer` nunca gera palavras que não vieram do ASR.

O Vosk aceita gramáticas para vocabulário restrito, mas elas restringem as frases reconhecíveis. Para fala livre, o `BrazilianMixedVocabulary` e o vocabulário personalizado atuam como contexto na escolha entre hipóteses acústicas, sem transformar o dicionário em uma falsa etapa de treinamento. Variantes como `pódcast`, `gueimplei` e `daunloud` são metadados de reconhecimento e nunca são copiadas para a legenda. Quando o backend não fornece alinhamento por palavra em uma alternativa, ela é descartada para não inventar tempos. A [documentação de adaptação do Vosk](https://alphacephei.com/vosk/adaptation) distingue alteração de vocabulário, adaptação do modelo de linguagem e ajuste do modelo acústico.

Os pesos de `CaptionRerankingWeights` são parâmetros injetáveis do gerador; devem ser recalibrados contra o conjunto de validação antes de uma mudança de produto. A configuração conservadora local mantém o ganho de vocabulário/entidade pequeno frente à evidência acústica.

Nomes de pessoas, criadores, marcas e produtos vêm do título, nome do arquivo, descrição disponível e vocabulário manual do projeto. O `DynamicEntityContext` aplica gate de confiança: abaixo do limiar o trecho é incerto e entra no retry/revisão, em vez de receber automaticamente “Neymar”, “Igor” ou outro nome conhecido.

## Vocabulário e revisão

O léxico local em `editor-engine/src/main/assets/language/pt_br/lexicon.json` reúne termos de fala informal, internet, jogos, edição, tecnologia, marcas e regiões. O módulo estruturado `mixed_vocabulary.json` adiciona categorias de code-switching e variantes fonéticas internas. Foi selecionado manualmente a partir dos exemplos da tarefa e de consultas sobre variação linguística, como [Priberam: oxente](https://dicionario.priberam.org/oxente), [Fundação Joaquim Nabuco: expressões populares](https://pesquisaescolar.fundaj.gov.br/pt-br/artigo/expressoes-populares/) e [Corpus do Português](https://www.corpusdoportugues.org/files/corpus-do-portugues_pt.pdf). Não se deve interpretá-lo como corpus de frequências nem como lista exaustiva de sotaques. A presença de um termo dá no máximo um pequeno bônus na comparação de palavras realmente reconhecidas.

O projeto salva até 500 termos personalizados. Correções manuais de legendas são guardadas localmente em `caption_corrections/<project-id>.json`. Uma ocorrência isolada não influencia as transcrições seguintes; duas ou mais correções iguais, em contexto compatível, geram um pequeno bônus. Nem o léxico nem o histórico mudam o modelo acústico.

## Fonte global

Somente camadas marcadas como legenda herdam `captionGlobalFontId`. Uma legenda pode ter `captionFontOverride`. O mesmo `CaptionStyleResolver` resolve a fonte para o estado de preview e para a composição de exportação. Alterações usam o histórico normal do projeto, então Undo/Redo restaura a fonte global e os overrides. A biblioteca existente de fontes faz seleção visual e cache de fontes.

## Avaliação e dados futuros

O modelo pequeno ainda pode errar em fala rápida, ruído, nomes e sotaques. Para medir ganhos, use áudio com consentimento e licença compatível, separe por falante e ambiente e calcule WER por grupo. Cada exemplo de um futuro conjunto de avaliação deve ter `audio.wav`, `transcript.txt` e metadados de região, equipamento, ruído e licença. [Mozilla Common Voice](https://commonvoice.mozilla.org/en/terms) é uma fonte possível conforme seus termos; não espelhe o conjunto nem use áudio de terceiros sem licença. O ajuste acústico real exigiria uma etapa separada de treinamento e validação do modelo Vosk.
