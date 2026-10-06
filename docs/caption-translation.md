# Caption translation

The editor sends caption text to the configured translation provider in batches of up to 40. Each input carries a stable caption ID and its neighboring caption text for context. The response must return exactly one non-empty translation for every input ID.

Translation is applied to the existing caption layers by ID. Their start/end times, phrase boundaries, style and transforms are kept. When a caption has word cues, the translated words receive evenly distributed cues inside the original cue interval. The whole project change is one undoable edit.

## Release backend contract

Configure `RECLY_CAPTION_BACKEND_URL` as an HTTPS base URL. The app sends `POST /v1/captions/translate` with JSON:

```json
{
  "sourceLanguageCode": "pt-BR",
  "targetLanguageCode": "en-US",
  "segments": [
    { "id": "caption-id", "text": "Fala original", "previous": null, "next": "Próxima fala" }
  ]
}
```

Return JSON with every requested ID exactly once:

```json
{
  "translations": [
    { "id": "caption-id", "translatedText": "Original line" }
  ]
}
```

The backend should ask Gemini to preserve meaning, tone, and the one-to-one caption split. It must treat the neighboring captions as context only, return no timestamps, and keep the Gemini key on the server. The Android client rejects incomplete, duplicate, blank, or overlong results before applying any caption changes.
