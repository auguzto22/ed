"""Incremental source import. Common Voice 26 must be supplied as a local MDC archive."""
import argparse
import csv
import io
import json
import math
import re
import tarfile
from pathlib import Path

from prepare_audio import convert

ROOT = Path(__file__).resolve().parents[1]
REVISIONS = {'cetuc': '6dbc8081d35fff5bdd133d8a12b8a11f91959b01',
             'mls': '2e83e61823b4c47dcbcb1980bb88601274127609',
             'common_voice': '26.0'}
SOURCES = {'cetuc': ('falabrasil/cetuc', None, 'train'),
           'mls': ('facebook/multilingual_librispeech', 'portuguese', '9_hours')}


def append(path, item):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a', encoding='utf-8') as out:
        out.write(json.dumps(item, ensure_ascii=False, allow_nan=False) + '\n')


def valid_text(value):
    if not isinstance(value, str):
        raise ValueError('missing transcription')
    value = ' '.join(value.split())
    if not value or '\ufffd' in value or any(ord(c) < 32 for c in value):
        raise ValueError('empty or invalid transcription')
    return value


def get_audio(row, name):
    field = row['wav' if name == 'cetuc' else 'audio']
    if isinstance(field, dict):
        if field.get('bytes'):
            return field['bytes']
        if field.get('path') and Path(field['path']).is_file():
            return Path(field['path'])
    if isinstance(field, (bytes, bytearray)):
        return bytes(field)
    raise ValueError('audio bytes unavailable')


def hf_rows(name):
    import urllib.request
    import pyarrow.parquet as pq
    repo, config, split = SOURCES[name]
    config = config or 'default'
    endpoint = f'https://huggingface.co/api/datasets/{repo}/parquet/{config}'
    with urllib.request.urlopen(f'https://huggingface.co/api/datasets/{repo}', timeout=60) as response:
        current_sha = json.load(response)['sha']
    if current_sha != REVISIONS[name]:
        raise RuntimeError(f'{name} revision changed: expected {REVISIONS[name]}, got {current_sha}')
    with urllib.request.urlopen(endpoint, timeout=60) as response:
        urls = json.load(response)[split]
    cache = ROOT / 'work' / 'parquet' / name
    cache.mkdir(parents=True, exist_ok=True)
    for shard, url in enumerate(urls):
        local = cache / f'{shard:03d}.parquet'
        if not local.is_file():
            temp = local.with_suffix('.partial')
            offset = temp.stat().st_size if temp.exists() else 0
            request = urllib.request.Request(url, headers={'Range': f'bytes={offset}-'} if offset else {})
            with urllib.request.urlopen(request, timeout=120) as response:
                mode = 'ab' if offset and response.status == 206 else 'wb'
                with temp.open(mode) as out:
                    while chunk := response.read(1024 * 1024):
                        out.write(chunk)
            pq.ParquetFile(temp)
            temp.replace(local)
        parquet = pq.ParquetFile(local)
        for group in range(parquet.num_row_groups):
            for row in parquet.read_row_group(group).to_pylist():
                yield row


def cv_rows(archive):
    with tarfile.open(archive, 'r:*') as tar:
        members = {m.name: m for m in tar.getmembers() if m.isfile()}
        partitions = [m for n, m in members.items() if Path(n).name in ('train.tsv', 'dev.tsv', 'test.tsv')]
        if not partitions:
            raise ValueError('train/dev/test TSV missing from Common Voice archive')
        for tsv in partitions:
            parent = Path(tsv.name).parent
            with tar.extractfile(tsv) as file:
                reader = csv.DictReader(io.TextIOWrapper(file, encoding='utf-8-sig'), delimiter='\t')
                for row in reader:
                    clip = row.get('path', '')
                    if not clip or '/' in clip or "\\" in clip or '..' in clip:
                        yield {'_error': 'unsafe or missing clip path', '_key': clip}
                        continue
                    member = members.get(str(parent / 'clips' / clip))
                    if not member:
                        yield {'_error': 'missing clip', '_key': clip}
                        continue
                    with tar.extractfile(member) as audio_file:
                        data = audio_file.read()
                    yield {'audio': data, 'text': row.get('sentence'),
                           'speaker_id': row.get('client_id') or None, '_key': clip}


def process(name, args):
    state_path = ROOT / 'state.json'
    state = json.loads(state_path.read_text()) if state_path.exists() else {}
    info = state.setdefault(name, {'processed': 0, 'seconds': 0.0, 'accepted': 0})
    rows = cv_rows(args.common_voice_archive) if name == 'common_voice' else hf_rows(name)
    target_seconds = {'cetuc': args.cetuc_hours * 3600, 'mls': args.mls_hours * 3600,
                      'common_voice': math.inf}[name]
    if info['seconds'] >= target_seconds:
        print(f'{name}: target already reached')
        return
    for index, row in enumerate(rows):
        if index < info['processed']:
            continue
        key = str(row.get('_key') or row.get('id') or index)
        target = ROOT / 'audio' / name / f'{index:07d}.wav'
        try:
            if '_error' in row:
                raise ValueError(row['_error'])
            raw_text = row.get('text' if name == 'common_voice' else 'txt' if name == 'cetuc' else 'transcript')
            caption = valid_text(raw_text)
            source = row['audio'] if name == 'common_voice' else get_audio(row, name)
            duration, digest = convert(source, target)
            if not math.isfinite(duration) or not 0.3 <= duration <= args.max_duration:
                raise ValueError(f'duration out of range: {duration}')
            speaker = (row.get('speaker_id') or (re.search(r'([^/]+)_([FM]\d+)', str(row.get('__url__', ''))) or [None, None, None])[2]
                       if name == 'cetuc' else row.get('speaker_id'))
            append(ROOT / 'work' / 'accepted.jsonl', {
                'audio': str(target.relative_to(ROOT)), 'text': caption, 'language': 'pt' if name == 'mls' else 'pt-BR',
                'source': name, 'source_revision': REVISIONS[name], 'duration': round(duration, 4), 'sha256': digest,
                'speaker_id': f'{name}:{speaker}' if speaker else None, 'source_id': key})
            info['seconds'] += duration
            info['accepted'] += 1
        except Exception as exc:
            target.unlink(missing_ok=True)
            append(ROOT / 'work' / 'rejected.jsonl',
                   {'source': name, 'source_id': key, 'reason': str(exc)[:500]})
        info['processed'] = index + 1
        state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2))
        if info['processed'] % 100 == 0:
            print(f'{name}: {info["processed"]} scanned, {info["seconds"]/3600:.2f} h', flush=True)
        if info['seconds'] >= target_seconds:
            break


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sources', nargs='+', choices=['common_voice', 'cetuc', 'mls'], required=True)
    parser.add_argument('--common-voice-archive', type=Path)
    parser.add_argument('--cetuc-hours', type=float, default=4.3)
    parser.add_argument('--mls-hours', type=float, default=9)
    parser.add_argument('--max-duration', type=float, default=30)
    args = parser.parse_args()
    if 'common_voice' in args.sources and (not args.common_voice_archive or not args.common_voice_archive.is_file()):
        parser.error('Common Voice requires a local MDC archive via --common-voice-archive')
    for name in args.sources:
        process(name, args)

if __name__ == '__main__':
    main()
