"""Deterministic, duration-balanced speaker-disjoint split."""
import hashlib


def select(groups, target, desired_speakers):
    """Subset sum in one-second units, preferring more known speakers on ties."""
    unit = 1
    limit = round((target + 120) / unit)
    max_speakers = desired_speakers + 1
    items = sorted(groups.items(), key=lambda kv: hashlib.sha256(kv[0].encode()).hexdigest())
    states = {0: ()}
    for index, (_, rows) in enumerate(items):
        weight = max(1, round(sum(row['duration'] for row in rows) / unit))
        for total, chosen in list(states.items()):
            new_total = total + weight
            if new_total > limit:
                continue
            candidate = chosen + (index,)
            if known_count(candidate, items) > max_speakers:
                continue
            prior = states.get(new_total)
            if prior is None or abs(known_count(candidate, items) - desired_speakers) < abs(known_count(prior, items) - desired_speakers):
                states[new_total] = candidate
    best = min(states.items(), key=lambda state: (
        abs(state[0] * unit - target), abs(known_count(state[1], items) - desired_speakers),
        hashlib.sha256(str(state[1]).encode()).hexdigest()))
    return {items[i][0] for i in best[1]}


def known_count(chosen, items):
    return sum(not items[i][0].startswith('unknown:') for i in chosen)


def split(rows):
    groups = {}
    negatives = []
    for row in rows:
        if row['source'] == 'no_speech':
            negatives.append(row)
            continue
        group = row.get('speaker_id') or 'unknown:' + row['sha256']
        groups.setdefault(group, []).append(row)
    total = sum(row['duration'] for members in groups.values() for row in members)
    desired = max(2, round(sum(not k.startswith('unknown:') for k in groups) * .1))
    test_groups = select(groups, total * .1, desired)
    remaining = {k: v for k, v in groups.items() if k not in test_groups}
    validation_groups = select(remaining, total * .1, desired)
    buckets = {'train': [], 'validation': [], 'test': []}
    for key, members in groups.items():
        destination = 'test' if key in test_groups else 'validation' if key in validation_groups else 'train'
        buckets[destination].extend(members)
    negatives.sort(key=lambda row: hashlib.sha256(row['sha256'].encode()).hexdigest())
    for index, row in enumerate(negatives):
        destination = ('validation', 'test', 'train')[index] if index < 3 else ('train' if index % 10 < 8 else 'validation' if index % 10 == 8 else 'test')
        buckets[destination].append(row)
    return buckets
