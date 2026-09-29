"""Count observed correction pairs; never alter transcriptions."""
import difflib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WATCH = {('contar','cortar'), ('evitar','editar'), ('mas','mais'),
         ('traz','trás'), ('agente','a gente')}


def analyze():
    counts = Counter()
    path = ROOT / 'errors/recly_user_corrections/hard_examples.jsonl'
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        prediction, correct = row['prediction'].lower().split(), row['correct_text'].lower().split()
        for tag, a, b, c, d in difflib.SequenceMatcher(a=prediction, b=correct).get_opcodes():
            if tag != 'equal':
                counts[(' '.join(prediction[a:b]), ' '.join(correct[c:d]))] += 1
    result = [{'prediction': p, 'correct': c, 'count': n, 'watch_pair': (p,c) in WATCH}
              for (p,c), n in counts.most_common()]
    (ROOT / 'reports/confusions.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result[:20], ensure_ascii=False, indent=2))

if __name__ == '__main__':
    analyze()
