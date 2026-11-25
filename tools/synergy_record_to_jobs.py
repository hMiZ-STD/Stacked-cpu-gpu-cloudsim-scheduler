#!/usr/bin/env python3
"""Convert the job trace Synergy's simulator records with --record_trace into our job CSV.

Synergy writes one line per job it generates, at generation time, so the file has *every* job of the
run, including the ones still unfinished when it stops (runner.py, record_job):

    job_id,model,arrival_time_s,total_iterations,gpus

Output (docs/formats.md): job_id,arrival_s,gpus,model,iterations
No iter_time_s column: Synergy uses the profile's iter_time for every job (jobs/model.py,
use_scores_from_tput), and so does the simulator when the column is absent.

Usage: synergy_record_to_jobs.py <record file> -o <jobs.csv>
"""
from __future__ import annotations

import argparse
import csv
import sys


def convert(src: str, dst: str) -> int:
    rows = []
    with open(src, newline="") as fh:
        for n, line in enumerate(csv.reader(fh), start=1):
            if not line:
                continue
            if len(line) != 5:
                raise ValueError(f"{src} line {n}: expected 5 fields, got {len(line)}: {line}")
            job_id, model, arrival, iterations, gpus = line
            rows.append((int(job_id), arrival, int(gpus), model, int(iterations)))
    ids = [r[0] for r in rows]
    if len(set(ids)) != len(ids):
        raise ValueError(f"{src}: duplicate job ids")
    with open(dst, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["job_id", "arrival_s", "gpus", "model", "iterations"])
        for r in sorted(rows):
            w.writerow(r)
    return len(rows)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("record", help="file written by Synergy's --record_trace (e.g. record9.0_tune)")
    ap.add_argument("-o", "--out", required=True, help="output job CSV")
    args = ap.parse_args(argv)
    n = convert(args.record, args.out)
    print(f"wrote {n} jobs to {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
