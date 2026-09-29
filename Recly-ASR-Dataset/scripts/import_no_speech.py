"""Import explicitly licensed non-speech recordings; never infer labels from sound."""
import argparse
import hashlib
import json
from pathlib import Path
from prepare_audio import convert

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('file', type=Path)
    p.add_argument('--category', required=True, choices=['silence','noise','music'])
    p.add_argument('--license', required=True, help='License or ownership of the source audio')
    p.add_argument('--provenance', required=True, help='Where the recording came from')
    args = p.parse_args()
    if not args.file.is_file():
        p.error('input file missing')
    ident = hashlib.sha256(args.file.read_bytes()).hexdigest()[:16]
    relative = Path('no_speech') / args.category / (ident + '.wav')
    target = ROOT / relative
    duration, digest = convert(args.file, target)
    if not .3 <= duration <= 30:
        target.unlink(missing_ok=True)
        p.error('duration must be 0.3–30 seconds')
    row = {'audio': str(relative), 'text': '', 'language': 'pt-BR', 'source': 'no_speech',
           'no_speech_category': args.category, 'duration': duration, 'sha256': digest,
           'speaker_id': None, 'license': args.license, 'provenance': args.provenance,
           'source_id': ident}
    with (ROOT / 'work' / 'accepted.jsonl').open('a', encoding='utf-8') as out:
        out.write(json.dumps(row, ensure_ascii=False) + '\n')
    print(relative)

if __name__ == '__main__':
    main()
