"""Does teaching help the very next scans? Replays labelled scans in time order per place.

Each labelled scan is scored using only the dry/damp examples taught before it (as the app would
have). "next" is accuracy over all such scans; "after same label" counts only scans right after a
teach of the same label (mark damp, scan the damp spot again), the case the user feels directly.

Usage: python tools/immediate_teach_check.py folder [folder ...]   (oldest copy first)
"""
import json
import sys
from collections import defaultdict
from multiprocessing import Pool
from pathlib import Path

import numpy as np

from hybrid_check import hybrid_percent, select
from scatter_compare import DEDUPE, FEATURES, _dedupe


def app(x, dry, moist):
    d, m = _dedupe(dry, DEDUPE), _dedupe(moist, DEDUPE)
    return hybrid_percent(x, d, m, select(d, m))[0]


def recent(k):
    def f(x, dry, moist):
        return app(x, dry[-k:], moist[-k:])
    return f


def recent_weighted(x, dry, moist):
    """Newest 3 of each class counted twice, so a fresh teach moves the centres at once."""
    return app(x, np.vstack([dry, dry[-3:]]), np.vstack([moist, moist[-3:]]))


def nearest_blend(weight, k=1):
    """Mix the app score with the label of the k nearest taught examples (z-scored features)."""
    def f(x, dry, moist):
        both = np.vstack([dry, moist])
        mu, sd = both.mean(0), both.std(0) + 1e-9
        d = np.linalg.norm((both - mu) / sd - (x - mu) / sd, axis=1)
        lab = np.r_[np.zeros(len(dry)), np.ones(len(moist))]
        nn = 100.0 * lab[np.argsort(d)[:k]].mean()
        return (1 - weight) * app(x, dry, moist) + weight * nn
    return f


VARIANTS = {"app now": app, "newest 3 doubled": recent_weighted,
            "nearest 1, 30%": nearest_blend(0.3), "nearest 1, 50%": nearest_blend(0.5),
            "nearest 3, 50%": nearest_blend(0.5, 3)}


def run(args):
    name, seq = args
    f = VARIANTS[name]
    out = []
    for i, (damp, x) in enumerate(seq):
        past = seq[max(0, i - 80):i]
        dry = np.array([p for d, p in past if not d])
        moist = np.array([p for d, p in past if d])
        if len(dry) == 0 or len(moist) == 0:
            continue
        pred = f(x, dry[-40:], moist[-40:]) >= 50
        out.append((damp, pred == damp, i > 0 and seq[i - 1][0] == damp))
    return name, out


def main():
    latest = {}
    for folder in map(Path, sys.argv[1:]):
        for p in folder.glob("*.json"):
            latest[p.stem] = p
    by_place = defaultdict(list)
    for p in latest.values():
        m = json.loads(p.read_text(encoding="utf-8"))
        if m.get("label") not in ("dry", "damp") or m.get("featureVersion", 0) < 2:
            continue
        by_place[m["locationId"]].append((m["timestamp"], m["label"] == "damp",
                                          np.array([m["features"][k] for k in FEATURES])))
    seqs = [[(d, x) for _, d, x in sorted(v, key=lambda t: t[0])] for v in by_place.values()]
    seqs = [s for s in seqs if sum(d for d, _ in s) >= 3 and sum(not d for d, _ in s) >= 3]
    print(f"{len(seqs)} places, {sum(map(len, seqs))} labelled scans in time order\n")
    with Pool() as pool:
        res = pool.map(run, [(n, s) for n in VARIANTS for s in seqs], chunksize=1)
    agg = defaultdict(list)
    for name, out in res:
        agg[name] += out
    print(f"{'variant':<18}{'next scan':>11}{'dry ok':>9}{'damp ok':>9}{'after same label':>18}")
    for name, out in agg.items():
        a = np.array(out, dtype=bool)
        bal = 50 * (a[a[:, 0], 1].mean() + a[~a[:, 0], 1].mean())
        same = a[a[:, 2], 1].mean() * 100
        print(f"{name:<18}{bal:>10.1f}%{a[~a[:, 0], 1].mean() * 100:>8.0f}%"
              f"{a[a[:, 0], 1].mean() * 100:>8.0f}%{same:>17.0f}%")


if __name__ == "__main__":
    main()
