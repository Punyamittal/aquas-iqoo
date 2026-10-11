"""Scatter plots of taught dry vs damp scans, and whether two features score as well as five.

For every place with at least 3 dry and 3 damp current-pipeline scans, compares leave-one-out
balanced accuracy of:
  - LDA on all five features (close to what AnomalyScorer does),
  - LDA on the best pair of features,
  - nearest-centroid on the best pair (the simplest thing you can draw on a scatter plot).

Usage: python tools/scatter_analysis.py [--csv data/ultrasonic/dataset/all_samples.csv]
Writes PNGs to docs/charts/scatter/.
"""
import argparse
import csv
import itertools
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from sklearn.discriminant_analysis import LinearDiscriminantAnalysis
from sklearn.metrics import balanced_accuracy_score
from sklearn.model_selection import LeaveOneOut
from sklearn.neighbors import NearestCentroid
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

FEATURES = ["resonanceFreqHz", "decayTimeMs", "spectralCentroidHz", "spectralSpreadHz", "spectralFlatness"]
SHORT = {"resonanceFreqHz": "resonance (Hz)", "decayTimeMs": "decay (ms)",
         "spectralCentroidHz": "centroid (Hz)", "spectralSpreadHz": "spread (Hz)",
         "spectralFlatness": "flatness"}
MIN_PER_CLASS = 3


def loo_accuracy(model_factory, x, y):
    preds = np.empty_like(y)
    for train, test in LeaveOneOut().split(x):
        if len(set(y[train])) < 2:
            return None
        m = model_factory()
        m.fit(x[train], y[train])
        preds[test] = m.predict(x[test])
    return 100 * balanced_accuracy_score(y, preds)


def nested_pair_accuracy(x, y):
    """Pick the best pair on the training scans only, then test it on the held-out scan."""
    preds = np.empty_like(y)
    pairs = list(itertools.combinations(range(x.shape[1]), 2))
    for train, test in LeaveOneOut().split(x):
        xt, yt = x[train], y[train]
        if min((yt == 0).sum(), (yt == 1).sum()) < 2:
            return None
        best = max(pairs, key=lambda p: loo_accuracy(lda, xt[:, list(p)], yt) or 0)
        m = lda().fit(xt[:, list(best)], yt)
        preds[test] = m.predict(x[test][:, list(best)])
    return 100 * balanced_accuracy_score(y, preds)


def lda():
    return make_pipeline(StandardScaler(), LinearDiscriminantAnalysis())


def centroid():
    return make_pipeline(StandardScaler(), NearestCentroid())


def main():
    root = Path(__file__).resolve().parents[1]
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default=str(root / "data/ultrasonic/dataset/all_samples.csv"))
    args = ap.parse_args()

    by_place = defaultdict(list)
    with open(args.csv, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["pipeline"] != "current (recorded v2)" or row["label"] not in ("dry", "damp"):
                continue
            by_place[row["place"]].append(row)

    out = root / "docs/charts/scatter"
    out.mkdir(parents=True, exist_ok=True)
    print(f"{'place':<10} {'dry':>3} {'damp':>4} {'LDA 5 feat':>10} {'2 feat, fair':>12} "
          f"{'2 feat, best':>12} {'centroid 2':>10}  best pair")
    results = []
    for place, rows in sorted(by_place.items()):
        y = np.array([1 if r["label"] == "damp" else 0 for r in rows])
        if min((y == 0).sum(), (y == 1).sum()) < MIN_PER_CLASS:
            continue
        x = np.array([[float(r[k]) for k in FEATURES] for r in rows])
        full = loo_accuracy(lda, x, y)
        pairs = []
        for i, j in itertools.combinations(range(len(FEATURES)), 2):
            pairs.append((loo_accuracy(lda, x[:, [i, j]], y), i, j))
        best_acc, bi, bj = max(pairs, key=lambda p: p[0] or 0)
        cen = loo_accuracy(centroid, x[:, [bi, bj]], y)
        fair = nested_pair_accuracy(x, y)
        fair_s = f"{fair:>11.0f}%" if fair is not None else f"{'n/a':>12}"
        pair_name = f"{SHORT[FEATURES[bi]]} x {SHORT[FEATURES[bj]]}"
        print(f"{place:<10} {(y == 0).sum():>3} {(y == 1).sum():>4} {full:>9.0f}% {fair_s} "
              f"{best_acc:>11.0f}% {cen:>9.0f}%  {pair_name}")
        results.append((place, x, y, bi, bj, full, best_acc))

    if not results:
        print("No place has enough labelled current-pipeline scans.")
        return

    cols = min(3, len(results))
    rows_n = (len(results) + cols - 1) // cols
    fig, axes = plt.subplots(rows_n, cols, figsize=(5 * cols, 4.2 * rows_n), squeeze=False)
    for ax, (place, x, y, bi, bj, full, best) in zip(axes.flat, results):
        ax.scatter(x[y == 0, bi], x[y == 0, bj], c="#2bb3c0", label="dry", edgecolors="white", s=46)
        ax.scatter(x[y == 1, bi], x[y == 1, bj], c="#e07a2f", label="damp", marker="^", edgecolors="white", s=52)
        ax.set_xlabel(SHORT[FEATURES[bi]])
        ax.set_ylabel(SHORT[FEATURES[bj]])
        ax.set_title(f"{place}: 5 features {full:.0f}%, this pair {best:.0f}%", fontsize=10)
        ax.legend(fontsize=8)
        ax.grid(alpha=0.25)
    for ax in list(axes.flat)[len(results):]:
        ax.axis("off")
    fig.suptitle("Dry vs damp per place, best pair of features (leave-one-out balanced accuracy)")
    fig.tight_layout()
    fig.savefig(out / "per_place_best_pair.png", dpi=130)

    n = len(FEATURES)
    fig, axes = plt.subplots(n, n, figsize=(13, 13))
    all_x = np.vstack([r[1] for r in results])
    all_y = np.concatenate([r[2] for r in results])
    for i in range(n):
        for j in range(n):
            ax = axes[i, j]
            if i == j:
                ax.hist(all_x[all_y == 0, j], bins=20, alpha=0.6, color="#2bb3c0")
                ax.hist(all_x[all_y == 1, j], bins=20, alpha=0.6, color="#e07a2f")
            else:
                ax.scatter(all_x[all_y == 0, j], all_x[all_y == 0, i], s=8, c="#2bb3c0")
                ax.scatter(all_x[all_y == 1, j], all_x[all_y == 1, i], s=8, c="#e07a2f", marker="^")
            if i == n - 1:
                ax.set_xlabel(SHORT[FEATURES[j]], fontsize=8)
            if j == 0:
                ax.set_ylabel(SHORT[FEATURES[i]], fontsize=8)
            ax.tick_params(labelsize=6)
    fig.suptitle("All places pooled: every feature pair (cyan dry, orange damp)")
    fig.tight_layout()
    fig.savefig(out / "all_pairs_pooled.png", dpi=110)
    print(f"Saved plots to {out}")


if __name__ == "__main__":
    main()
