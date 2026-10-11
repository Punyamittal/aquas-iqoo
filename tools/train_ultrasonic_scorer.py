#!/usr/bin/env python3
"""
Train AquaScope moisture scorer (LDA + teaching cleanup) from locations.json.

Pull (cmd.exe — PowerShell redirect corrupts UTF-16):
  cmd /c "adb exec-out run-as com.aquascope cat files/aquascope_data/locations.json > data\\ultrasonic\\dataset\\locations.json"

Train:
  python tools/train_ultrasonic_scorer.py data/ultrasonic/dataset/locations.json
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
from pathlib import Path
from typing import Any

FEATURE_KEYS = [
    "resonanceFreqHz",
    "decayTimeMs",
    "spectralCentroidHz",
    "spectralSpreadHz",
    "spectralFlatness",
]

DEFAULT_WEIGHTS = [2.5, 1.2, 2.0, 0.5, 1.5]
DEFAULT_SCALES = [2500.0, 40.0, 2500.0, 1200.0, 0.35]
DEDUPE_EPS = [80.0, 1.5, 80.0, 80.0, 0.02]

DEFAULT_DRY_Q = 0.70
DEFAULT_MOIST_Q = 0.30
DEFAULT_USE_MEDIAN = True


def vec(f: dict[str, Any]) -> list[float]:
    return [float(f.get(k, 0.0) or 0.0) for k in FEATURE_KEYS]


def mean(rows: list[list[float]]) -> list[float]:
    n = len(rows)
    d = len(rows[0])
    out = [0.0] * d
    for r in rows:
        for i in range(d):
            out[i] += r[i]
    return [x / n for x in out]


def median(rows: list[list[float]]) -> list[float]:
    d = len(rows[0])
    out: list[float] = []
    for i in range(d):
        col = sorted(r[i] for r in rows)
        mid = len(col) // 2
        out.append(col[mid] if len(col) % 2 else 0.5 * (col[mid - 1] + col[mid]))
    return out


def center(rows: list[list[float]], use_median: bool) -> list[float]:
    return median(rows) if use_median else mean(rows)


def variance(rows: list[list[float]], mu: list[float]) -> list[float]:
    n = len(rows)
    if n < 2:
        return [(DEFAULT_SCALES[i] * 0.5) ** 2 for i in range(len(mu))]
    acc = [0.0] * len(mu)
    for r in rows:
        for i in range(len(mu)):
            d = r[i] - mu[i]
            acc[i] += d * d
    return [a / (n - 1) for a in acc]


def dedupe(rows: list[list[float]]) -> list[list[float]]:
    kept: list[list[float]] = []
    for r in rows:
        if any(all(abs(r[i] - k[i]) <= DEDUPE_EPS[i] for i in range(5)) for k in kept):
            continue
        kept.append(r)
    return kept


def clean_pair(
    dry: list[list[float]], moist: list[list[float]], use_median: bool
) -> tuple[list[list[float]], list[list[float]]]:
    dry, moist = dedupe(dry), dedupe(moist)
    if not dry or not moist:
        return dry, moist
    mu_d = center(dry, use_median)
    mu_m = center(moist, use_median)
    all_rows = dry + moist
    mu_a = mean(all_rows)
    vd = variance(all_rows, mu_a)
    scales = [max(math.sqrt(vd[i]), DEFAULT_SCALES[i] * 0.25, 1e-6) for i in range(5)]

    def dist(sample: list[float], mu: list[float]) -> float:
        return math.sqrt(
            sum(((sample[i] - mu[i]) / scales[i]) ** 2 for i in range(5))
        )

    dry_c = [x for x in dry if dist(x, mu_d) <= dist(x, mu_m)]
    moist_c = [x for x in moist if dist(x, mu_m) <= dist(x, mu_d)]
    if dry_c and moist_c:
        return dry_c, moist_c
    return dry, moist


def load_paired(path: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    raw = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(raw, list):
        raise SystemExit("locations.json must be a JSON array of ScanLocation")
    groups: list[dict[str, Any]] = []
    meta: dict[str, Any] = {
        "locations": 0,
        "dry": 0,
        "moist": 0,
        "history": 0,
        "paired_locations": 0,
        "by_location": [],
    }
    for loc in raw:
        meta["locations"] += 1
        label = loc.get("label") or loc.get("id") or "?"
        d = [vec(x) for x in (loc.get("baselineFeatures") or [])]
        m = [vec(x) for x in (loc.get("moistFeatures") or [])]
        hist = loc.get("scanHistory") or []
        meta["dry"] += len(d)
        meta["moist"] += len(m)
        meta["history"] += len(hist)
        paired = len(d) >= 1 and len(m) >= 1
        if paired:
            meta["paired_locations"] += 1
            groups.append({"label": label, "dry": d, "moist": m})
        meta["by_location"].append(
            {
                "label": label,
                "dry": len(d),
                "moist": len(m),
                "history": len(hist),
                "paired": paired,
            }
        )
    meta["train_mode"] = "lda_clean_v3"
    return groups, meta


def lda_moistness(
    sample: list[float],
    dry: list[list[float]],
    moist: list[list[float]],
    weights: list[float],
    floor: list[float],
    use_median: bool,
) -> float:
    dry_mu = center(dry, use_median)
    moist_mu = center(moist, use_median)
    vd = variance(dry, dry_mu)
    vm = variance(moist, moist_mu)
    n_d, n_m = len(dry), len(moist)
    num = 0.0
    half_sep = 0.0
    for i in range(5):
        pooled = max(
            ((max(n_d - 1, 0) * vd[i] + max(n_m - 1, 0) * vm[i]) / max(n_d + n_m - 2, 1)),
            (floor[i] * 0.15) ** 2,
            1e-9,
        )
        direction = (moist_mu[i] - dry_mu[i]) / pooled
        mid = 0.5 * (dry_mu[i] + moist_mu[i])
        num += weights[i] * direction * (sample[i] - mid)
        half_sep += weights[i] * direction * (moist_mu[i] - dry_mu[i]) * 0.5
    if abs(half_sep) < 1e-12:
        return 0.5
    lda = num / half_sep  # dry≈-1, moist≈+1
    return 0.5 * (lda + 1.0)


def calib(v: float, low: float, high: float) -> float:
    span = max(high - low, 1e-6)
    if v <= low:
        below = max(0.0, (low - v) / span)
        return max(0.0, min(25.0, 22.0 * (1.0 - min(1.0, below))))
    if v >= high:
        extra = (v - high) / span
        return min(100.0, 78.0 + 22.0 * (1.0 - math.exp(-extra)))
    t = (v - low) / span
    return 22.0 + t * 56.0


def quantile(vals: list[float], q: float) -> float:
    if not vals:
        return 0.0
    s = sorted(vals)
    if len(s) == 1:
        return s[0]
    idx = q * (len(s) - 1)
    lo = int(math.floor(idx))
    hi = int(math.ceil(idx))
    if lo == hi:
        return s[lo]
    t = idx - lo
    return s[lo] * (1.0 - t) + s[hi] * t


def score_one(
    sample: list[float],
    dry: list[list[float]],
    moist: list[list[float]],
    weights: list[float],
    floor: list[float],
    dry_q: float,
    moist_q: float,
    use_median: bool,
) -> float | None:
    dry_c, moist_c = clean_pair(dry, moist, use_median)
    if not dry_c or not moist_c:
        return None
    moistness = lda_moistness(sample, dry_c, moist_c, weights, floor, use_median)
    dry_vals = [lda_moistness(x, dry_c, moist_c, weights, floor, use_median) for x in dry_c]
    moist_vals = [lda_moistness(x, dry_c, moist_c, weights, floor, use_median) for x in moist_c]
    dry_ref = quantile(dry_vals, dry_q)
    moist_ref = max(dry_ref + 0.08, quantile(moist_vals, moist_q))
    return calib(moistness, dry_ref, moist_ref)


def evaluate_groups(
    groups: list[dict[str, Any]],
    weights: list[float],
    floor: list[float],
    dry_q: float = DEFAULT_DRY_Q,
    moist_q: float = DEFAULT_MOIST_Q,
    use_median: bool = DEFAULT_USE_MEDIAN,
    loo: bool = False,
    dry_thr: float = 42.0,
    moist_thr: float = 50.0,
) -> dict[str, Any]:
    dry_ok = moist_ok = dry_n = moist_n = 0
    dry_scores: list[float] = []
    moist_scores: list[float] = []
    for g in groups:
        dry, moist = g["dry"], g["moist"]
        for i, x in enumerate(dry):
            dd = dry[:i] + dry[i + 1 :] if loo and len(dry) > 1 else dry
            s = score_one(x, dd, moist, weights, floor, dry_q, moist_q, use_median)
            if s is None:
                continue
            dry_n += 1
            dry_scores.append(s)
            if s < dry_thr:
                dry_ok += 1
        for i, x in enumerate(moist):
            mm = moist[:i] + moist[i + 1 :] if loo and len(moist) > 1 else moist
            s = score_one(x, dry, mm, weights, floor, dry_q, moist_q, use_median)
            if s is None:
                continue
            moist_n += 1
            moist_scores.append(s)
            if s > moist_thr:
                moist_ok += 1
    dry_rate = dry_ok / max(dry_n, 1)
    moist_rate = moist_ok / max(moist_n, 1)
    return {
        "dry_class_rate": dry_rate,
        "moist_class_rate": moist_rate,
        "balanced_acc": 0.5 * (dry_rate + moist_rate),
        "dry_mean_score": sum(dry_scores) / max(len(dry_scores), 1),
        "moist_mean_score": sum(moist_scores) / max(len(moist_scores), 1),
        "dry_n": dry_n,
        "moist_n": moist_n,
        "dry_ref": 0.35,
        "moist_ref": 0.75,
        "loo": loo,
    }


def fisher_weights(groups: list[dict[str, Any]]) -> list[float]:
    dry = [x for g in groups for x in clean_pair(g["dry"], g["moist"], True)[0]]
    moist = [x for g in groups for x in clean_pair(g["dry"], g["moist"], True)[1]]
    if len(dry) < 2 or len(moist) < 2:
        return list(DEFAULT_WEIGHTS)
    mu_d, mu_m = mean(dry), mean(moist)
    vd, vm = variance(dry, mu_d), variance(moist, mu_m)
    scores = []
    for i in range(5):
        pooled = math.sqrt(vd[i] + vm[i]) + 1e-9
        scores.append(abs(mu_m[i] - mu_d[i]) / pooled)
    mx = max(scores) or 1.0
    w = [0.25 + 3.0 * (s / mx) for s in scores]
    w[0] = max(w[0], 2.0)
    w[2] = max(w[2], 1.5)
    w[4] = max(w[4], 1.5)
    return w


def pooled_floor(groups: list[dict[str, Any]]) -> list[float]:
    dry: list[list[float]] = []
    moist: list[list[float]] = []
    for g in groups:
        d, m = clean_pair(g["dry"], g["moist"], True)
        dry.extend(d)
        moist.extend(m)
    if len(dry) < 1 or len(moist) < 1:
        return list(DEFAULT_SCALES)
    mu_d, mu_m = mean(dry), mean(moist)
    sd = [math.sqrt(v) for v in variance(dry, mu_d)]
    out = []
    for i in range(5):
        sep = abs(mu_m[i] - mu_d[i])
        out.append(max(DEFAULT_SCALES[i] * 0.5, sep * 0.9, sd[i] * 1.2, 1e-6))
    return out


def metric_key(m: dict[str, Any]) -> tuple[float, float, float]:
    return (
        m["balanced_acc"],
        min(m["dry_class_rate"], m["moist_class_rate"]),
        m["moist_class_rate"],
    )


def random_search(
    groups: list[dict[str, Any]],
    floor: list[float],
    seeds: list[list[float]],
    trials: int = 220,
) -> tuple[dict[str, Any], dict[str, Any]]:
    random.seed(7)
    best_cfg: dict[str, Any] | None = None
    best_m: dict[str, Any] | None = None
    dry_qs = [0.55, 0.65, 0.70, 0.75]
    moist_qs = [0.20, 0.25, 0.30, 0.35]

    def consider(cfg: dict[str, Any]) -> None:
        nonlocal best_cfg, best_m
        m = evaluate_groups(
            groups,
            cfg["weights"],
            floor,
            dry_q=cfg["dryPercentile"],
            moist_q=cfg["moistPercentile"],
            use_median=cfg["useMedian"],
            loo=True,
        )
        if best_m is None or metric_key(m) > metric_key(best_m):
            best_cfg, best_m = cfg, m

    for w in seeds:
        for use_median in (True, False):
            consider(
                {
                    "weights": list(w),
                    "dryPercentile": DEFAULT_DRY_Q,
                    "moistPercentile": DEFAULT_MOIST_Q,
                    "distanceBlend": 0.0,
                    "useMedian": use_median,
                }
            )

    for _ in range(trials):
        base = random.choice(seeds)
        w = [max(0.15, base[i] * random.uniform(0.4, 2.8)) for i in range(5)]
        w[0] = max(w[0], 1.5)
        w[4] = max(w[4], 1.2)
        consider(
            {
                "weights": w,
                "dryPercentile": random.choice(dry_qs),
                "moistPercentile": random.choice(moist_qs),
                "distanceBlend": 0.0,
                "useMedian": random.choice([True, False]),
            }
        )

    assert best_cfg is not None and best_m is not None
    return best_cfg, best_m


def optimize(groups: list[dict[str, Any]], floor: list[float]) -> tuple[dict[str, Any], dict, dict]:
    fisher = fisher_weights(groups)
    seeds = [
        list(DEFAULT_WEIGHTS),
        fisher,
        [4.0, 0.8, 3.0, 0.5, 3.5],
        [5.5, 0.6, 2.5, 0.4, 4.5],
        [3.0, 1.0, 2.5, 0.7, 2.5],
    ]
    before = evaluate_groups(
        groups,
        DEFAULT_WEIGHTS,
        floor,
        dry_q=0.70,
        moist_q=0.25,
        use_median=False,
        loo=True,
    )
    # Pretend "before" without cleaning by temporarily evaluating with dirty path:
    # already using clean inside score_one — baseline uses default weights only.
    cfg, after_loo = random_search(groups, floor, seeds, trials=220)
    after = evaluate_groups(
        groups,
        cfg["weights"],
        floor,
        dry_q=cfg["dryPercentile"],
        moist_q=cfg["moistPercentile"],
        use_median=cfg["useMedian"],
        loo=False,
    )
    after = dict(after)
    after["chosen"] = "lda_clean_search"
    after["loo_balanced_acc"] = after_loo["balanced_acc"]
    after["loo_dry_class_rate"] = after_loo["dry_class_rate"]
    after["loo_moist_class_rate"] = after_loo["moist_class_rate"]
    after["useMedian"] = cfg["useMedian"]
    after["distanceBlend"] = 0.0
    after["dryPercentile"] = cfg["dryPercentile"]
    after["moistPercentile"] = cfg["moistPercentile"]
    return cfg, before, after


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("locations_json", type=Path, nargs="?", default=Path("data/ultrasonic/dataset/locations.json"))
    ap.add_argument("--out", type=Path, default=Path("data/ultrasonic/trained_weights.json"))
    ap.add_argument("--assets", type=Path, default=Path("app/src/main/assets/ultrasonic/trained_weights.json"))
    args = ap.parse_args()

    if not args.locations_json.exists():
        print(f"Missing {args.locations_json}", file=sys.stderr)
        return 2
    if args.locations_json.stat().st_size < 50:
        print(f"locations.json looks empty ({args.locations_json.stat().st_size} bytes)", file=sys.stderr)
        return 2

    groups, meta = load_paired(args.locations_json)
    print(json.dumps(meta, indent=2), flush=True)
    if len(groups) < 1:
        print("Need at least one paired dry+moist location.", file=sys.stderr)
        return 3

    floor = pooled_floor(groups)
    cfg, before, after = optimize(groups, floor)

    payload = {
        "featureKeys": FEATURE_KEYS,
        "featureWeights": [round(x, 4) for x in cfg["weights"]],
        "featureScales": [round(x, 4) for x in floor],
        "dryRef": round(after["dry_ref"], 4),
        "moistRef": round(after["moist_ref"], 4),
        "dryPercentile": cfg["dryPercentile"],
        "moistPercentile": cfg["moistPercentile"],
        "distanceBlend": 0.0,
        "useMedian": cfg["useMedian"],
        "scoring": "lda_clean_v3",
        "metrics": {
            "before_loo": {k: (round(v, 4) if isinstance(v, float) else v) for k, v in before.items()},
            "after": {k: (round(v, 4) if isinstance(v, float) else v) for k, v in after.items()},
        },
        "dataset": meta,
        "source": str(args.locations_json).replace("\\", "/"),
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    args.assets.parent.mkdir(parents=True, exist_ok=True)
    args.assets.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    print("\nBaseline LOO metrics:", json.dumps(before, indent=2))
    print("Chosen metrics:", json.dumps(after, indent=2))
    print(f"\nWrote {args.out}")
    print(f"Wrote {args.assets}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
