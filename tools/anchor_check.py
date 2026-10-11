"""Does a "first 3 scans" anchor help? Same fair protocol as tools/combine_check.py.

Anchor: the median of the first 3 scans the user taught as dry (by time) is the dry anchor, and
the median of the first 3 taught as damp is the damp anchor. A scan's anchor score is its relative
distance to the two anchors (0 at the dry anchor, 1 at the damp anchor), on standardised features.

Variants
  hybrid              the app today: 0.5 x LDA + 0.5 x distance to the centres of ALL taught scans
  anchor only         distance to the two anchors
  centres+anchor      0.5 x centres + 0.5 x anchor (anchor replaces LDA)
  lda+anchor          0.5 x LDA + 0.5 x anchor (anchor replaces the centres)
  hybrid+anchor       mean of LDA, centres and anchor

Usage: python tools/anchor_check.py
"""
import csv
from collections import defaultdict
from multiprocessing import Pool

import numpy as np

from combine_check import lda, self_check
from scatter_compare import FEATURES, MIN_PER_CLASS, ROOT, _standardise, centroid_ratio

ANCHOR_SIZE = 3


def anchor_ratio(x, d, m):
    """d and m arrive in teaching order, so their first rows are the earliest taught scans."""
    mu, s = _standardise(d, m)
    ad = (np.median(d[:ANCHOR_SIZE], axis=0) - mu) / s
    am = (np.median(m[:ANCHOR_SIZE], axis=0) - mu) / s
    z = (np.atleast_2d(x) - mu) / s
    dd = np.linalg.norm(z - ad, axis=1)
    dm = np.linalg.norm(z - am, axis=1)
    return dd / (dd + dm + 1e-12)


SCORERS = {
    "hybrid": lambda x, d, m, i: 0.5 * lda(x, d, m, i) + 0.5 * centroid_ratio(x, d, m),
    "anchor only": lambda x, d, m, i: anchor_ratio(x, d, m),
    "centres+anchor": lambda x, d, m, i: 0.5 * centroid_ratio(x, d, m) + 0.5 * anchor_ratio(x, d, m),
    "lda+anchor": lambda x, d, m, i: 0.5 * lda(x, d, m, i) + 0.5 * anchor_ratio(x, d, m),
    "hybrid+anchor": lambda x, d, m, i: (lda(x, d, m, i) + centroid_ratio(x, d, m) + anchor_ratio(x, d, m)) / 3,
}


def load_places_in_time_order():
    by_place = defaultdict(list)
    with open(ROOT / "data/ultrasonic/dataset/all_samples.csv", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["pipeline"] == "current (recorded v2)" and row["label"] in ("dry", "damp"):
                by_place[row["place"]].append(row)
    places = {}
    for place, rows in sorted(by_place.items()):
        rows.sort(key=lambda r: (r["date"], r["time"], r["scan_id"]))
        y = np.array([1 if r["label"] == "damp" else 0 for r in rows])
        if min((y == 0).sum(), (y == 1).sum()) >= MIN_PER_CLASS:
            places[place] = (np.array([[float(r[k]) for k in FEATURES] for r in rows]), y)
    return places


def job(args):
    variant, place, x, y = args
    return variant, place, self_check([SCORERS[variant]], x[y == 0], x[y == 1])


def main():
    places = load_places_in_time_order()
    jobs = [(v, p, x, y) for v in SCORERS for p, (x, y) in places.items()]
    with Pool() as pool:
        table = {(v, p): a for v, p, a in pool.map(job, jobs)}
    names = list(places)
    print(f"{'variant':<16}" + "".join(f"{p:>10}" for p in names) + f"{'mean':>8}")
    for v in SCORERS:
        accs = [table[(v, p)] for p in names]
        print(f"{v:<16}" + "".join(f"{a:>9.0f}%" for a in accs) + f"{np.mean(accs):>7.0f}%")


if __name__ == "__main__":
    main()
