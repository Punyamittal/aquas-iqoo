"""Can combining scorers beat the app's hybrid? Same protocol as tools/hybrid_check.py.

Every variant turns a scan into a damp-ness between 0 and 1 (damp at 0.5 or more), picks its
feature combination per place by 10-fold cross-validation, and is reported as leave-one-out
balanced accuracy with the feature choice redone in every fold (so the numbers are fair).

Variants
  hybrid        the app today: 0.5 x LDA score + 0.5 x distance-to-centres ratio
  hybrid 30/70  0.3 x LDA + 0.7 x centres          hybrid 70/30  0.7 x LDA + 0.3 x centres
  hybrid median same as hybrid with median centres
  lda+cen+maha  mean of LDA, centres ratio and Mahalanobis ratio (accounts for correlated features)
  +knn5         the three above plus the share of damp scans among the 5 nearest
  vote3         majority vote of LDA, centres and Mahalanobis
  pick method   per place, the method AND features chosen together by cross-validation

Usage: python tools/combine_check.py
"""
from multiprocessing import Pool

import numpy as np

from scatter_compare import COMBOS, DEDUPE, _dedupe, _standardise, centroid_ratio, current_score_percent, load_places

FOLDS = 10
MIN_PER_CLASS = 3
ALL = COMBOS[-1]


def lda(x, d, m, idx):
    return current_score_percent(x, d, m, idx) / 100.0


def maha_ratio(x, d, m):
    mu, s = _standardise(d, m)
    zd, zm, z = (d - mu) / s, (m - mu) / s, (np.atleast_2d(x) - mu) / s
    k = zd.shape[1]
    cd = np.atleast_2d(np.cov(zd.T)) if len(zd) > 1 else np.zeros((k, k))
    cm = np.atleast_2d(np.cov(zm.T)) if len(zm) > 1 else np.zeros((k, k))
    cov = ((len(zd) - 1) * cd + (len(zm) - 1) * cm) / max(len(zd) + len(zm) - 2, 1)
    inv = np.linalg.inv(0.7 * cov + 0.3 * np.eye(k))
    a, b = z - zd.mean(axis=0), z - zm.mean(axis=0)
    dd = np.sqrt(np.einsum("ij,jk,ik->i", a, inv, a))
    dm = np.sqrt(np.einsum("ij,jk,ik->i", b, inv, b))
    return dd / (dd + dm + 1e-12)


def knn_share(x, d, m, k=5):
    mu, s = _standardise(d, m)
    train = (np.vstack([d, m]) - mu) / s
    lab = np.r_[np.zeros(len(d)), np.ones(len(m))]
    z = (np.atleast_2d(x) - mu) / s
    dist = np.linalg.norm(z[:, None, :] - train[None, :, :], axis=2)
    return lab[np.argsort(dist, axis=1)[:, :min(k, len(train))]].mean(axis=1)


SCORERS = {
    "hybrid": lambda x, d, m, i: 0.5 * lda(x, d, m, i) + 0.5 * centroid_ratio(x, d, m),
    "hybrid 30/70": lambda x, d, m, i: 0.3 * lda(x, d, m, i) + 0.7 * centroid_ratio(x, d, m),
    "hybrid 70/30": lambda x, d, m, i: 0.7 * lda(x, d, m, i) + 0.3 * centroid_ratio(x, d, m),
    "hybrid median": lambda x, d, m, i: 0.5 * lda(x, d, m, i) + 0.5 * centroid_ratio(x, d, m, use_median=True),
    "lda+cen+maha": lambda x, d, m, i: (lda(x, d, m, i) + centroid_ratio(x, d, m) + maha_ratio(x, d, m)) / 3,
    "+knn5": lambda x, d, m, i: (lda(x, d, m, i) + centroid_ratio(x, d, m) + maha_ratio(x, d, m)
                                 + knn_share(x, d, m)) / 4,
    "vote3": lambda x, d, m, i: ((lda(x, d, m, i) >= 0.5).astype(float) + (centroid_ratio(x, d, m) >= 0.5)
                                 + (maha_ratio(x, d, m) >= 0.5)) / 3,
}
PICKABLE = ["lda", "centres", "maha", "hybrid"]
BASIC = {
    "lda": lda,
    "centres": lambda x, d, m, i: centroid_ratio(x, d, m),
    "maha": lambda x, d, m, i: maha_ratio(x, d, m),
    "hybrid": SCORERS["hybrid"],
}


def damp_score(fn, x, d, m, idx):
    c = list(idx)
    return fn(np.atleast_2d(x)[:, c], d[:, c], m[:, c], idx)


def cv_balanced(fn, d, m, idx):
    hd = hm = 0
    for f in range(FOLDS):
        td, tm = np.arange(len(d)) % FOLDS == f, np.arange(len(m)) % FOLDS == f
        if not td.any() and not tm.any():
            continue
        if td.any():
            hd += (damp_score(fn, d[td], d[~td], m[~tm], idx) < 0.5).sum()
        if tm.any():
            hm += (damp_score(fn, m[tm], d[~td], m[~tm], idx) >= 0.5).sum()
    return 50.0 * (hd / len(d) + hm / len(m))


def choose(fns, d, m):
    """Best (scorer, features) by cross-validation; smallest feature set first, then list order."""
    if len(d) < MIN_PER_CLASS or len(m) < MIN_PER_CLASS:
        return fns[0], ALL
    best, best_acc = (fns[0], ALL), -1.0
    for c in COMBOS:
        for fn in fns:
            acc = cv_balanced(fn, d, m, c)
            if acc > best_acc:
                best, best_acc = (fn, c), acc
    return best


def self_check(fns, d, m):
    d, m = _dedupe(d, DEDUPE), _dedupe(m, DEDUPE)

    def held_out(x, td, tm):
        fn, c = choose(fns, td, tm)
        return damp_score(fn, x, td, tm, c)[0]

    hd = sum(held_out(d[i], np.delete(d, i, 0), m) < 0.5 for i in range(len(d)))
    hm = sum(held_out(m[i], d, np.delete(m, i, 0)) >= 0.5 for i in range(len(m)))
    return 50.0 * (hd / len(d) + hm / len(m))


def job(args):
    variant, place, x, y = args
    fns = [BASIC[k] for k in PICKABLE] if variant == "pick method" else [SCORERS[variant]]
    return variant, place, self_check(fns, x[y == 0], x[y == 1])


def main():
    places = load_places()
    variants = list(SCORERS) + ["pick method"]
    jobs = [(v, p, x, y) for v in variants for p, (x, y) in places.items()]
    with Pool() as pool:
        results = pool.map(job, jobs)
    table = {(v, p): a for v, p, a in results}
    names = list(places)
    print(f"{'variant':<15}" + "".join(f"{p:>10}" for p in names) + f"{'mean':>8}")
    for v in variants:
        accs = [table[(v, p)] for p in names]
        print(f"{v:<15}" + "".join(f"{a:>9.0f}%" for a in accs) + f"{np.mean(accs):>7.0f}%")


if __name__ == "__main__":
    main()
