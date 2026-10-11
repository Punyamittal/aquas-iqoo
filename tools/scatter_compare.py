"""Compare the app's scorer with simple scatter / distance methods, over every feature combination.

Each place is scored on its own taught scans (current pipeline only), leave-one-out:
hide one scan, fit on the rest, check whether it comes out dry or damp. Accuracy is balanced
(the mean of the dry hit rate and the damp hit rate), the same as the app's self-check.

Methods
  current      Port of AnomalyScorer.score with dry + damp examples (trained weights, cleaning,
               median centres, LDA axis, percentile calibration). Damp when score >= 50.
  centroid     Standardise, then nearer of the dry mean and the damp mean.
  median       Same with medians (less affected by a few odd scans).
  mahalanobis  Distance that accounts for correlated features (pooled covariance, shrunk).
  knn3/knn5    Majority of the 3 / 5 nearest taught scans.
  hybrid       Average of the current score and the centroid distance ratio.

Feature selection is reported three ways:
  all five     no selection,
  per place    best combination picked on the training scans only (nested, so not optimistic),
  one global   one combination for every place, picked on the other places (leave-one-place-out).

Usage: python tools/scatter_compare.py
"""
import csv
import itertools
import json
import time
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
FEATURES = ["resonanceFreqHz", "decayTimeMs", "spectralCentroidHz", "spectralSpreadHz", "spectralFlatness"]
SHORT = ["resonance", "decay", "centroid", "spread", "flatness"]
MIN_PER_CLASS = 3
COMBOS = [c for r in range(1, 6) for c in itertools.combinations(range(5), r)]

_weights = json.loads((ROOT / "app/src/main/assets/ultrasonic/trained_weights.json").read_text())
W = np.array(_weights["featureWeights"])
SC = np.array(_weights["featureScales"])
DRY_Q = _weights.get("dryPercentile", 0.75)
MOIST_Q = _weights.get("moistPercentile", 0.30)
DEDUPE = np.array([80.0, 1.5, 80.0, 80.0, 0.02])


# ---- port of AnomalyScorer (dry + damp path) --------------------------------------------

def _dedupe(x, eps):
    kept = []
    for row in x:
        if not any(np.all(np.abs(row - k) <= eps) for k in kept):
            kept.append(row)
    return np.array(kept)


def _vars(x, mu, sc):
    if len(x) < 2:
        return (sc * 0.5) ** 2
    return ((x - mu) ** 2).sum(axis=0) / (len(x) - 1)


def _prepare(dry, moist, sc, eps):
    dry, moist = _dedupe(dry, eps), _dedupe(moist, eps)
    if len(dry) == 0 or len(moist) == 0:
        return dry, moist
    mu_d, mu_m = np.median(dry, axis=0), np.median(moist, axis=0)
    both = np.vstack([dry, moist])
    scales = np.maximum.reduce([np.sqrt(_vars(both, both.mean(axis=0), sc)), sc * 0.25, np.full(len(sc), 1e-6)])

    def dist(a, mu):
        return np.sqrt((((a - mu) / scales) ** 2).sum(axis=-1))

    dc = dry[dist(dry, mu_d) <= dist(dry, mu_m)]
    mc = moist[dist(moist, mu_m) <= dist(moist, mu_d)]
    return (dc, mc) if len(dc) and len(mc) else (dry, moist)


def _lda_moistness(x, dry, moist, w, sc):
    mu_d, mu_m = np.median(dry, axis=0), np.median(moist, axis=0)
    vd, vm = _vars(dry, mu_d, sc), _vars(moist, mu_m, sc)
    nd, nm = len(dry), len(moist)
    pooled = np.maximum.reduce([
        (max(nd - 1, 0) * vd + max(nm - 1, 0) * vm) / max(nd + nm - 2, 1),
        (sc * 0.15) ** 2,
        np.full(len(sc), 1e-9),
    ])
    direction = (mu_m - mu_d) / pooled
    mid = 0.5 * (mu_d + mu_m)
    half = (w * direction * (mu_m - mu_d) * 0.5).sum()
    if abs(half) < 1e-12:
        return np.full(len(np.atleast_2d(x)), 0.5)
    return 0.5 * ((np.atleast_2d(x) - mid) @ (w * direction) / half + 1.0)


