"""Accuracy of the exact hybrid scorer the app uses, against the app's previous scorer.

Mirrors AnomalyScorer's hybrid path: near-duplicate scans removed once, the feature
combination picked per place by 10-fold cross-validation on the taught scans (all five
features when either class has fewer than 3), score = mean of the LDA score and the
distance-to-centres score. Reported as leave-one-out balanced accuracy, with the feature
choice redone inside every fold so the number is not optimistic.

Usage: python tools/hybrid_check.py
"""
import os

import numpy as np

from scatter_compare import (COMBOS, DEDUPE, _dedupe, centroid_ratio, current_score_percent,
                             load_places, name)

FOLDS = int(os.environ.get("HYBRID_FOLDS", "10"))
MIN_PER_CLASS = 3
ALL = COMBOS[-1]


def hybrid_percent(x, dry, moist, idx):
    cols = list(idx)
    cur = current_score_percent(np.atleast_2d(x)[:, cols], dry[:, cols], moist[:, cols], idx)
    return 0.5 * cur + 50.0 * centroid_ratio(np.atleast_2d(x)[:, cols], dry[:, cols], moist[:, cols])


def cv_balanced(dry, moist, idx):
    hits_d = hits_m = 0
    for f in range(FOLDS):
        td, tm = np.arange(len(dry)) % FOLDS == f, np.arange(len(moist)) % FOLDS == f
        if not td.any() and not tm.any():
            continue
        tr_d, tr_m = dry[~td], moist[~tm]
        if td.any():
            hits_d += (hybrid_percent(dry[td], tr_d, tr_m, idx) < 50).sum()
        if tm.any():
            hits_m += (hybrid_percent(moist[tm], tr_d, tr_m, idx) >= 50).sum()
    return 50.0 * (hits_d / len(dry) + hits_m / len(moist))


def select(dry, moist):
    if len(dry) < MIN_PER_CLASS or len(moist) < MIN_PER_CLASS:
        return ALL
    best, best_acc = ALL, -1.0
    for c in COMBOS:
        acc = cv_balanced(dry, moist, c)
        if acc > best_acc:
            best, best_acc = c, acc
    return best


def self_check(dry, moist, scorer):
    dry, moist = _dedupe(dry, DEDUPE), _dedupe(moist, DEDUPE)
    if len(dry) < MIN_PER_CLASS or len(moist) < MIN_PER_CLASS:
        return None
    hits_d = sum(scorer(dry[i], np.delete(dry, i, 0), moist) < 50 for i in range(len(dry)))
    hits_m = sum(scorer(moist[i], dry, np.delete(moist, i, 0)) >= 50 for i in range(len(moist)))
    return 50.0 * (hits_d / len(dry) + hits_m / len(moist))


def old_scorer(x, dry, moist):
    return current_score_percent(np.atleast_2d(x), dry, moist, ALL)[0]


def new_scorer(x, dry, moist):
    return hybrid_percent(x, dry, moist, select(dry, moist))[0]


def main():
    places = load_places()
    print(f"{'place':<10} {'previous':>9} {'hybrid':>8}  features chosen on all taught scans")
    olds, news = [], []
    for p, (x, y) in places.items():
        dry, moist = x[y == 0], x[y == 1]
        o, n = self_check(dry, moist, old_scorer), self_check(dry, moist, new_scorer)
        chosen = select(_dedupe(dry, DEDUPE), _dedupe(moist, DEDUPE))
        print(f"{p:<10} {o:>8.0f}% {n:>7.0f}%  {name(chosen)}")
        olds.append(o)
        news.append(n)
    print(f"{'mean':<10} {np.mean(olds):>8.0f}% {np.mean(news):>7.0f}%")


if __name__ == "__main__":
    main()
