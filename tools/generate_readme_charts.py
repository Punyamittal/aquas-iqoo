#!/usr/bin/env python3
"""
Generate hackathon README charts from on-device AquaScope data.

Inputs:
  data/ultrasonic/dataset/locations.json
  data/ultrasonic/trained_weights.json

Outputs:
  docs/charts/*.png
  docs/charts/metrics_summary.json
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "ultrasonic"
OUT = ROOT / "docs" / "charts"

FEATURE_KEYS = [
    "resonanceFreqHz",
    "decayTimeMs",
    "spectralCentroidHz",
    "spectralSpreadHz",
    "spectralFlatness",
]
FEATURE_LABELS = ["Resonance (Hz)", "Decay (ms)", "Centroid (Hz)", "Spread (Hz)", "Flatness"]

# AquaScope brand palette
NAVY = "#071A2B"
CYAN = "#4AAFC2"
AMBER = "#FFB800"
GREEN = "#3DDC97"
RED = "#FF6B6B"
MUTE = "#B7C9C8"
BG = "#0A1628"


def load_json(path: Path) -> dict | list:
    return json.loads(path.read_text(encoding="utf-8"))


def flatten_locations(locations: list) -> dict:
    dry, moist, history = [], [], []
    by_label: dict[str, int] = {}
    for loc in locations:
        label = (loc.get("label") or "?").strip() or "?"
        by_label[label] = by_label.get(label, 0) + len(loc.get("scanHistory") or [])
        for f in loc.get("baselineFeatures") or []:
            dry.append({**f, "_label": label})
        for f in loc.get("moistFeatures") or []:
            moist.append({**f, "_label": label})
        for rec in loc.get("scanHistory") or []:
            history.append(
                {
                    "score": float(rec.get("anomalyScore") or 0),
                    "ts": int(rec.get("timestamp") or 0),
                    "label": label,
                    "features": rec.get("features") or {},
                }
            )
    return {"dry": dry, "moist": moist, "history": history, "by_label": by_label}


def style_ax(ax, title: str, xlabel: str = "", ylabel: str = ""):
    ax.set_facecolor(BG)
    ax.set_title(title, color="white", fontsize=12, fontweight="bold", pad=10)
    ax.set_xlabel(xlabel, color=MUTE)
    ax.set_ylabel(ylabel, color=MUTE)
    ax.tick_params(colors=MUTE)
    for spine in ax.spines.values():
        spine.set_color("#1E3F55")


def chart_anomaly_distribution(history: list):
    scores = [h["score"] for h in history if h["score"] >= 0]
    if not scores:
        return
    fig, ax = plt.subplots(figsize=(8, 4.5), facecolor=BG)
    bins = np.linspace(0, 100, 21)
    ax.hist(scores, bins=bins, color=CYAN, edgecolor=NAVY, alpha=0.85)
    ax.axvline(35, color=GREEN, linestyle="--", linewidth=1.2, label="Green max (~35)")
    ax.axvline(70, color=AMBER, linestyle="--", linewidth=1.2, label="Yellow max (~70)")
    style_ax(ax, "Anomaly score distribution (n={})".format(len(scores)), "Anomaly %", "Scans")
    ax.legend(facecolor=NAVY, edgecolor=CYAN, labelcolor="white", fontsize=8)
    fig.tight_layout()
    fig.savefig(OUT / "anomaly_score_distribution.png", dpi=160, facecolor=BG)
    plt.close(fig)


def chart_dry_vs_moist_features(dry: list, moist: list):
    if not dry or not moist:
        return
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.5), facecolor=BG)
    for ax, key, label in zip(
        axes,
        ["resonanceFreqHz", "decayTimeMs"],
        ["Resonance frequency (Hz)", "Decay time (ms)"],
    ):
        d_vals = [float(x.get(key, 0)) for x in dry]
        m_vals = [float(x.get(key, 0)) for x in moist]
        bp = ax.boxplot(
            [d_vals, m_vals],
            labels=["Dry baseline", "Moist teaching"],
            patch_artist=True,
            medianprops=dict(color="white", linewidth=2),
        )
        bp["boxes"][0].set_facecolor(GREEN)
        bp["boxes"][0].set_alpha(0.7)
        bp["boxes"][1].set_facecolor(RED)
        bp["boxes"][1].set_alpha(0.7)
        style_ax(ax, label, "", label)
    fig.suptitle("Physical feature shift: dry vs moist labels", color="white", fontsize=13, y=1.02)
    fig.tight_layout()
    fig.savefig(OUT / "dry_vs_moist_features.png", dpi=160, facecolor=BG)
    plt.close(fig)


def chart_lda_improvement(weights: dict):
    m = weights.get("metrics") or {}
    before = m.get("before_loo") or {}
    after = m.get("after") or {}
    if not before or not after:
        return
    labels = ["Balanced acc", "Dry class %", "Moist class %"]
    b_vals = [
        before.get("balanced_acc", 0) * 100,
        before.get("dry_class_rate", 0) * 100,
        before.get("moist_class_rate", 0) * 100,
    ]
    a_vals = [
        after.get("balanced_acc", 0) * 100,
        after.get("dry_class_rate", 0) * 100,
        after.get("moist_class_rate", 0) * 100,
    ]
    x = np.arange(len(labels))
    w = 0.35
    fig, ax = plt.subplots(figsize=(8, 4.5), facecolor=BG)
    ax.bar(x - w / 2, b_vals, w, label="Before LDA cleanup", color=MUTE, alpha=0.8)
    ax.bar(x + w / 2, a_vals, w, label="After trained weights", color=CYAN, alpha=0.9)
    ax.set_xticks(x)
    ax.set_xticklabels(labels, color=MUTE)
    ax.set_ylim(0, 100)
    style_ax(ax, "Classifier improvement on device dataset", "", "Rate (%)")
    ax.legend(facecolor=NAVY, edgecolor=CYAN, labelcolor="white")
    fig.tight_layout()
    fig.savefig(OUT / "lda_training_improvement.png", dpi=160, facecolor=BG)
    plt.close(fig)


def chart_mean_scores(weights: dict):
    m = weights.get("metrics") or {}
    after = m.get("after") or {}
    if not after:
        return
    fig, ax = plt.subplots(figsize=(6, 4), facecolor=BG)
    labels = ["Dry mean score", "Moist mean score"]
    vals = [after.get("dry_mean_score", 0), after.get("moist_mean_score", 0)]
    colors = [GREEN, RED]
    bars = ax.bar(labels, vals, color=colors, alpha=0.85, edgecolor=NAVY)
    for bar, v in zip(bars, vals):
        ax.text(bar.get_x() + bar.get_width() / 2, v + 1.5, f"{v:.1f}%", ha="center", color="white", fontsize=10)
    style_ax(ax, "Mean anomaly score by class (trained model)", "", "Anomaly %")
    fig.tight_layout()
    fig.savefig(OUT / "mean_scores_by_class.png", dpi=160, facecolor=BG)
    plt.close(fig)


def chart_dataset_composition(weights: dict, flat: dict):
    ds = weights.get("dataset") or {}
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.2), facecolor=BG)
    # Pie: sample types
    sizes = [ds.get("dry", 0), ds.get("moist", 0), max(0, ds.get("history", 0) - ds.get("dry", 0) - ds.get("moist", 0))]
    labels_p = ["Dry baselines", "Moist labels", "Compare scans"]
    colors_p = [GREEN, RED, CYAN]
    axes[0].pie(
        [max(1, s) for s in sizes[:3]],
        labels=labels_p,
        colors=colors_p,
        autopct="%1.0f%%",
        textprops={"color": "white", "fontsize": 9},
        wedgeprops={"edgecolor": NAVY, "linewidth": 1},
    )
    axes[0].set_title("Dataset composition", color="white", fontsize=11)
    # Bar: top locations
    by_label = flat.get("by_label") or {}
    top = sorted(by_label.items(), key=lambda x: -x[1])[:10]
    if top:
        labels_b = [t[0][:12] for t in top]
        counts = [t[1] for t in top]
        axes[1].barh(labels_b[::-1], counts[::-1], color=CYAN, alpha=0.85)
        style_ax(axes[1], "Top scan locations", "Scan count", "")
    for ax in axes:
        ax.set_facecolor(BG)
    fig.suptitle(
        f"iQOO field dataset — {ds.get('locations', 0)} locations · {ds.get('history', 0)} scans",
        color="white",
        fontsize=12,
        y=1.02,
    )
    fig.tight_layout()
    fig.savefig(OUT / "dataset_composition.png", dpi=160, facecolor=BG)
    plt.close(fig)


def chart_score_timeline(history: list):
    if len(history) < 5:
        return
    hist = sorted(history, key=lambda h: h["ts"])
    scores = [h["score"] for h in hist]
    fig, ax = plt.subplots(figsize=(10, 4), facecolor=BG)
    ax.plot(range(len(scores)), scores, color=CYAN, linewidth=1.2, alpha=0.9)
    ax.fill_between(range(len(scores)), scores, alpha=0.15, color=CYAN)
    ax.axhline(35, color=GREEN, linestyle=":", alpha=0.7)
    ax.axhline(70, color=AMBER, linestyle=":", alpha=0.7)
    style_ax(ax, "Scan anomaly scores over session history", "Scan index (chronological)", "Anomaly %")
    fig.tight_layout()
    fig.savefig(OUT / "score_timeline.png", dpi=160, facecolor=BG)
    plt.close(fig)


def chart_feature_radar(dry: list, moist: list):
    if len(dry) < 2 or len(moist) < 2:
        return

    def mean_feat(rows, key):
        vals = [float(r.get(key, 0)) for r in rows]
        return sum(vals) / max(1, len(vals))

    dry_m = [mean_feat(dry, k) for k in FEATURE_KEYS]
    moist_m = [mean_feat(moist, k) for k in FEATURE_KEYS]
    # Normalize each dimension 0-1 for radar
    combined = list(zip(dry_m, moist_m))
    norms_d, norms_m = [], []
    for d, m in combined:
        lo, hi = min(d, m), max(d, m)
        span = hi - lo if hi > lo else 1.0
        norms_d.append((d - lo) / span)
        norms_m.append((m - lo) / span)

    angles = np.linspace(0, 2 * math.pi, len(FEATURE_KEYS), endpoint=False).tolist()
    angles += angles[:1]
    norms_d += norms_d[:1]
    norms_m += norms_m[:1]

    fig, ax = plt.subplots(figsize=(6, 6), subplot_kw=dict(polar=True), facecolor=BG)
    ax.set_facecolor(BG)
    ax.plot(angles, norms_d, color=GREEN, linewidth=2, label="Dry baseline (mean)")
    ax.fill(angles, norms_d, color=GREEN, alpha=0.2)
    ax.plot(angles, norms_m, color=RED, linewidth=2, label="Moist teaching (mean)")
    ax.fill(angles, norms_m, color=RED, alpha=0.2)
    ax.set_xticks(angles[:-1])
    ax.set_xticklabels([f.split()[0] for f in FEATURE_LABELS], color=MUTE, fontsize=8)
    ax.tick_params(colors=MUTE)
    ax.set_title("Acoustic fingerprint: dry vs moist (normalized)", color="white", pad=20)
    ax.legend(loc="upper right", bbox_to_anchor=(1.3, 1.1), facecolor=NAVY, labelcolor="white")
    fig.tight_layout()
    fig.savefig(OUT / "feature_radar_dry_moist.png", dpi=160, facecolor=BG)
    plt.close(fig)


def build_metrics_summary(weights: dict, flat: dict) -> dict:
    history = flat["history"]
    scores = [h["score"] for h in history]
    ds = weights.get("dataset") or {}
    after = (weights.get("metrics") or {}).get("after") or {}
    dry_r = [float(x.get("resonanceFreqHz", 0)) for x in flat["dry"]]
    moist_r = [float(x.get("resonanceFreqHz", 0)) for x in flat["moist"]]
    dry_d = [float(x.get("decayTimeMs", 0)) for x in flat["dry"]]
    moist_d = [float(x.get("decayTimeMs", 0)) for x in flat["moist"]]

    def pct_shift(a, b):
        if not a or not b:
            return None
        ma, mb = sum(a) / len(a), sum(b) / len(b)
        if ma == 0:
            return None
        return round((mb - ma) / ma * 100, 1)

    return {
        "locations": ds.get("locations", 0),
        "total_scans": len(history),
        "dry_samples": len(flat["dry"]),
        "moist_samples": len(flat["moist"]),
        "paired_locations": ds.get("paired_locations", 0),
        "score_mean": round(sum(scores) / max(1, len(scores)), 1),
        "score_median": round(float(np.median(scores)), 1) if scores else 0,
        "score_p90": round(float(np.percentile(scores, 90)), 1) if scores else 0,
        "balanced_accuracy_pct": round(after.get("balanced_acc", 0) * 100, 1),
        "dry_mean_score": round(after.get("dry_mean_score", 0), 1),
        "moist_mean_score": round(after.get("moist_mean_score", 0), 1),
        "resonance_shift_pct_moist_vs_dry": pct_shift(dry_r, moist_r),
        "decay_shift_pct_moist_vs_dry": pct_shift(dry_d, moist_d),
        "score_lift_moist_minus_dry": round(
            after.get("moist_mean_score", 0) - after.get("dry_mean_score", 0), 1
        ),
    }


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    loc_path = DATA / "dataset" / "locations.json"
    w_path = DATA / "trained_weights.json"
    if not loc_path.is_file():
        print("Missing", loc_path)
        return 1
    locations = load_json(loc_path)
    weights = load_json(w_path) if w_path.is_file() else {}
    flat = flatten_locations(locations)

    chart_anomaly_distribution(flat["history"])
    chart_dry_vs_moist_features(flat["dry"], flat["moist"])
    chart_lda_improvement(weights)
    chart_mean_scores(weights)
    chart_dataset_composition(weights, flat)
    chart_score_timeline(flat["history"])
    chart_feature_radar(flat["dry"], flat["moist"])

    summary = build_metrics_summary(weights, flat)
    (OUT / "metrics_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print("Wrote charts to", OUT)
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
