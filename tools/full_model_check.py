"""Whole-dataset comparison of feature sets and of per-place vs shared (cross-place) models.

Features are recomputed from every archived impulse response the user labelled dry or damp.

  per-place : leave-one-out diagonal LDA inside each place (what the app does, simplified)
  shared    : one LDA on features expressed relative to the place's own dry median, trained on
              the other places only (leave-one-place-out), so it also works where only dry is taught
  combined  : mean of the per-place and shared damp probabilities

Score = mean over places (3+ dry and 3+ damp) of balanced accuracy.

Usage: python tools/full_model_check.py [--pull] [folder]
"""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

from evaluate_extra_features import (bandlimit, base_features, direct_echo, envelope, next_pow2,
                                     pull_from_phone)

BANDS = np.geomspace(200.0, 8000.0, 9)


def robust_features(echo: np.ndarray, sr: int) -> dict[str, float]:
    n = next_pow2(len(echo))
    power = np.abs(np.fft.rfft(echo, n)) ** 2
    f = np.fft.rfftfreq(n, 1 / sr)
    band = np.array([power[(f >= lo) & (f < hi)].sum() for lo, hi in zip(BANDS[:-1], BANDS[1:])])
    share = band / max(band.sum(), 1e-30)
    out = {f"band{i}": float(10 * np.log10(s + 1e-9)) for i, s in enumerate(share)}
    env = envelope(echo)
    p = int(np.argmax(env))
    e = echo[p:] ** 2
    sch = np.cumsum(e[::-1])[::-1]
    sch_db = 10 * np.log10(np.maximum(sch / max(sch[0], 1e-30), 1e-12))
    below10 = np.nonzero(sch_db <= -10)[0]
    out["edtMs"] = float((below10[0] if below10.size else len(sch_db)) * 6.0 / sr * 1000)
    ms = sr / 1000
    early = e[: int(2 * ms)].sum()
    late = e[int(2 * ms): int(20 * ms)].sum()
    out["earlyLateDb"] = float(10 * np.log10((early + 1e-30) / (late + 1e-30)))
    return out


BASE = ["resonanceFreqHz", "decayTimeMs", "spectralCentroidHz", "spectralSpreadHz", "spectralFlatness"]
ROBUST = [f"band{i}" for i in range(8)] + ["edtMs", "earlyLateDb"]
SETS = {
    "current 5": BASE,
    "robust (bands+EDT)": ROBUST,
    "current 5 + robust": BASE + ROBUST,
    "centroid+flatness+robust": ["spectralCentroidHz", "spectralFlatness"] + ROBUST,
}


def load(folders: list[Path]):
    latest = {}
    for folder in folders:
        for meta_path in folder.glob("*.json"):
            latest[meta_path.stem] = meta_path
    scans = []
    for meta_path in latest.values():
        folder = meta_path.parent
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        if meta.get("label") not in ("dry", "damp"):
            continue
        sr = int(meta["sampleRate"])
        waves = sorted(folder.glob(f"{meta['scanId']}.c*.f32")) or [meta_path.with_suffix(".f32")]
        feats = []
        for w in waves:
            if not w.exists():
                continue
            echo = direct_echo(bandlimit(np.fromfile(w, dtype="<f4").astype(np.float64), sr), sr)
            d = base_features(echo, sr)
            d.update(robust_features(echo, sr))
            feats.append(d)
        if not feats:
            continue
        merged = {k: float(np.median([d[k] for d in feats])) for k in feats[0]}
        scans.append((meta["locationId"], meta["label"] == "damp", merged))
    return scans


def lda_fit(x, y, shrink=0.3):
    mu0, mu1 = x[~y].mean(0), x[y].mean(0)
    resid = np.vstack([x[~y] - mu0, x[y] - mu1])
    cov = np.cov(resid.T) if len(resid) > 2 else np.eye(x.shape[1])
    cov = np.atleast_2d(cov)
    cov = (1 - shrink) * cov + shrink * np.diag(np.diag(cov)) + 1e-9 * np.eye(len(cov))
    w = np.linalg.solve(cov, mu1 - mu0)
    b = -w @ (mu0 + mu1) / 2
    sd = np.sqrt(max(w @ cov @ w, 1e-12))
    return w, b, sd


def prob(model, x):
    w, b, sd = model
    return 1 / (1 + np.exp(-np.clip((x @ w + b) / sd * 1.5, -30, 30)))


def per_place_probs(x, y):
    out = np.full(len(y), 0.5)
    mu, s = x.mean(0), x.std(0) + 1e-9
    z = (x - mu) / s
    for i in range(len(y)):
        keep = np.arange(len(y)) != i
        if y[keep].sum() < 1 or (~y[keep]).sum() < 1:
            continue
        out[i] = prob(lda_fit(z[keep], y[keep], shrink=0.7), z[i])
    return out


def relative(x, y, i_out=None):
    """Each scan relative to its place's dry median (excluding itself)."""
    rel = np.zeros_like(x)
    for i in range(len(y)):
        dry = [j for j in range(len(y)) if not y[j] and j != i]
        rel[i] = x[i] - (np.median(x[dry], 0) if dry else x[i])
    return rel


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folders", type=Path, nargs="*", default=[Path("data/ultrasonic/dataset/ir")],
                    help="archive copies, oldest first; a later copy's label wins")
    ap.add_argument("--pull", action="store_true", help="pull the phone archive into the last folder first")
    args = ap.parse_args()
    if args.pull:
        pull_from_phone(args.folders[-1])
    scans = load(args.folders)
    by_place = defaultdict(list)
    for loc, damp, f in scans:
        by_place[loc].append((damp, f))
    print(f"{len(scans)} labelled scans, {len(by_place)} places")

    for name, keys in SETS.items():
        data = {}
        for loc, items in by_place.items():
            x = np.array([[f[k] for k in keys] for _, f in items])
            y = np.array([d for d, _ in items])
            if (~y).sum() >= 2:
                data[loc] = (x, y, relative(x, y))
        scale = np.std(np.vstack([r[~y] for x, y, r in data.values()]), 0) + 1e-9
        evaluable = [l for l, (x, y, _) in data.items() if y.sum() >= 3 and (~y).sum() >= 3]
        res = defaultdict(list)
        for loc in evaluable:
            x, y, r = data[loc]
            tr = [(r2 / scale, y2) for l2, (_, y2, r2) in data.items() if l2 != loc and y2.sum() >= 1]
            shared = lda_fit(np.vstack([a for a, _ in tr]), np.concatenate([b for _, b in tr]))
            ps = prob(shared, r / scale)
            pp = per_place_probs(x, y)
            for tag, p in (("per-place", pp), ("shared", ps), ("combined", (pp + ps) / 2)):
                pred = p >= 0.5
                res[tag].append(50 * ((pred[y]).mean() + (~pred[~y]).mean()))
        print(f"\n{name}  ({len(evaluable)} places)")
        for tag, v in res.items():
            print(f"   {tag:<10} {np.mean(v):5.1f}%   (places at 70%+: {sum(a >= 70 for a in v)}/{len(v)})")


if __name__ == "__main__":
    main()
