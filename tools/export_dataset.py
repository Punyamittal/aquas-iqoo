"""Export every AquaScope moisture sample we have into one CSV.

Sources:
  recording  raw recordings in data/ultrasonic/ir (Oct 2026 onward). Features are
             recomputed with the current pipeline, so all of these are comparable.
  sept       data/ultrasonic/locations.json (12-13 Sep 2026 sessions). Only the
             features the app stored survive; they came from the old ultrasonic
             pipeline and are NOT comparable with the recordings.
  oct_v0     data/ultrasonic/locations_new.json (9 Oct, before the band-limited
             pipeline). Stored features only; also not comparable.

For sept/oct_v0, taught rows carry a dry/damp label; scored rows are unlabelled
scans with the score the app showed.

Usage:
  python tools/export_dataset.py [--pull] [--out data/ultrasonic/dataset/all_samples.csv]
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import evaluate_extra_features as ev  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data" / "ultrasonic"
IR_DIR = DATA / "ir"
FEATURES = ["resonanceFreqHz", "decayTimeMs", "spectralCentroidHz", "spectralSpreadHz", "spectralFlatness"]
COLUMNS = ["source", "pipeline", "date", "time", "place", "place_id", "label", "kind",
           "shown_score_pct", "peak_to_noise_db", *FEATURES, "scan_id"]


def adb() -> str:
    return str(Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe")


def phone_locations() -> list[dict]:
    """Current place list from the phone, cached so exports still work when it is unplugged."""
    cache = DATA / "dataset" / "phone_locations.json"
    try:
        raw = subprocess.run([adb(), "exec-out", "run-as", "com.aquascope", "cat",
                              "files/aquascope_data/locations.json"], capture_output=True, timeout=30).stdout
        locations = json.loads(raw.decode("utf-8"))
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps(locations), encoding="utf-8")
        return locations
    except (OSError, subprocess.SubprocessError, ValueError):
        print("phone not reachable; using cached place list" if cache.exists() else
              "phone not reachable and no cache; place names and shown scores may be missing")
        return load_json(cache)


def load_json(path: Path) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else []


def stamp(ms: int) -> tuple[str, str]:
    t = datetime.fromtimestamp(ms / 1000)
    return t.strftime("%Y-%m-%d"), t.strftime("%H:%M:%S")


def simple_label(label: str) -> str:
    return {"damp_implicit": "damp_assumed"}.get(label, label)


def recording_rows(names: dict[str, str], scores: dict[str, list[tuple[int, float]]]) -> list[dict]:
    rows = []
    for meta_path in sorted(IR_DIR.glob("*.json")):
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        ir_path = meta_path.with_suffix(".f32")
        if not ir_path.exists():
            continue
        sr = meta["sampleRate"]
        ir = np.fromfile(ir_path, "<f4").astype(float)
        feats = ev.base_features(ev.direct_echo(ev.bandlimit(ir, sr), sr), sr)
        lid, ts = meta["locationId"], meta["timestamp"]
        shown = next((s for t, s in scores.get(lid, []) if abs(t - ts) <= 2000), None)
        date, time = stamp(ts)
        rows.append({
            "source": "recording",
            "pipeline": f"current (recorded v{meta.get('featureVersion', 0)})",
            "date": date, "time": time,
            "place": names.get(lid, lid[:8]), "place_id": lid,
            "label": simple_label(meta["label"]), "kind": "scan",
            "shown_score_pct": "" if shown is None else round(shown, 1),
            "peak_to_noise_db": round(meta["peakToNoiseDb"], 1) if "peakToNoiseDb" in meta else "",
            **{k: round(feats[k], 4) for k in FEATURES},
            "scan_id": meta.get("scanId", meta_path.stem),
        })
    return rows


def snapshot_rows(locations: list[dict], source: str, pipeline: str) -> list[dict]:
    rows = []
    for loc in locations:
        history = loc.get("scanHistory", [])
        first = min((h["timestamp"] for h in history), default=None)
        date = stamp(first)[0] if first else ""
        base = {"source": source, "pipeline": pipeline, "place": loc["label"], "place_id": loc["id"],
                "peak_to_noise_db": "", "scan_id": ""}
        for key, label in (("baselineFeatures", "dry"), ("moistFeatures", "damp")):
            for f in loc.get(key, []):
                rows.append({**base, "date": date, "time": "", "label": label, "kind": "taught",
                             "shown_score_pct": "", **{k: round(f[k], 4) for k in FEATURES}})
        for h in history:
            d, t = stamp(h["timestamp"])
            rows.append({**base, "date": d, "time": t, "label": "unlabelled", "kind": "scored",
                         "shown_score_pct": round(h["anomalyScore"], 1),
                         **{k: round(h["features"][k], 4) for k in FEATURES}})
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pull", action="store_true", help="pull new recordings from the phone first")
    parser.add_argument("--out", type=Path, default=DATA / "dataset" / "all_samples.csv")
    args = parser.parse_args()
    if args.pull:
        try:
            ev.pull_from_phone(IR_DIR)
        except SystemExit:
            print("pull failed; exporting the recordings already on disk")

    phone = phone_locations()
    oct_v0 = load_json(DATA / "locations_new.json")
    names = {l["id"]: l["label"] for l in oct_v0}
    names.update({l["id"]: l["label"] for l in phone})
    scores = {l["id"]: [(h["timestamp"], h["anomalyScore"]) for h in l.get("scanHistory", [])] for l in phone}

    rows = (recording_rows(names, scores)
            + snapshot_rows(oct_v0, "oct_v0", "pre band-limit (9 Oct)")
            + snapshot_rows(load_json(DATA / "locations.json"), "sept", "old ultrasonic (Sep)"))
    rows.sort(key=lambda r: (r["date"], r["time"]))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=COLUMNS)
        writer.writeheader()
        writer.writerows(rows)

    print(f"wrote {len(rows)} rows to {args.out}")
    for (source, kind, label), n in sorted(Counter((r["source"], r["kind"], r["label"]) for r in rows).items()):
        print(f"  {source:10s} {kind:7s} {label:13s} {n:5d}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