def current_score_percent(x, dry, moist, idx):
    w, sc, eps = W[list(idx)], SC[list(idx)], DEDUPE[list(idx)]
    dc, mc = _prepare(dry, moist, sc, eps)
    m = _lda_moistness(x, dc, mc, w, sc)
    dry_ref = np.quantile(_lda_moistness(dc, dc, mc, w, sc), DRY_Q)
    moist_ref = max(np.quantile(_lda_moistness(mc, dc, mc, w, sc), MOIST_Q), dry_ref + 0.08)
    low = max(dry_ref, 1e-6)
    high = max(moist_ref, low * 1.01)
    d = np.maximum(m, 0.0)
    pct = np.where(d <= low, np.clip(22 * d / low, 0, 25),
                   np.where(d >= high, 78 + 22 * (1 - np.exp(-(d - high) / high)),
                            22 + (d - low) / (high - low) * 56))
    return pct


# ---- scatter / distance methods ---------------------------------------------------------

def _standardise(dry, moist):
    both = np.vstack([dry, moist])
    s = both.std(axis=0, ddof=1)
    s[s < 1e-9] = 1.0
    return both.mean(axis=0), s


def centroid_ratio(x, dry, moist, use_median=False):
    mu, s = _standardise(dry, moist)
    f = np.median if use_median else np.mean
    cd, cm = (f(dry, axis=0) - mu) / s, (f(moist, axis=0) - mu) / s
    z = (np.atleast_2d(x) - mu) / s
    dd = np.linalg.norm(z - cd, axis=1)
    dm = np.linalg.norm(z - cm, axis=1)
    return dd / (dd + dm + 1e-12)


def predict(method, x, dry, moist, idx):
    x = np.atleast_2d(x)
    if method == "current":
        return current_score_percent(x, dry, moist, idx) >= 50
    if method == "centroid":
        return centroid_ratio(x, dry, moist) > 0.5
    if method == "median":
        return centroid_ratio(x, dry, moist, use_median=True) > 0.5
    if method == "hybrid":
        cur = current_score_percent(x, dry, moist, idx) / 100.0
        return 0.5 * cur + 0.5 * centroid_ratio(x, dry, moist) > 0.5
    if method == "mahalanobis":
        mu, s = _standardise(dry, moist)
        zd, zm, z = (dry - mu) / s, (moist - mu) / s, (x - mu) / s
        cov = np.atleast_2d(((len(zd) - 1) * np.atleast_2d(np.cov(zd.T)) + (len(zm) - 1) * np.atleast_2d(np.cov(zm.T)))
                            / max(len(zd) + len(zm) - 2, 1))
        cov = 0.7 * cov + 0.3 * np.eye(len(cov))
        inv = np.linalg.inv(cov)
        a, b = z - zd.mean(axis=0), z - zm.mean(axis=0)
        return np.einsum("ij,jk,ik->i", b, inv, b) < np.einsum("ij,jk,ik->i", a, inv, a)
    if method.startswith("knn"):
        k = int(method[3:])
        mu, s = _standardise(dry, moist)
        train = (np.vstack([dry, moist]) - mu) / s
        lab = np.r_[np.zeros(len(dry)), np.ones(len(moist))]
        z = (x - mu) / s
        d = np.linalg.norm(z[:, None, :] - train[None, :, :], axis=2)
        nearest = np.argsort(d, axis=1)[:, :min(k, len(train))]
        return lab[nearest].mean(axis=1) > 0.5
    raise ValueError(method)


METHODS = ["current", "hybrid", "centroid", "median", "mahalanobis", "knn3", "knn5"]


def loo_balanced(method, x, y, idx):
    preds = np.zeros(len(y), dtype=bool)
    cols = list(idx)
    for i in range(len(y)):
        mask = np.arange(len(y)) != i
        xt, yt = x[mask][:, cols], y[mask]
        if (yt == 0).sum() < 2 or (yt == 1).sum() < 2:
            return None
        preds[i] = predict(method, x[i, cols], xt[yt == 0], xt[yt == 1], idx)[0]
    return 50.0 * ((~preds[y == 0]).mean() + preds[y == 1].mean())


