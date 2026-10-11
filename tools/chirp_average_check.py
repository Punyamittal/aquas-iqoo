"""Per-chirp-median features vs features from the three chirps' averaged spectrum.

Uses scans saved with all three chirp recordings (<scanId>.c<i>.f32). Spectral features only,
since averaging spectra discards timing.

Usage: python tools/chirp_average_check.py folder
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

from evaluate_extra_features import analysis_bins, bandlimit, base_features, direct_echo, next_pow2
from full_model_check import per_place_probs

KEYS = ["resonanceFreqHz", "spectralCentroidHz", "spectralSpreadHz", "spectralFlatness"]


def from_spectrum(mag, half_n, sr):
    lo, hi = analysis_bins(half_n, sr)
    band = mag[lo:hi + 1]
    freqs = np.arange(lo, hi + 1) * sr / (half_n * 2.0)
    c = (freqs * band).sum() / band.sum()
    return [freqs[np.argmax(band)], c, np.sqrt(((freqs - c) ** 2 * band).sum() / band.sum()),
            np.exp(np.log(band[band > 1e-12]).mean()) / band.mean()]


folder = Path(sys.argv[1])
by_place = defaultdict(list)
for meta_path in folder.glob("*.json"):
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    waves = sorted(folder.glob(f"{meta['scanId']}.c*.f32"))
    if meta.get("label") not in ("dry", "damp") or len(waves) < 2:
        continue
    sr = int(meta["sampleRate"])
    echoes = [direct_echo(bandlimit(np.fromfile(w, dtype="<f4").astype(np.float64), sr), sr) for w in waves]
    n = next_pow2(max(len(e) for e in echoes))
    mags = [np.abs(np.fft.fft(e, n)) for e in echoes]
    median = np.median([[base_features(e, sr)[k] for k in KEYS] for e in echoes], 0)
    avg = from_spectrum(np.sqrt(np.mean([m ** 2 for m in mags], 0)), n // 2, sr)
    by_place[meta["locationId"]].append((meta["label"] == "damp", median, avg))

for loc, items in by_place.items():
    y = np.array([d for d, _, _ in items])
    if y.sum() < 3 or (~y).sum() < 3:
        continue
    line = f"{loc[:8]} ({(~y).sum()} dry, {y.sum()} damp):"
    for name, idx in (("median of chirps", 1), ("averaged spectrum", 2)):
        x = np.array([it[idx] for it in items])
        pred = per_place_probs(x, y) >= 0.5
        line += f"  {name} {50 * (pred[y].mean() + (~pred[~y]).mean()):.0f}%"
    print(line)
