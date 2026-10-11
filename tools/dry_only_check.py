"""How the dry-only score behaves while a place has only its first few dry examples.

For each place with 5+ dry and 3+ damp taught, train the dry-only path on the first n dry
examples (in teaching order) and score every later dry example and every damp example.
Reports how often a dry scan scores high (70+) and the balanced accuracy at 50, per n.

Usage: python tools/dry_only_check.py path/to/locations.json
"""
import json
import sys

import numpy as np

from scatter_compare import FEATURES, SC, W

G_DRY, G_MOIST = 0.35, 0.75


def calibrated(d, low, high):
    low = max(low, 1e-6)
    high = max(high, low * 1.01)
    if d <= low:
        return min(22.0 * d / low, 25.0)
    if d >= high:
        return 78.0 + 22.0 * (1.0 - np.exp(-(d - high) / high))
    return 22.0 + (d - low) / (high - low) * 56.0


def dist(x, mean, scales):
    return float(np.sqrt((W * ((x - mean) / scales) ** 2).sum()))


def current(x, dry):
    n = len(dry)
    mean = dry.mean(0)
    std = dry.std(0, ddof=1) if n >= 2 else None
    scales = np.maximum(SC * 0.4, std * 1.75) if n >= 2 else SC.copy()
    d = dist(x, mean, scales)
    if n >= 2:
        dd = [dist(r, mean, scales) for r in dry]
        dry_ref = max(G_DRY, np.percentile(dd, 75), 0.08)
        return calibrated(d, dry_ref, max(G_MOIST, dry_ref * 1.25))
    return 100.0 * (1.0 - np.exp(-0.4 * d))


def shrunk(prior_mult, k):
    """Spread estimate pulled toward a prior until enough dry examples exist; same formula for every n."""
    def f(x, dry):
        n = len(dry)
        mean = dry.mean(0)
        prior = SC * prior_mult
        var = ((n - 1) * (dry.var(0, ddof=1) if n >= 2 else 0) + k * prior ** 2) / (n - 1 + k)
        scales = np.maximum(SC * 0.4, np.sqrt(var) * 1.75)
        d = dist(x, mean, scales)
        if n >= 2:
            loo = [dist(dry[i], np.delete(dry, i, 0).mean(0), scales) for i in range(n)]
            dry_ref = max(G_DRY, np.percentile(loo, 75))
        else:
            dry_ref = G_DRY
        return calibrated(d, dry_ref, max(G_MOIST, dry_ref * 1.25))
    return f


def loose(x, dry):
    """The one-example formula kept for every n: default spreads, distance from the dry mean."""
    return 100.0 * (1.0 - np.exp(-0.4 * dist(x, dry.mean(0), SC)))


VARIANTS = {"current": current, "loose (1-example formula always)": loose}
for pm in (0.6, 1.0, 1.5):
    for k in (4, 8):
        VARIANTS[f"shrink p{pm} k{k}"] = shrunk(pm, k)


def main():
    places = []
    for loc in json.load(open(sys.argv[1], encoding="utf-8")):
        d = np.array([[f[k] for k in FEATURES] for f in loc.get("baselineFeatures", [])]).reshape(-1, 5)
        m = np.array([[f[k] for k in FEATURES] for f in loc.get("moistFeatures", [])]).reshape(-1, 5)
        if len(d) >= 5 and len(m) >= 3:
            places.append((loc["label"], d, m))
    print(f"{len(places)} places\n")
    print(f"{'variant':<18}" + "".join(f"  n={n}: dry70+ / bal" for n in range(1, 5)))
    for name, f in VARIANTS.items():
        row = f"{name:<18}"
        for n in range(1, 5):
            high, bal = [], []
            for _, d, m in places:
                train, later = d[:n], d[n:]
                sd = np.array([f(x, train) for x in later])
                sm = np.array([f(x, train) for x in m])
                high.append((sd >= 70).mean())
                bal.append(50 * ((sd < 50).mean() + (sm >= 50).mean()))
            row += f"  {100 * np.mean(high):6.0f}% / {np.mean(bal):3.0f}%"
        print(row)


if __name__ == "__main__":
    main()