def nested_balanced(method, x, y):
    """Pick the combination on the training scans only, then test the held-out scan."""
    preds = np.zeros(len(y), dtype=bool)
    for i in range(len(y)):
        mask = np.arange(len(y)) != i
        xt, yt = x[mask], y[mask]
        best = max(COMBOS, key=lambda c: loo_balanced(method, xt, yt, c) or 0)
        cols = list(best)
        preds[i] = predict(method, x[i, cols], xt[yt == 0][:, cols], xt[yt == 1][:, cols], best)[0]
    return 50.0 * ((~preds[y == 0]).mean() + preds[y == 1].mean())


def load_places():
    by_place = defaultdict(list)
    with open(ROOT / "data/ultrasonic/dataset/all_samples.csv", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["pipeline"] == "current (recorded v2)" and row["label"] in ("dry", "damp"):
                by_place[row["place"]].append(row)
    places = {}
    for place, rows in sorted(by_place.items()):
        y = np.array([1 if r["label"] == "damp" else 0 for r in rows])
        if min((y == 0).sum(), (y == 1).sum()) >= MIN_PER_CLASS:
            places[place] = (np.array([[float(r[k]) for k in FEATURES] for r in rows]), y)
    return places


def name(c):
    return "+".join(SHORT[i] for i in c)


def main():
    t0 = time.time()
    places = load_places()
    names = list(places)
    print(f"Places with >= {MIN_PER_CLASS} dry and damp current-pipeline scans: {', '.join(names)}\n")

    grid = {m: {p: {c: loo_balanced(m, *places[p], c) for c in COMBOS} for p in names} for m in METHODS}

    all5 = {m: {p: grid[m][p][COMBOS[-1]] for p in names} for m in METHODS}
    nested = {m: {p: nested_balanced(m, *places[p]) for p in names} for m in METHODS}
    lopo, lopo_combo = {}, {}
    for m in METHODS:
        lopo[m], lopo_combo[m] = {}, {}
        for p in names:
            others = [q for q in names if q != p]
            best = max(COMBOS, key=lambda c: np.mean([grid[m][q][c] for q in others]))
            lopo[m][p], lopo_combo[m][p] = grid[m][p][best], best
    overall_best = {m: max(COMBOS, key=lambda c: np.mean([grid[m][p][c] for p in names])) for m in METHODS}

    def table(title, data):
        print(title)
        print(f"{'method':<12}" + "".join(f"{p:>10}" for p in names) + f"{'mean':>8}")
        for m in METHODS:
            vals = [data[m][p] for p in names]
            print(f"{m:<12}" + "".join(f"{v:>9.0f}%" for v in vals) + f"{np.mean(vals):>7.0f}%")
        print()

    table("A. All five features (no selection)", all5)
    table("B. Best combination per place, picked on training scans only (fair)", nested)
    table("C. One combination for every place, picked on the other places (fair)", lopo)
    print("Combination each method would use for every place (best mean over all places, optimistic):")
    for m in METHODS:
        c = overall_best[m]
        print(f"  {m:<12} {name(c):<40} mean {np.mean([grid[m][p][c] for p in names]):.0f}%")
    print(f"\nTook {time.time() - t0:.0f} s")

    out = ROOT / "docs/charts/scatter"
    out.mkdir(parents=True, exist_ok=True)
    fig, ax = plt.subplots(figsize=(10, 4.8))
    width = 0.11
    for k, m in enumerate(METHODS):
        vals = [nested[m][p] for p in names] + [np.mean([nested[m][p] for p in names])]
        ax.bar(np.arange(len(names) + 1) + (k - len(METHODS) / 2) * width, vals, width, label=m)
    ax.axhline(70, color="grey", ls="--", lw=1)
    ax.text(len(names) + 0.45, 71, "trusted (70%)", fontsize=8, color="grey", ha="right")
    ax.set_xticks(np.arange(len(names) + 1))
    ax.set_xticklabels(names + ["mean"])
    ax.set_ylabel("leave-one-out balanced accuracy %")
    ax.set_ylim(0, 105)
    ax.set_title("Per place, best feature combination picked fairly on training scans only")
    ax.legend(ncol=4, fontsize=8)
    fig.tight_layout()
    fig.savefig(out / "method_comparison.png", dpi=130)
    print(f"Saved {out / 'method_comparison.png'}")


if __name__ == "__main__":
    main()
