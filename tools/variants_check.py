"""Compare scorer variants on the phone's current teaching data (locations.json).

Each variant is graded by leave-one-out balanced accuracy per place (near-duplicates removed,
3+ dry and 3+ damp needed), with any per-place choice redone inside every fold.

Usage: python tools/variants_check.py path/to/locations.json
"""
import json
import sys
from multiprocessing import Pool

import numpy as np

from hybrid_check import ALL, MIN_PER_CLASS, cv_balanced, hybrid_percent, select
from scatter_compare import COMBOS, DEDUPE, FEATURES, _dedupe, centroid_ratio, current_score_percent


def guarded_select(dry, moist, margin=10.0):
    if len(dry) < MIN_PER_CLASS or len(moist) < MIN_PER_CLASS:
        return ALL
    base = cv_balanced(dry, moist, ALL)
    best, best_acc = ALL, base
    for c in COMBOS:
        acc = cv_balanced(dry, moist, c)
        if acc > best_acc:
            best, best_acc = c, acc
    return best if best_acc >= base + margin else ALL


def knn(x, dry, moist, k=3):
    both = np.vstack([dry, moist])
    mu, s = both.mean(0), both.std(0, ddof=1)
    s[s < 1e-9] = 1.0
    z = (both - mu) / s
    d = np.linalg.norm(z - (np.atleast_2d(x) - mu) / s, axis=1)
    lab = np.r_[np.zeros(len(dry)), np.ones(len(moist))]
    near = lab[np.argsort(d)[:k]]
    return 100.0 * near.mean()


def logf(a):
    a = np.array(a, dtype=float)
    a[..., :4] = np.log(np.maximum(a[..., :4], 1e-6))
    return a


def nearest30(x, d, m):
    app = hybrid_percent(x, d, m, select(d, m))[0]
    both = np.vstack([d, m])
    mu, sd = both.mean(0), both.std(0) + 1e-9
    dist = np.linalg.norm((both - mu) / sd - (np.asarray(x) - mu) / sd, axis=1)
    nn = 100.0 * (np.argmin(dist) >= len(d))
    return 0.7 * app + 0.3 * nn


VARIANTS = {
    "nearest30": nearest30,
    "previous": lambda x, d, m: current_score_percent(np.atleast_2d(x), d, m, ALL)[0],
    "hybrid_all": lambda x, d, m: hybrid_percent(x, d, m, ALL)[0],
    "hybrid_select (app)": lambda x, d, m: hybrid_percent(x, d, m, select(d, m))[0],
}


def loo(args):
    place, dry, moist, vname = args
    f = VARIANTS[vname]
    hd = sum(f(dry[i], np.delete(dry, i, 0), moist) < 50 for i in range(len(dry)))
    hm = sum(f(moist[i], dry, np.delete(moist, i, 0)) >= 50 for i in range(len(moist)))
    return place, vname, 50.0 * (hd / len(dry) + hm / len(moist))


def main():
    places = {}
    for loc in json.load(open(sys.argv[1], encoding="utf-8")):
        d = np.array([[f[k] for k in FEATURES] for f in loc.get("baselineFeatures", [])]).reshape(-1, 5)
        m = np.array([[f[k] for k in FEATURES] for f in loc.get("moistFeatures", [])]).reshape(-1, 5)
        d, m = _dedupe(d, DEDUPE), _dedupe(m, DEDUPE)
        if len(d) >= MIN_PER_CLASS and len(m) >= MIN_PER_CLASS:
            places[loc["label"]] = (d, m)
    jobs = [(p, d, m, v) for p, (d, m) in places.items() for v in VARIANTS]
    with Pool() as pool:
        res = pool.map(loo, jobs, chunksize=1)
    table = {(p, v): a for p, v, a in res}
    names = list(VARIANTS)
    print(f"{'place':<10}" + "".join(f"{v[:14]:>15}" for v in names))
    for p in places:
        print(f"{p:<10}" + "".join(f"{table[p, v]:>14.0f}%" for v in names))
    print(f"{'MEAN':<10}" + "".join(f"{np.mean([table[p, v] for p in places]):>14.1f}%" for v in names))


if __name__ == "__main__":
    main()
