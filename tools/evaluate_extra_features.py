#!/usr/bin/env python3
"""
Test whether extra acoustic features improve dry-vs-damp separation before adding them to the app.

The phone keeps the raw impulse response of every scan (ImpulseArchive.kt) and labels it when the
user teaches the scan as dry or damp. This script recomputes the five shipping features from those
impulse responses, adds candidate features, and compares leave-one-out accuracy per location.

Pull from a connected phone and evaluate:
  python tools/evaluate_extra_features.py --pull

Evaluate an already-pulled folder:
  python tools/evaluate_extra_features.py data/ultrasonic/dataset/ir
"""

from __future__ import annotations

import argparse
import io
import json
import os
import subprocess
import sys
import tarfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np

BAND_MIN_HZ = 200.0
BAND_MAX_HZ = 8000.0

# Must match Deconvolution.BAND_*_HZ and ImpulseWindow.PRE_MS / POST_MS.
ECHO_BAND_LOW_HZ = 150.0
ECHO_BAND_HIGH_HZ = 10000.0
ECHO_PRE_MS = 1.0
ECHO_POST_MS = 40.0
FEATURE_VERSION = 2

BASE_KEYS = [
    "resonanceFreqHz",
    "decayTimeMs",
    "spectralCentroidHz",
    "spectralSpreadHz",
    "spectralFlatness",
]

CANDIDATES = {
    "resonanceQ": "Sharpness of the main resonance (f0 / -3 dB bandwidth). Damping lowers it.",
    "lowHighRatioDb": "Energy 200-1500 Hz vs 1500-8000 Hz. Added water mass shifts energy down.",
    "earlyLateRatioDb": "Energy in the first 2 ms after the peak vs 2-20 ms. Damping kills the tail.",
    "rolloff85Hz": "Frequency below which 85% of in-band energy sits.",
    "decaySlopeDbPerMs": "Straight-line fit of the envelope decay over 10 ms. Steadier than T20 x 3.",
}

# A candidate ships only if it clears all three.
MIN_GAIN_POINTS = 3.0
MIN_LABELLED_SCANS = 30
MIN_LOCATIONS = 3


@dataclass
class Scan:
    scan_id: str
    location_id: str
    label: str
    features: dict[str, float]
    phone_features: dict[str, float]


# ---------------------------------------------------------------- shipping features (Kotlin port)

def next_pow2(n: int) -> int:
    return 1 << max(0, (n - 1).bit_length())


def analysis_bins(half_n: int, sr: int) -> tuple[int, int]:
    max_hz = min(BAND_MAX_HZ, sr / 2.0 * 0.9)
    lo = max(int(BAND_MIN_HZ * half_n * 2 / sr), 1)
    hi = min(max(int(max_hz * half_n * 2 / sr), lo + 1), half_n - 1)
    return lo, hi


def spectrum(ir: np.ndarray) -> tuple[np.ndarray, int]:
    n = next_pow2(len(ir))
    mag = np.abs(np.fft.fft(ir, n))
    return mag, n // 2


def resonance(mag: np.ndarray, half_n: int, sr: int) -> float:
    lo, hi = analysis_bins(half_n, sr)
    peak = lo + int(np.argmax(mag[lo : hi + 1]))
    if lo < peak < hi:
        y0, y1, y2 = mag[peak - 1], mag[peak], mag[peak + 1]
        denom = y0 - 2 * y1 + y2
        if abs(denom) > 1e-18:
            delta = 0.5 * (y0 - y2) / denom
            if -0.5 <= delta <= 0.5:
                return (peak + delta) * sr / (half_n * 2.0)
    return peak * sr / (half_n * 2.0)


