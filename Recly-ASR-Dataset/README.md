# Recly PT-BR ASR dataset — preparation

This directory is separate from the Android app. No model training starts here. Audio is stored as 16 kHz mono PCM16 WAV. Transcriptions retain their source spelling, including colloquial forms when present; this pipeline does not invent informal labels.

## Sources and rights

- [Common Voice Scripted Speech 26.0 — Brazilian Portuguese](https://mozilladatacollective.com/datasets/cmruxo9ew00d3md07veethj6k): CC0-1.0; already filtered to validated Brazilian accent clips, about 5 h 57 min. Download its archive from Mozilla Data Collective and provide the local path. Do not attempt to identify speakers.
- [CETUC / FalaBrasil](https://huggingface.co/datasets/falabrasil/cetuc): MIT, read incrementally from published Parquet shards in `train` to about 4.3 hours (4.37 hours from the first shard in the current run).
- [MLS Portuguese](https://huggingface.co/datasets/facebook/multilingual_librispeech): CC BY 4.0; labeled `pt` because the source does not certify Brazilian accent; read incrementally from the currently published `9_hours` Parquet split. Retain attribution when using or distributing results.
- [CORAA v1.1](https://huggingface.co/datasets/Racoci/CORAA-v1.1): CC BY-NC-ND 4.0. Research only; never imported into commercial manifests. Put any local research files in `research/coraa/` after separate license review.

The datasets above are mostly read speech. They cannot, on their own, demonstrate improved performance on slang, rapid spontaneous conversation, or noisy gameplay. That needs the later permissioned Recly-BR-Informal corpus and evaluation.

## Run

Requires Python 3.10+, ffmpeg/ffprobe, and network access to Hugging Face. The Common Voice archive must be downloaded separately through Mozilla Data Collective.

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python scripts/download_datasets.py --sources cetuc mls
.venv/bin/python scripts/download_datasets.py --sources common_voice --common-voice-archive /path/to/common-voice-scripted-speech-26-0-brazil-2e99282f.tar.gz
.venv/bin/python scripts/build_manifest.py
.venv/bin/python scripts/validate_dataset.py
```

A Parquet shard is cached on disk before row-group processing; the first CETUC shard is about 500 MB. Hugging Face repository revisions are pinned in `scripts/download_datasets.py`; if a source changes, inspect the new revision before updating the pin. The importer writes `state.json` after each processed sample, and resumes by skipping already scanned source rows. Keep `work/accepted.jsonl`, `work/rejected.jsonl`, and the audio directory together. Rebuilding manifests is safe and deterministic. Rejected examples are reported in `reports/bad_samples.jsonl`; no input is silently deleted. A source import can be interrupted and rerun. The 80/10/10 split uses known speaker IDs as groups and hashes to keep identical audio in one split. With large speakers the final percentages may deviate to preserve isolation.

`reports/audit_100.jsonl` is a random, deterministic set of up to 100 audio/text pairs. `manual_review: pending` means a human has not listened to the pair. Metadata checks cannot establish whether spoken words match the transcript; complete the listening review before using this as a trusted evaluation set.

## No speech and user corrections

Only import recordings you own or may use. Verify they contain no speech, including background vocals or faint voices, before importing. Silence, noise, and instrumental music use empty text.

```sh
.venv/bin/python scripts/import_no_speech.py /path/to/recording.wav --category noise --license 'owner permission' --provenance 'recording description'
.venv/bin/python scripts/add_correction.py /path/to/clip.wav --prediction 'eu vou contar' --correct-text 'eu vou cortar' --start-ms 0 --end-ms 1500 --model-version recly_asr_v1
.venv/bin/python scripts/build_manifest.py
.venv/bin/python scripts/validate_dataset.py
```

User corrections are stored separately in `errors/recly_user_corrections/hard_examples.jsonl`. They are not incorporated into this first dataset without review and permission. Phonetic confusions are examples for later error analysis; this pipeline never applies text substitutions blindly.

The future `categories/` tree reserves separate manifests for PT-BR slang,
English words in mixed-language speech, social media, technology, gaming,
proper names, brands, mixed language and hard examples. Populate it only with
human-verified, permissioned audio/transcript pairs; generated captions remain
predictions, never labels.

## Model compatibility

The Android code currently loads `vosk-model-small-pt-0.3`. This generic audio/text manifest is a preparation artifact, not a ready-to-load Vosk model. A later fine-tuning phase must establish the Vosk/Kaldi training recipe, vocabulary and language model update, model packaging, and device evaluation. No Android code is changed here.
