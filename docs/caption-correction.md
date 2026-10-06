# Contextual caption correction

The editor can correct captions already on the timeline. It sends text in batches of up to 40 with stable IDs, the source language, neighboring caption text, project name, and the project's custom vocabulary. It never sends video frames or timestamps to the correction model.

The correction prompt is conservative: it targets clear transcription, spelling, punctuation, names, brands, slang, English words, and technical terms. The service validates that every input ID appears once, rejects blank or oversized output, and keeps text that changes too much. For captions with word timing cues, it also keeps the original when the corrected word count would invalidate those timings.

Accepted changes update the existing caption layers in one project-history operation. The before and after project snapshots support Undo/Redo; caption timing, style, position, and other layers stay intact.

## Release backend contract

Configure `RECLY_CAPTION_BACKEND_URL` as an HTTPS base URL. The app sends `POST /v1/captions/correct` with JSON:

```json
{
  "languageCode": "pt-BR",
  "projectContext": "Podcast Recly",
  "terms": ["Recly", "TikTok"],
  "segments": [
    { "id": "caption-id", "text": "fala original", "previous": "fala anterior", "next": "próxima fala" }
  ]
}
```

The backend must return every requested ID exactly once, with corrected text in `text`:

```json
[
  { "id": "caption-id", "text": "Fala original." }
]
```

It should apply the same conservative correction rules and keep Gemini credentials on the server. The response contains no timestamps; the Android app applies text to the original caption IDs.
