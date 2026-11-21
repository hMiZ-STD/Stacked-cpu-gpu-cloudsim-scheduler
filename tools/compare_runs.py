#!/usr/bin/env python3
"""Compare the per-job JCTs of our run against a reference run (e.g. Synergy's FIFO+tune).

Both files are CSVs. Only two columns are used, found by name:
  job id : ``job_id`` (our CSV) or ``job id`` (Synergy reference)
  JCT    : ``jct_s`` (our CSV) or ``jct_seconds`` (Synergy reference), in seconds

The result is printed and also written as JSON. It contains:
mean / p99 / p99.9 JCT in hours, the relative error of the mean, job counts, the jobs that
appear in only one file, the share of common jobs within 1 s, and the largest difference.

The verdict is PASS when abs(mean_ours - mean_ref) / mean_ref <= 0.10, else FAIL. Percentiles
use pandas' default linear interpolation, as Synergy's own report does. The exit code is 0 for
PASS, 2 for FAIL and 1 for bad input.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pandas as pd

ID_COLUMNS = ("job_id", "job id")
JCT_COLUMNS = ("jct_s", "jct_seconds")
PASS_THRESHOLD = 0.10
SAME_JCT_TOLERANCE_S = 1.0


class CompareError(Exception):
    pass


def load_jcts(path: Path) -> pd.Series:
    """Return a Series of JCT seconds indexed by integer job id."""
    df = pd.read_csv(path)
    id_col = next((c for c in ID_COLUMNS if c in df.columns), None)
    jct_col = next((c for c in JCT_COLUMNS if c in df.columns), None)
    if id_col is None or jct_col is None:
        raise CompareError(f"{path}: need one of {ID_COLUMNS} and one of {JCT_COLUMNS}; "
                           f"found {list(df.columns)}")
    df = df[[id_col, jct_col]]
    if df.isna().any().any():
        bad = df[df.isna().any(axis=1)][id_col].tolist()[:5]
        raise CompareError(f"{path}: missing values in {id_col}/{jct_col}, e.g. rows {bad}")
    ids = df[id_col].astype("int64")
    dups = ids[ids.duplicated()].unique().tolist()
    if dups:
        raise CompareError(f"{path}: duplicate job ids, e.g. {dups[:5]}")
    return pd.Series(df[jct_col].astype(float).values, index=ids.values, name="jct_s")


def stats_hours(jct_s: pd.Series) -> dict:
    h = jct_s / 3600.0
    return {
        "jobs": int(len(h)),
        "mean_h": float(h.mean()),
        "p99_h": float(h.quantile(0.99)),
        "p999_h": float(h.quantile(0.999)),
    }


def compare(ours: pd.Series, ref: pd.Series) -> dict:
    if len(ours) == 0 or len(ref) == 0:
        raise CompareError("one of the files has no jobs")
    s_ours, s_ref = stats_hours(ours), stats_hours(ref)
    rel_err = abs(s_ours["mean_h"] - s_ref["mean_h"]) / s_ref["mean_h"]

    common = ours.index.intersection(ref.index).sort_values()
    only_ours = sorted(int(i) for i in ours.index.difference(ref.index))
    only_ref = sorted(int(i) for i in ref.index.difference(ours.index))

    if len(common):
        diff = (ours.loc[common] - ref.loc[common]).abs()
        within = float((diff < SAME_JCT_TOLERANCE_S).mean())
        worst_id = int(diff.idxmax())
        worst = {"job_id": worst_id, "abs_diff_s": float(diff.loc[worst_id]),
                 "ours_s": float(ours.loc[worst_id]), "ref_s": float(ref.loc[worst_id])}
    else:
        within, worst = None, None

    return {
        "ours": s_ours,
        "ref": s_ref,
        "relative_error_mean": rel_err,
        "jobs_common": int(len(common)),
        "jobs_only_in_ours": only_ours,
        "jobs_only_in_ref": only_ref,
        "share_common_within_1s": within,
        "largest_difference": worst,
        "pass_threshold": PASS_THRESHOLD,
        "verdict": "PASS" if rel_err <= PASS_THRESHOLD else "FAIL",
    }


def render(result: dict, ours_name: str, ref_name: str) -> str:
    o, r = result["ours"], result["ref"]

    def ids(lst):
        return (", ".join(map(str, lst[:20])) + (" ..." if len(lst) > 20 else "")) or "-"

    worst = result["largest_difference"]
    within = result["share_common_within_1s"]
    lines = [
        f"{'':18}{'ours':>14}{'ref':>14}",
        f"{'jobs':18}{o['jobs']:>14}{r['jobs']:>14}",
        f"{'mean JCT (h)':18}{o['mean_h']:>14.4f}{r['mean_h']:>14.4f}",
        f"{'p99 JCT (h)':18}{o['p99_h']:>14.3f}{r['p99_h']:>14.3f}",
        f"{'p99.9 JCT (h)':18}{o['p999_h']:>14.3f}{r['p999_h']:>14.3f}",
        "",
        f"ours: {ours_name}",
        f"ref:  {ref_name}",
        f"relative error of mean: {result['relative_error_mean']:.4f} "
        f"(pass if <= {result['pass_threshold']})",
        f"jobs in both: {result['jobs_common']}",
        f"only in ours ({len(result['jobs_only_in_ours'])}): {ids(result['jobs_only_in_ours'])}",
        f"only in ref  ({len(result['jobs_only_in_ref'])}): {ids(result['jobs_only_in_ref'])}",
        "common jobs within 1 s: " + ("-" if within is None else f"{within:.2%}"),
        "largest difference: " + ("-" if worst is None else
                                  f"{worst['abs_diff_s']:.3f} s on job {worst['job_id']} "
                                  f"(ours {worst['ours_s']:.3f}, ref {worst['ref_s']:.3f})"),
        f"verdict: {result['verdict']}",
    ]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("ours", type=Path, help="our job CSV (job_id, jct_s, ...)")
    ap.add_argument("ref", type=Path, help="reference CSV (job id, jct_seconds, ...)")
    ap.add_argument("--json", type=Path,
                    help="where to write the JSON result (default: <ours>.compare.json)")
    args = ap.parse_args(argv)

    try:
        result = compare(load_jcts(args.ours), load_jcts(args.ref))
    except (OSError, CompareError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    out = args.json or Path(f"{args.ours}.compare.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"ours_file": str(args.ours), "ref_file": str(args.ref), **result},
                              indent=2) + "\n")
    print(render(result, str(args.ours), str(args.ref)))
    print(f"(JSON written to {out})")
    return 0 if result["verdict"] == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
