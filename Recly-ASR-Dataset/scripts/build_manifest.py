"""Deduplicate imports, split by speaker, and write manifests plus reports."""
import json
import math
import random
import wave
from collections import Counter
from pathlib import Path

from split_dataset import split

ROOT = Path(__file__).resolve().parents[1]


def records(path):
    if path.exists():
        with path.open(encoding='utf-8') as file:
            for line in file:
                if line.strip():
                    yield json.loads(line)


def write_jsonl(path, rows):
    with path.open('w', encoding='utf-8') as out:
        for row in rows:
            out.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + '\n')


def build():
    accepted, rejected = [], list(records(ROOT / 'work' / 'rejected.jsonl'))
    seen = set()
    for row in records(ROOT / 'work' / 'accepted.jsonl'):
        reason = None
        audio = ROOT / row['audio']
        if not audio.is_file():
            reason = 'missing audio file'
        elif (not row.get('text') and row.get('source') != 'no_speech') or '\ufffd' in row['text']:
            reason = 'invalid text'
        elif not math.isfinite(row.get('duration', float('nan'))):
            reason = 'invalid duration'
        else:
            try:
                with wave.open(str(audio), 'rb') as wav:
                    if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                        reason = 'invalid WAV format'
                    elif abs(wav.getnframes()/16000 - row['duration']) > .02:
                        reason = 'duration mismatch'
            except Exception as exc:
                reason = f'corrupt WAV: {exc}'
        key = row.get('sha256')
        if not reason and key in seen:
            reason = 'duplicate audio SHA256'
        if reason:
            rejected.append({'source': row.get('source'), 'source_id': row.get('source_id'), 'reason': reason})
        else:
            seen.add(key)
            accepted.append(row)
    write_jsonl(ROOT / 'reports' / 'bad_samples.jsonl', rejected)
    buckets = split(accepted)
    for name, rows in buckets.items():
        write_jsonl(ROOT / 'manifests' / f'{name}.jsonl', rows)
    count = Counter(row['source'] for row in accepted)
    hours = {source: sum(row['duration'] for row in accepted if row['source'] == source)/3600
             for source in ('common_voice', 'cetuc', 'mls', 'no_speech')}
    durations = [row['duration'] for row in accepted]
    histogram = {'0.3-2s': 0, '2-5s': 0, '5-10s': 0, '10-20s': 0, '20s+': 0}
    for d in durations:
        histogram['0.3-2s' if d < 2 else '2-5s' if d < 5 else '5-10s' if d < 10 else '10-20s' if d < 20 else '20s+'] += 1
    rng = random.Random(42)
    audit = rng.sample(accepted, min(100, len(accepted)))
    write_jsonl(ROOT / 'reports' / 'audit_100.jsonl',
                [{'audio': row['audio'], 'text': row['text'], 'source': row['source'], 'duration': row['duration'],
                  'chars_per_second': round(len(row['text']) / row['duration'], 2),
                  'automatic_flag': 'unusual_text_rate' if row['text'] and not 2 <= len(row['text']) / row['duration'] <= 30 else None,
                  'manual_review': 'pending'} for row in audit])
    report = {'total_files': len(accepted), 'total_hours': sum(hours.values()), 'hours_by_source': hours,
              'known_speakers': len({x['speaker_id'] for x in accepted if x.get('speaker_id')}),
              'average_duration': sum(durations)/len(durations) if durations else 0,
              'min_duration': min(durations, default=0), 'max_duration': max(durations, default=0),
              'splits': {k: len(v) for k, v in buckets.items()}, 'rejected': len(rejected),
              'no_speech': count['no_speech'], 'language_counts': dict(Counter(row['language'] for row in accepted)),
              'source_revisions': {row['source']: row.get('source_revision') for row in accepted if row['source'] in ('cetuc', 'mls', 'common_voice')}, 'duration_distribution': histogram,
              'manual_audit_pending': len(audit),
              'missing_sources': [name for name in ('common_voice', 'cetuc', 'mls') if hours[name] == 0],
              'missing_no_speech_categories': sorted(set(('silence', 'noise', 'music')) -
                  {row.get('no_speech_category') for row in accepted if row['source'] == 'no_speech'})}
    (ROOT / 'reports' / 'dataset_report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
    labels = [('Total de arquivos', len(accepted)), ('Total de horas', report['total_hours']),
              ('Horas Common Voice', hours['common_voice']), ('Horas CETUC', hours['cetuc']),
              ('Horas MLS', hours['mls']), ('Número de falantes conhecidos', report['known_speakers']),
              ('Duração média', report['average_duration']), ('Menor segmento', report['min_duration']),
              ('Maior segmento', report['max_duration']), ('Quantidade train', len(buckets['train'])),
              ('Quantidade validation', len(buckets['validation'])), ('Quantidade test', len(buckets['test'])),
              ('Quantidade rejeitada', len(rejected)), ('Quantidade no_speech', count['no_speech'])]
    (ROOT / 'reports' / 'statistics.txt').write_text(''.join(f'{k}: {v}\n' for k,v in labels) +
        'Distribuição de duração:\n' + ''.join(f'  {k}: {v}\n' for k,v in histogram.items()) +
        f'Revisão manual pendente: {len(audit)} pares\n')
    print(json.dumps(report, indent=2, ensure_ascii=False))

if __name__ == '__main__':
    build()
