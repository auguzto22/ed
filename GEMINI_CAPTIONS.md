# Legendas automáticas Gemini

## DEBUG local

Edite o `local.properties` na raiz do projeto e adicione sua chave, sem aspas:

```properties
GEMINI_API_KEY=MINHA_CHAVE_NOVA_AQUI
```

`local.properties` está no `.gitignore`. A chave só é exposta na variante `debug` através de `BuildConfig`; a variante `release` recebe uma string vazia e usa o backend HTTPS configurado por `RECLY_CAPTION_BACKEND_URL`.

## Fluxo

O editor extrai somente a faixa de áudio, remuxa AAC para M4A e usa WAV como fallback. Arquivos grandes são divididos em chunks de 20 minutos com sobreposição de 1,5 segundo. O Gemini 3.5 Transcribe é chamado via Files + Interactions em modo `verbatim` com timestamps por palavra. Vocabulário personalizado não é enviado nessa chamada; ele fica reservado para o corretor opcional Gemini 3.8 Flash.

Os segmentos retornados são convertidos nos `TextClip` existentes, com `wordCues`, e entram em uma única operação de `ProjectHistory`. Fonte, estilo, animação, posição, duração, edição manual, preview e persistência continuam sendo os sistemas atuais do Recly.

## RELEASE

Configure `RECLY_CAPTION_BACKEND_URL` como propriedade Gradle HTTPS. O provider de release envia áudio para `/v1/captions/transcribe` e correções para `/v1/captions/correct`; o backend deve guardar a chave Gemini em variável de ambiente/Secret Manager e nunca devolvê-la ao Android.
