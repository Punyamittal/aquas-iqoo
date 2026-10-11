"""Second round of candidate features, recomputed from archived impulse responses.

Groups (each tested as an addition to the five shipping features):
  arrival  : sub-sample time of the direct-echo peak in the full impulse response
  banddecay: Schroeder early decay time in 4 frequency bands of the echo
  cepstrum : 6 mel-cepstral coefficients of the echo
  tail     : energy 40-200 ms after the peak vs the first 40 ms (dB)
  peaks    : mean and spread of the 3 strongest resonance peaks

Score: leave-one-out diagonal LDA per place (places with 3+ dry and 3+ damp), balanced accuracy
averaged over places; plus how many places got better / worse.

Usage: python tools/features_round2.py folder [folder ...]   (oldest copy first; later labels win)
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

from evaluate_extra_features import (BASE_KEYS, bandlimit, base_features, classify_loo, direct_echo,
                                     envelope, next_pow2)

DECAY_BANDS = [(200, 1000), (1000, 2500), (2500, 5000), (5000, 8000)]


def edt_ms(x, sr):
    e = x ** 2
    sch = np.cumsum(e[::-1])[::-1]
    if sch[0] <= 0:
        return 0.0
    db = 10 * np.log10(np.maximum(sch / sch[0], 1e-12))
    below = np.nonzero(db <= -10)[0]
    return float((below[0] if below.size else len(db)) * 6.0 / sr * 1000)


def band_signal(x, sr, lo, hi):
    n = next_pow2(len(x))
    spec = np.fft.rfft(x, n)
    f = np.fft.rfftfreq(n, 1 / sr)
    spec[(f < lo) | (f >= hi)] = 0
    return np.fft.irfft(spec, n)[: len(x)]


def mel_cepstrum(x, sr, n_mels=16, n_coef=6):
    n = next_pow2(len(x))
    power = np.abs(np.fft.rfft(x, n)) ** 2
    f = np.fft.rfftfreq(n, 1 / sr)
    mel = lambda hz: 2595 * np.log10(1 + hz / 700)
    edges = 700 * (10 ** (np.linspace(mel(200), mel(8000), n_mels + 2) / 2595) - 1)
    energies = []
    for a, c, b in zip(edges[:-2], edges[1:-1], edges[2:]):
        w = np.clip(np.minimum((f - a) / (c - a), (b - f) / (b - c)), 0, None)
        energies.append(np.log((w * power).sum() + 1e-20))
    e = np.array(energies)
    k = np.arange(n_mels)
    return [float(np.sum(e * np.cos(np.pi * i * (k + 0.5) / n_mels))) for i in range(1, n_coef + 1)]


def peak_features(x, sr):
    n = next_pow2(len(x))
    mag = np.abs(np.fft.rfft(x, n))
    f = np.fft.rfftfreq(n, 1 / sr)
    band = (f >= 200) & (f <= 8000)
    idx = np.nonzero(band)[0]
    m = mag[idx]
    local = [i for i in range(1, len(m) - 1) if m[i] >= m[i - 1] and m[i] >= m[i + 1]]
    min_sep = int(150 / (f[1] - f[0]))
    picked = []
    for i in sorted(local, key=lambda i: -m[i]):
        if all(abs(i - j) > min_sep for j in picked):
            picked.append(i)
        if len(picked) == 3:
            break
    hz = f[idx[picked]] if picked else np.array([0.0])
    return float(hz.mean()), float(hz.std())


def features(ir, sr):
    ir = bandlimit(ir, sr)
    echo = direct_echo(ir, sr)
    out = base_features(echo, sr)
    env = envelope(ir)
    p = int(np.argmax(env))
    if 0 < p < len(env) - 1:
        y0, y1, y2 = env[p - 1], env[p], env[p + 1]
        den = y0 - 2 * y1 + y2
        frac = 0.5 * (y0 - y2) / den if abs(den) > 1e-18 else 0.0
    else:
        frac = 0.0
    out["arrivalMs"] = float((p + np.clip(frac, -0.5, 0.5)) / sr * 1000)
    seg = echo[int(np.argmax(envelope(echo))):]
    for i, (lo, hi) in enumerate(DECAY_BANDS):
        out[f"edtBand{i}"] = edt_ms(band_signal(seg, sr, lo, hi), sr)
    for i, c in enumerate(mel_cepstrum(echo, sr)):
        out[f"cep{i}"] = c
    ms = sr / 1000
    head = (ir[p: p + int(40 * ms)] ** 2).sum()
    tail = (ir[p + int(40 * ms): p + int(200 * ms)] ** 2).sum()
    out["tailDb"] = float(10 * np.log10((tail + 1e-30) / (head + 1e-30)))
    out["peakMeanHz"], out["peakSpreadHz"] = peak_features(echo, sr)
    return out


GROUPS = {
    "arrival": ["arrivalMs"],
    "banddecay": [f"edtBand{i}" for i in range(4)],
    "cepstrum": [f"cep{i}" for i in range(6)],
    "tail": ["tailDb"],
    "peaks": ["peakMeanHz", "peakSpreadHz"],
}


def loo_place(rows, y):
    pred = classify_loo(rows, y)
    return 50 * ((pred[y]).mean() + (~pred[~y]).mean())


def main():
    latest = {}
    for folder in map(Path, sys.argv[1:]):
        for p in folder.glob("*.json"):
            latest[p.stem] = p
    by_place = defaultdict(list)
    for p in latest.values():
        m = json.loads(p.read_text(encoding="utf-8"))
        wave = p.with_suffix(".f32")
        if m.get("label") not in ("dry", "damp") or not wave.exists():
            continue
        f = features(np.fromfile(wave, dtype="<f4").astype(np.float64), int(m["sampleRate"]))
        by_place[m["locationId"]].append((m["label"] == "damp", f))
    places = {k: v for k, v in by_place.items()
              if sum(d for d, _ in v) >= 3 and sum(not d for d, _ in v) >= 3}
    n = sum(len(v) for v in places.values())
    print(f"{len(places)} places with 3+ dry and 3+ damp, {n} labelled scans\n")

    def score(keys):
        per = {}
        for loc, items in places.items():
            y = np.array([d for d, _ in items])
            rows = np.array([[f[k] for k in keys] for _, f in items])
            per[loc] = loo_place(rows, y)
        return np.mean(list(per.values())), per

    base, base_per = score(BASE_KEYS)
    print(f"{'features':<28}{'accuracy':>9}{'gain':>8}{'places better/worse':>22}")
    print(f"{'current 5':<28}{base:>8.1f}%")
    all_new = []
    for name, keys in GROUPS.items():
        all_new += keys
        for label, ks in ((f"+ {name}", BASE_KEYS + keys), (f"{name} alone", keys)):
            acc, per = score(ks)
            better = sum(per[l] > base_per[l] + 1e-9 for l in per)
            worse = sum(per[l] < base_per[l] - 1e-9 for l in per)
            print(f"{label:<28}{acc:>8.1f}%{acc - base:>+7.1f}{f'{better}/{worse}':>22}")
    acc, per = score(BASE_KEYS + all_new)
    better = sum(per[l] > base_per[l] + 1e-9 for l in per)
    worse = sum(per[l] < base_per[l] - 1e-9 for l in per)
    print(f"{'+ all groups':<28}{acc:>8.1f}%{acc - base:>+7.1f}{f'{better}/{worse}':>22}")


if __name__ == "__main__":
    main()
