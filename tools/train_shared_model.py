#!/usr/bin/env python3
"""
Train the shared moisture model the app uses at places that only have dry scans taught.

Input is the impulse-response archive pulled by evaluate_extra_features.py --pull. Each scan's
five shipping features become |scan - median(dry)| / spread(dry) over its place's other dry scans,
so the model learns "how unusual is this scan for this place" rather than what any one surface
sounds like. The direction is dropped on purpose: damp raised the spectral centre at two places
and lowered it at a third. Output is a small CatBoost oblivious-tree model that
SharedMoistureModel.kt evaluates on the phone.

The app does not ship this model yet. With 3 places it scored 74-86% at an unseen place depending
on settings, no better than the app's distance scoring (85%). Re-run as places are added; export
only once it clearly wins.

  python tools/train_shared_model.py                      # leave-one-place-out accuracy
  python tools/train_shared_model.py --out model.json     # also train on everything and export
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import evaluate_extra_features as ev  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
EXCLUDED = ROOT / "data/ultrasonic/dataset/excluded_scans.txt"
PARAMS = dict(iterations=200, depth=3, learning_rate=0.08, random_seed=0, verbose=0,
              train_dir=str(ROOT / "data/ultrasonic/catboost_info"))
MIN_PER_CLASS = 3
SPREAD_FLOOR_FRACTION = 0.25


def load(folder: Path) -> dict[str, list[tuple[np.ndarray, bool]]]:
    excluded = set()
    if EXCLUDED.exists():
        for line in EXCLUDED.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.startswith("#"):
                excluded.add(line.split()[0])
    places: dict[str, list[tuple[np.ndarray, bool]]] = defaultdict(list)
    for meta_path in sorted(folder.glob("*.json")):
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        if meta.get("featureVersion", 0) < ev.FEATURE_VERSION or meta["scanId"] in excluded:
            continue
        if meta.get("label") not in ("dry", "damp", "damp_implicit"):
            continue
        ir = np.fromfile(meta_path.with_suffix(".f32"), dtype="<f4").astype(np.float64)
        sr = int(meta["sampleRate"])
        f = ev.base_features(ev.direct_echo(ev.bandlimit(ir, sr), sr), sr)
        places[meta["locationId"]].append((np.array([f[k] for k in ev.BASE_KEYS]), meta["label"] != "dry"))
    return {
        k: v for k, v in places.items()
        if sum(d for _, d in v) >= MIN_PER_CLASS and sum(not d for _, d in v) >= MIN_PER_CLASS
    }


def spread_floors(places: dict) -> np.ndarray:
    """Lower bound on a place's dry spread, so two near-identical dry scans can't blow up the input."""
    sds = [np.std([s for s, d in v if not d], axis=0, ddof=1) for v in places.values()]
    return SPREAD_FLOOR_FRACTION * np.median(sds, axis=0)


def deviation(x: np.ndarray, dry: list[np.ndarray], floors: np.ndarray) -> np.ndarray:
    """Must match SharedMoistureModel.deviation in the app."""
    dry = np.array(dry)
    return np.abs(x - np.median(dry, axis=0)) / np.maximum(dry.std(axis=0, ddof=1), floors)


def relative_rows(scans: list[tuple[np.ndarray, bool]], floors: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    X, y = [], []
    for i, (x, damp) in enumerate(scans):
        dry = [s for j, (s, d) in enumerate(scans) if not d and j != i]
        if len(dry) < 2:
            continue
        X.append(deviation(x, dry, floors))
        y.append(int(damp))
    return np.array(X), np.array(y)


def fit(places: dict, floors: np.ndarray) -> "CatBoostClassifier":  # noqa: F821
    from catboost import CatBoostClassifier

    parts = [relative_rows(v, floors) for v in places.values()]
    model = CatBoostClassifier(**PARAMS)
    model.fit(np.vstack([p[0] for p in parts]), np.concatenate([p[1] for p in parts]))
    return model


def leave_one_place_out(places: dict) -> dict[str, float]:
    out = {}
    for test in places:
        train = {k: v for k, v in places.items() if k != test}
        floors = spread_floors(train)
        model = fit(train, floors)
        X, y = relative_rows(places[test], floors)
        pred = model.predict_proba(X)[:, 1] >= 0.5
        out[test[:8]] = 50 * (pred[y == 1].mean() + (~pred[y == 0]).mean())
    return out


def export(model, floors: np.ndarray, path: Path, n_scans: int, n_places: int) -> None:
    raw = json.loads(_catboost_json(model))
    trees = []
    for t in raw["oblivious_trees"]:
        trees.append({
            "features": [s["float_feature_index"] for s in t["splits"]],
            "borders": [s["border"] for s in t["splits"]],
            "leaves": t["leaf_values"],
        })
    scale, bias = raw["scale_and_bias"]
    out = {
        "featureKeys": ev.BASE_KEYS,
        "input": "|scan - median(dry)| / max(sd(dry), spreadFloors), needs 2+ dry scans",
        "spreadFloors": floors.tolist(),
        "scale": scale,
        "bias": bias[0] if isinstance(bias, list) else bias,
        "trees": trees,
        "trainedOn": {"scans": n_scans, "places": n_places},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(out, separators=(",", ":")), encoding="utf-8")


def _catboost_json(model) -> str:
    import tempfile

    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / "m.json"
        model.save_model(str(p), format="json")
        return p.read_text(encoding="utf-8")


def evaluate_export(path: Path, X: np.ndarray) -> np.ndarray:
    """Same arithmetic as SharedMoistureModel.kt, to check the export."""
    m = json.loads(path.read_text(encoding="utf-8"))
    raw = np.zeros(len(X))
    for t in m["trees"]:
        idx = np.zeros(len(X), dtype=int)
        for depth, (f, b) in enumerate(zip(t["features"], t["borders"])):
            idx |= (X[:, f] > b).astype(int) << depth
        raw += np.array(t["leaves"])[idx]
    return 1 / (1 + np.exp(-(m["scale"] * raw + m["bias"])))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", type=Path, nargs="?", default=ROOT / "data/ultrasonic/dataset/ir")
    ap.add_argument("--out", type=Path, help="export the model trained on all places to this file")
    args = ap.parse_args()

    places = load(args.folder)
    n = sum(len(v) for v in places.values())
    print(f"{n} labelled scans from {len(places)} places with {MIN_PER_CLASS}+ dry and damp each")
    if len(places) < 2:
        print("Need at least 2 places to train and check a shared model.", file=sys.stderr)
        return 2
    loo = leave_one_place_out(places)
    print("Accuracy at a place the model never saw (dry baseline only):")
    for k, v in loo.items():
        print(f"  {k}  {v:5.1f}%")
    print(f"  mean      {np.mean(list(loo.values())):5.1f}%")
    if args.out is None:
        return 0

    floors = spread_floors(places)
    model = fit(places, floors)
    export(model, floors, args.out, n, len(places))
    X = np.vstack([relative_rows(v, floors)[0] for v in places.values()])
    diff = np.abs(evaluate_export(args.out, X) - model.predict_proba(X)[:, 1]).max()
    print(f"Wrote {args.out} ({args.out.stat().st_size // 1024} KB); "
          f"export matches CatBoost to {diff:.1e}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
