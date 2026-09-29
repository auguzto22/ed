"""Create basic digital silence clips; real background sounds must be imported separately."""
import hashlib
import json
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    for seconds in (1, 2, 4):
        relative = Path('no_speech/silence') / f'digital_silence_{seconds}s.wav'
        target = ROOT / relative
        with wave.open(str(target), 'wb') as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(b'\x00\x00' * 16000 * seconds)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        row = {'audio': str(relative), 'text': '', 'language': 'pt-BR', 'source': 'no_speech',
               'no_speech_category': 'silence', 'duration': float(seconds), 'sha256': digest,
               'speaker_id': None, 'license': 'generated in project',
               'provenance': 'digital zero-valued PCM silence', 'source_id': f'silence-{seconds}s'}
        with (ROOT / 'work/accepted.jsonl').open('a', encoding='utf-8') as out:
            out.write(json.dumps(row, ensure_ascii=False) + '\n')

if __name__ == '__main__':
    main()