def envelope(ir: np.ndarray) -> np.ndarray:
    n = next_pow2(len(ir))
    spec = np.fft.fft(ir, n)
    spec[1 : n // 2] *= 2.0
    spec[n // 2 + 1 :] = 0.0
    return np.abs(np.fft.ifft(spec))[: len(ir)]


def bandlimit(ir: np.ndarray, sr: int) -> np.ndarray:
    """Deconvolution.deconvolveBandLimited keeps only these bins. Idempotent on version-2 archives."""
    n = next_pow2(len(ir))
    spec = np.fft.rfft(ir, n)
    f = np.fft.rfftfreq(n, 1 / sr)
    spec[(f < ECHO_BAND_LOW_HZ) | (f > ECHO_BAND_HIGH_HZ)] = 0.0
    return np.fft.irfft(spec, n)[: len(ir)]


def direct_echo(ir: np.ndarray, sr: int) -> np.ndarray:
    """Port of ImpulseWindow.aroundDirectPath: 1 ms before to 40 ms after the envelope peak."""
    p = int(np.argmax(envelope(ir)))
    a = max(0, p - int(ECHO_PRE_MS * sr / 1000))
    b = min(len(ir), p + int(ECHO_POST_MS * sr / 1000))
    seg = ir[a:b].copy()
    fade = min(len(seg) // 8, int(2 * sr / 1000))
    if fade > 0:
        seg[-fade:] *= np.hanning(2 * fade)[fade:]
    return seg


def decay_ms(env: np.ndarray, sr: int) -> float:
    if env.size == 0:
        return 0.0
    peak = int(np.argmax(env))
    if env[peak] <= 0:
        return 0.0
    below = np.nonzero(env[peak:] < env[peak] * 0.1)[0]
    t20 = peak + int(below[0]) if below.size else max(len(env) - 1, peak)
    return max(t20 - peak, 1) * 3.0 / sr * 1000.0


def base_features(ir: np.ndarray, sr: int) -> dict[str, float]:
    mag, half_n = spectrum(ir)
    lo, hi = analysis_bins(half_n, sr)
    band = mag[lo : hi + 1]
    freqs = np.arange(lo, hi + 1) * sr / (half_n * 2.0)
    total = band.sum()
    centroid = float((freqs * band).sum() / total) if total > 0 else 0.0
    spread = float(np.sqrt(((freqs - centroid) ** 2 * band).sum() / total)) if total > 0 else 0.0
    pos = band[band > 1e-12]
    flat = float(np.exp(np.log(pos).mean()) / pos.mean()) if pos.size else 0.0
    return {
        "resonanceFreqHz": resonance(mag, half_n, sr),
        "decayTimeMs": decay_ms(envelope(ir), sr),
        "spectralCentroidHz": centroid,
        "spectralSpreadHz": spread,
        "spectralFlatness": flat,
    }


# ---------------------------------------------------------------- candidate features

def candidate_features(ir: np.ndarray, sr: int) -> dict[str, float]:
    mag, half_n = spectrum(ir)
    lo, hi = analysis_bins(half_n, sr)
    hz_per_bin = sr / (half_n * 2.0)
    power = mag ** 2

    peak = lo + int(np.argmax(mag[lo : hi + 1]))
    half_power = mag[peak] / np.sqrt(2.0)
    left = peak
    while left > lo and mag[left] > half_power:
        left -= 1
    right = peak
    while right < hi and mag[right] > half_power:
        right += 1
    bandwidth = max(right - left, 1) * hz_per_bin
    q = peak * hz_per_bin / bandwidth

    split = int(1500.0 / hz_per_bin)
    low = power[lo:split].sum()
    high = power[split : hi + 1].sum()
    low_high = 10 * np.log10((low + 1e-20) / (high + 1e-20))

    band_power = power[lo : hi + 1]
    cumulative = np.cumsum(band_power)
    roll_idx = int(np.searchsorted(cumulative, 0.85 * cumulative[-1])) if cumulative[-1] > 0 else 0
    rolloff = (lo + roll_idx) * hz_per_bin

    env = envelope(ir)
    p = int(np.argmax(env))
    ms = sr / 1000.0
    energy = ir.astype(np.float64) ** 2
    early = energy[p : p + int(2 * ms)].sum()
    late = energy[p + int(2 * ms) : p + int(20 * ms)].sum()
    early_late = 10 * np.log10((early + 1e-20) / (late + 1e-20))

    seg = env[p : p + int(10 * ms)]
    if seg.size >= 4 and seg[0] > 0:
        db = 20 * np.log10(np.maximum(seg / seg[0], 1e-6))
        t = np.arange(seg.size) / ms
        slope = float(np.polyfit(t, db, 1)[0])
    else:
        slope = 0.0

    return {
        "resonanceQ": float(q),
        "lowHighRatioDb": float(low_high),
        "earlyLateRatioDb": float(early_late),
        "rolloff85Hz": float(rolloff),
        "decaySlopeDbPerMs": slope,
    }


# ---------------------------------------------------------------- data loading

def pull_from_phone(dest: Path) -> None:
    adb = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    if not os.path.exists(adb):
        adb = "adb"
    result = subprocess.run(
        [adb, "exec-out", "run-as", "com.aquascope", "tar", "-cf", "-", "-C", "files/aquascope_data", "ir"],
        capture_output=True,
    )
    if result.returncode != 0 or not result.stdout:
        raise SystemExit(f"Pull failed: {result.stderr.decode(errors='replace').strip() or 'no data'}")
    dest.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(result.stdout)) as tar:
        for member in tar.getmembers():
            if member.isfile():
                member.name = os.path.basename(member.name)
                tar.extract(member, dest)
    print(f"Pulled {len(list(dest.glob('*.f32')))} scans into {dest}")


def load_scans(folder: Path, include_implicit: bool) -> list[Scan]:
    accepted = {"dry", "damp"} | ({"damp_implicit"} if include_implicit else set())
    scans: list[Scan] = []
    for meta_path in sorted(folder.glob("*.json")):
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        if meta.get("label") not in accepted:
            continue
        wave_path = meta_path.with_suffix(".f32")
        if not wave_path.exists():
            continue
        ir = np.fromfile(wave_path, dtype="<f4").astype(np.float64)
        sr = int(meta["sampleRate"])
        echo = direct_echo(bandlimit(ir, sr), sr)
        feats = base_features(echo, sr)
        feats.update(candidate_features(echo, sr))
        same_pipeline = meta.get("featureVersion", 0) >= FEATURE_VERSION
        scans.append(
            Scan(
                scan_id=meta["scanId"],
                location_id=meta["locationId"],
                label="damp" if meta["label"].startswith("damp") else "dry",
                features=feats,
                phone_features=(meta.get("features") or {}) if same_pipeline else {},
            )
        )
    return scans


# ---------------------------------------------------------------- evaluation

def classify_loo(rows: np.ndarray, labels: np.ndarray) -> np.ndarray:
    """Leave-one-out diagonal LDA within one location. Returns predicted damp flags."""
    preds = np.zeros(len(rows), dtype=bool)
    for i in range(len(rows)):
        keep = np.arange(len(rows)) != i
        x, y = rows[keep], labels[keep]
        dry, damp = x[~y], x[y]
        if len(dry) < 1 or len(damp) < 1:
            preds[i] = False
            continue
        mu_d, mu_m = dry.mean(axis=0), damp.mean(axis=0)
        var = np.concatenate([dry - mu_d, damp - mu_m]).var(axis=0)
        var = np.maximum(var, (x.std(axis=0) * 0.15) ** 2 + 1e-12)
        w = (mu_m - mu_d) / var
        preds[i] = float(w @ (rows[i] - 0.5 * (mu_d + mu_m))) > 0
    return preds


def evaluate(scans: list[Scan], keys: list[str]) -> tuple[float, dict[str, float]]:
    by_loc: dict[str, list[Scan]] = {}
    for s in scans:
        by_loc.setdefault(s.location_id, []).append(s)
    hits = {"dry": [0, 0], "damp": [0, 0]}
    per_location: dict[str, float] = {}
    for loc, items in by_loc.items():
        labels = np.array([s.label == "damp" for s in items])
        if labels.sum() < 2 or (~labels).sum() < 2:
            continue
        rows = np.array([[s.features[k] for k in keys] for s in items])
        preds = classify_loo(rows, labels)
        loc_hits = {"dry": [0, 0], "damp": [0, 0]}
        for truth, pred in zip(labels, preds):
            cls = "damp" if truth else "dry"
            for bucket in (hits, loc_hits):
                bucket[cls][0] += int(truth == pred)
                bucket[cls][1] += 1
        per_location[loc] = 50.0 * sum(c / max(n, 1) for c, n in loc_hits.values())
    if hits["dry"][1] == 0 or hits["damp"][1] == 0:
        return float("nan"), per_location
    return 50.0 * sum(c / n for c, n in hits.values()), per_location


def parity_report(scans: list[Scan]) -> None:
    worst = {k: 0.0 for k in BASE_KEYS}
    compared = 0
    for s in scans:
        if not all(k in s.phone_features for k in BASE_KEYS):
            continue
        compared += 1
        for k in BASE_KEYS:
            phone = s.phone_features[k]
            rel = abs(s.features[k] - phone) / max(abs(phone), 1e-9)
            worst[k] = max(worst[k], rel)
    if compared == 0:
        print("No scans from the current app pipeline, so the Python port could not be checked against the phone.")
        return
    print(f"Python vs phone feature agreement over {compared} scans (worst relative difference):")
    for k, v in worst.items():
        flag = "" if v < 0.02 else "   <-- check the port before trusting results"
        print(f"  {k:20s} {v * 100:6.2f}%{flag}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", type=Path, nargs="?", default=Path("data/ultrasonic/dataset/ir"))
    ap.add_argument("--pull", action="store_true", help="pull the archive from the connected phone first")
    ap.add_argument("--include-implicit", action="store_true",
                    help="also use scans the app taught as damp because the user skipped labelling")
    args = ap.parse_args()

    if args.pull:
        pull_from_phone(args.folder)
    if not args.folder.exists():
        print(f"No archive at {args.folder}. Scan on the phone, then run with --pull.", file=sys.stderr)
        return 2

    scans = load_scans(args.folder, args.include_implicit)
    locations = {s.location_id for s in scans}
    n_dry = sum(s.label == "dry" for s in scans)
    print(f"Labelled scans: {len(scans)} ({n_dry} dry, {len(scans) - n_dry} damp) across {len(locations)} locations\n")
    if not scans:
        return 3
    parity_report(scans)

    base_acc, base_loc = evaluate(scans, BASE_KEYS)
    print(f"\nLeave-one-out balanced accuracy, shipping 5 features: {base_acc:.1f}%")
    enough = len(scans) >= MIN_LABELLED_SCANS and len(base_loc) >= MIN_LOCATIONS
    if not enough:
        print(f"Not enough data to decide: need {MIN_LABELLED_SCANS}+ labelled scans and "
              f"{MIN_LOCATIONS}+ locations with at least 2 dry and 2 damp each "
              f"(have {len(scans)} scans, {len(base_loc)} usable locations).")

    print(f"\n{'candidate':20s} {'accuracy':>9s} {'gain':>7s} {'locations better/worse':>24s}  verdict")
    keep: list[str] = []
    for name, why in CANDIDATES.items():
        acc, loc = evaluate(scans, BASE_KEYS + [name])
        gain = acc - base_acc
        better = sum(loc[l] > base_loc[l] + 1e-9 for l in loc)
        worse = sum(loc[l] < base_loc[l] - 1e-9 for l in loc)
        ok = enough and gain >= MIN_GAIN_POINTS and better > worse
        if ok:
            keep.append(name)
        verdict = "keep" if ok else ("drop" if enough else "undecided")
        print(f"{name:20s} {acc:8.1f}% {gain:+6.1f} {f'{better}/{worse}':>24s}  {verdict}")
        print(f"{'':20s} {why}")

    if keep:
        acc, _ = evaluate(scans, BASE_KEYS + keep)
        print(f"\nAll kept candidates together: {acc:.1f}% ({acc - base_acc:+.1f} points)")
        print("Port these to FeatureExtractor.kt and retrain with train_ultrasonic_scorer.py:", ", ".join(keep))
    elif enough:
        print("\nNo candidate beat the shipping features by enough. Keep the app as it is.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
