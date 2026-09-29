"""Check manifest paths, WAV properties, duplicates and speaker leakage."""
import hashlib
import json
import math
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def validate():
    errors, hashes, speakers, counts = [], {}, {}, {}
    for split in ('train', 'validation', 'test'):
        path = ROOT / 'manifests' / f'{split}.jsonl'
        counts[split] = 0
        for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            if not line.strip():
                continue
            counts[split] += 1
            try:
                row = json.loads(line)
                audio = row['audio']
                source = row['source']
                text = row['text']
                duration = row['duration']
                if row.get('language') != ('pt' if source == 'mls' else 'pt-BR') or not isinstance(text, str):
                    raise ValueError('invalid language or text')
                if not text and source != 'no_speech':
                    raise ValueError('empty speech transcript')
                if text and source == 'no_speech':
                    raise ValueError('nonempty no_speech transcript')
                if not math.isfinite(duration) or not .3 <= duration <= 30:
                    raise ValueError('duration outside accepted range')
                filepath = (ROOT / audio).resolve()
                if not filepath.is_relative_to(ROOT) or not filepath.is_file():
                    raise ValueError('missing or unsafe audio path')
                with wave.open(str(filepath), 'rb') as wav:
                    if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1,2,16000):
                        raise ValueError('invalid WAV format')
                    if abs(wav.getnframes()/16000 - duration) > .02:
                        raise ValueError('duration mismatch')
                digest = hashlib.sha256(filepath.read_bytes()).hexdigest()
                if row.get('sha256') != digest:
                    raise ValueError('SHA256 mismatch')
                if digest in hashes:
                    raise ValueError(f'duplicate audio in {hashes[digest]}')
                hashes[digest] = split
                speaker = row.get('speaker_id')
                if speaker and speaker in speakers and speakers[speaker] != split:
                    raise ValueError(f'speaker leakage from {speakers[speaker]}')
                if speaker:
                    speakers[speaker] = split
            except Exception as exc:
                errors.append({'split': split, 'line': number, 'reason': str(exc)})
    print(json.dumps({'counts': counts, 'errors': errors[:50], 'error_count': len(errors)}, ensure_ascii=False, indent=2))
    if errors:
        raise SystemExit(1)

if __name__ == '__main__':
    validate()
