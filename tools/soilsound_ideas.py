#!/usr/bin/env python3
"""
Test SoilSound-inspired ideas on the phone's archived impulse responses before changing the app.

  1. Direct-path subtraction. SoilSound subtracts an open-air template of the speaker-to-mic
     leakage. We have no open-air recordings, so the template is the median echo of every scan
     from the OTHER places (the phone's own leakage is common to all of them); the five features
     are then recomputed on what is left.
  2. Frequency-dependent reflection. Their physics: a wetter, smoother surface reflects short
     wavelengths more strongly. Candidate: energy 5-10 kHz vs 1-5 kHz.
  3. Range gates. Their range profile becomes time gates after the direct arrival: energy
     1-5 ms and 5-15 ms after the peak, relative to the first millisecond.
  4. Template matching per place ("did this spot change from itself"): the held-out scan's
     smoothed log spectrum is compared with the median dry and median damp spectra of the
     other scans at the same place, with and without direct-path subtraction.

Everything is leave-one-out per place, balanced accuracy, same as tools/evaluate_extra_features.py.

Usage: python tools/soilsound_ideas.py [--include-implicit]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from evaluate_extra_features import (BASE_KEYS, ECHO_PRE_MS, base_features, bandlimit, classify_loo,  # noqa: E402
                                     direct_echo, envelope)

FOLDER = Path(__file__).resolve().parents[1] / "data/ultrasonic/dataset/ir"
SEG = 2048


def load(include_implicit: bool):
    accepted = {"dry", "damp"} | ({"damp_implicit"} if include_implicit else set())
    rows = []
    for meta_path in sorted(FOLDER.glob("*.json")):
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        wave = meta_path.with_suffix(".f32")
        if meta.get("label") not in accepted or not wave.exists():
            continue
        sr = int(meta["sampleRate"])
        echo = direct_echo(bandlimit(np.fromfile(wave, dtype="<f4").astype(np.float64), sr), sr)
        if int(ECHO_PRE_MS * sr / 1000) + 10 > len(echo):
            continue
        seg = np.zeros(SEG)
        seg[: min(SEG, len(echo))] = echo[:SEG]
        rows.append({"loc": meta["locationId"], "damp": meta["label"].startswith("damp"), "sr": sr,
                     "echo": seg / (np.linalg.norm(seg) + 1e-12)})
    return rows


def physics_features(seg: np.ndarray, sr: int) -> dict[str, float]:
    spec = np.abs(np.fft.rfft(seg)) ** 2
    f = np.fft.rfftfreq(len(seg), 1 / sr)
    mid = spec[(f >= 1000) & (f < 5000)].sum()
    high = spec[(f >= 5000) & (f < 10000)].sum()
    energy = seg ** 2
    p = int(np.argmax(envelope(seg)))
    ms = sr / 1000.0
    first = energy[p: p + int(ms)].sum() + 1e-20
    gate1 = energy[p + int(ms): p + int(5 * ms)].sum() + 1e-20
    gate2 = energy[p + int(5 * ms): p + int(15 * ms)].sum() + 1e-20
    return {
        "hfReflectDb": 10 * np.log10((high + 1e-20) / (mid + 1e-20)),
        "gate1to5Db": 10 * np.log10(gate1 / first),
        "gate5to15Db": 10 * np.log10(gate2 / first),
    }


def subtract_template(rows):
    """Direct-path removal with a template built only from other places' scans."""
    out = []
    locs = {r["loc"] for r in rows}
    templates = {l: np.median([r["echo"] for r in rows if r["loc"] != l], axis=0) for l in locs}
    for r in rows:
        t = templates[r["loc"]]
        gain = float(r["echo"] @ t / (t @ t + 1e-12))
        out.append(r["echo"] - gain * t)
    return out


def log_spectrum(seg: np.ndarray, sr: int) -> np.ndarray:
    f = np.fft.rfftfreq(len(seg), 1 / sr)
    band = (f >= 200) & (f <= 8000)
    db = 20 * np.log10(np.abs(np.fft.rfft(seg))[band] + 1e-9)
    db = np.convolve(db, np.ones(9) / 9, mode="same")
    return db - db.mean()


def template_match_loo(specs: np.ndarray, damp: np.ndarray) -> np.ndarray:
    preds = np.zeros(len(specs), dtype=bool)
    for i in range(len(specs)):
        keep = np.arange(len(specs)) != i
        d, m = specs[keep & ~damp], specs[keep & damp]
        if len(d) == 0 or len(m) == 0:
            continue
        dd = np.linalg.norm(specs[i] - np.median(d, axis=0))
        dm = np.linalg.norm(specs[i] - np.median(m, axis=0))
        preds[i] = dm < dd
    return preds


