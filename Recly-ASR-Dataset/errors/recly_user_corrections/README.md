# Correction format

Append reviewed user corrections with `scripts/add_correction.py`. Each JSONL row has `audio`, `prediction`, `correct_text`, `start_ms`, `end_ms`, and `model_version`. Audio is WAV PCM16 mono 16 kHz. The correct text is authoritative; phonetic-confusion analysis only counts observed differences. Keep corrections out of training until recording permissions and labels are reviewed.
