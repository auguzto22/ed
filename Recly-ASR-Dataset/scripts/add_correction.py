"""Store a user-approved caption correction for later hard-example training."""
import argparse
import hashlib
import json
from pathlib import Path
from prepare_audio import convert

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('audio', type=Path)
    p.add_argument('--prediction', required=True)
    p.add_argument('--correct-text', required=True)
    p.add_argument('--start-ms', type=int, required=True)
    p.add_argument('--end-ms', type=int, required=True)
    p.add_argument('--model-version', required=True)
    args = p.parse_args()
    if not args.audio.is_file() or not args.correct_text.strip() or args.start_ms < 0 or args.end_ms <= args.start_ms:
        p.error('invalid correction or missing audio')
    ident = hashlib.sha256(args.audio.read_bytes()).hexdigest()[:16]
    relative = Path('errors/recly_user_corrections/audio') / (ident + '.wav')
    duration, _ = convert(args.audio, ROOT / relative)
    if args.end_ms > duration * 1000 + 20:
        (ROOT / relative).unlink(missing_ok=True)
        p.error('end-ms exceeds audio duration')
    row = {'audio': str(relative), 'prediction': args.prediction.strip(),
           'correct_text': args.correct_text.strip(), 'start_ms': args.start_ms,
           'end_ms': args.end_ms, 'model_version': args.model_version}
    with (ROOT / 'errors/recly_user_corrections/hard_examples.jsonl').open('a', encoding='utf-8') as out:
        out.write(json.dumps(row, ensure_ascii=False) + '\n')
    print(relative)

if __name__ == '__main__':
    main()