def score(rows, predict) -> tuple[float, float, int]:
    """Pooled balanced accuracy, mean per-place balanced accuracy, places used."""
    hits = {True: [0, 0], False: [0, 0]}
    per_place = []
    for loc in sorted({r["loc"] for r in rows}):
        idx = [i for i, r in enumerate(rows) if r["loc"] == loc]
        y = np.array([rows[i]["damp"] for i in idx])
        if y.sum() < 2 or (~y).sum() < 2:
            continue
        p = predict(idx, y)
        local = {True: [0, 0], False: [0, 0]}
        for t, q in zip(y, p):
            for bucket in (hits, local):
                bucket[bool(t)][0] += int(t == q)
                bucket[bool(t)][1] += 1
        per_place.append(50.0 * sum(c / n for c, n in local.values()))
    return 50.0 * sum(c / n for c, n in hits.values()), float(np.mean(per_place)), len(per_place)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--include-implicit", action="store_true")
    args = ap.parse_args()
    rows = load(args.include_implicit)
    print(f"{len(rows)} labelled scans ({sum(r['damp'] for r in rows)} damp) from {len({r['loc'] for r in rows})} places\n")

    residual = subtract_template(rows)
    for r, res in zip(rows, residual):
        r["base"] = base_features(r["echo"], r["sr"])
        r["phys"] = physics_features(r["echo"], r["sr"])
        r["resid"] = {f"resid_{k}": v for k, v in base_features(res, r["sr"]).items()}
        r["resid"]["residualDb"] = 10 * np.log10(float(res @ res) + 1e-20)
        r["spec"] = log_spectrum(r["echo"], r["sr"])
        r["spec_resid"] = log_spectrum(res, r["sr"])

    def lda(features_of):
        return lambda idx, y: classify_loo(np.array([features_of(rows[i]) for i in idx]), y)

    base = lambda r: [r["base"][k] for k in BASE_KEYS]
    trials = {
        "shipping 5 features": lda(base),
        "+ hfReflectDb (roughness physics)": lda(lambda r: base(r) + [r["phys"]["hfReflectDb"]]),
        "+ gate1to5Db (range gate)": lda(lambda r: base(r) + [r["phys"]["gate1to5Db"]]),
        "+ gate5to15Db (range gate)": lda(lambda r: base(r) + [r["phys"]["gate5to15Db"]]),
        "+ all three physics": lda(lambda r: base(r) + list(r["phys"].values())),
        "5 features after direct-path removal": lda(lambda r: [r["resid"][f"resid_{k}"] for k in BASE_KEYS]),
        "5 + 5 after removal": lda(lambda r: base(r) + [r["resid"][f"resid_{k}"] for k in BASE_KEYS]),
        "+ residual energy": lda(lambda r: base(r) + [r["resid"]["residualDb"]]),
        "template match, raw spectrum": lambda idx, y: template_match_loo(np.array([rows[i]["spec"] for i in idx]), y),
        "template match, after removal": lambda idx, y: template_match_loo(np.array([rows[i]["spec_resid"] for i in idx]), y),
    }
    print(f"{'method':40s} {'pooled':>7s} {'per-place mean':>15s}")
    for name, predict in trials.items():
        pooled, mean, n = score(rows, predict)
        print(f"{name:40s} {pooled:6.1f}% {mean:14.1f}%   ({n} places)")

    print("\nRepeated scans per spot: consecutive same-label scans at a place grouped, median of their features")
    for size in (1, 2, 3, 4):
        grouped = []
        for loc in sorted({r["loc"] for r in rows}):
            for label in (False, True):
                same = [r for r in rows if r["loc"] == loc and r["damp"] == label]
                for g in range(0, len(same) - size + 1, size):
                    grouped.append({"loc": loc, "damp": label,
                                    "base": np.median([base(s) for s in same[g:g + size]], axis=0)})
        pooled, mean, n = score(grouped, lambda idx, y: classify_loo(np.array([grouped[i]["base"] for i in idx]), y))
        print(f"  {size} scan(s) per reading: pooled {pooled:5.1f}%  per-place mean {mean:5.1f}%  "
              f"({len(grouped)} readings, {n} places)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
