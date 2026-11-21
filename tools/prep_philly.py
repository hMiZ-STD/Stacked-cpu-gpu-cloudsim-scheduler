#!/usr/bin/env python3
"""Turn the Microsoft Philly trace (cluster_job_log) into a job CSV for the simulator.

Output columns: job_id,arrival_s,gpus,duration_s,model,team,priority,status

Input: ``cluster_job_log`` from https://github.com/msr-fiddle/philly-traces. It is a JSON list of
jobs::

    {"status": "Pass"|"Failed"|"Killed", "vc": "<virtual cluster>", "jobid": "application_...",
     "user": "...", "submitted_time": "YYYY-MM-DD HH:MM:SS",
     "attempts": [{"start_time": "...", "end_time": "...",
                   "detail": [{"ip": "m372", "gpus": ["gpu0", ...]}, ...]}, ...]}

Rules:
* Only successfully completed jobs are kept (``status == "Pass"``).
* ``--strict`` also drops jobs that request more than 8 GPUs (STRICT locality, one server).
* ``team`` is the job's Philly virtual cluster (``vc``).
* ``arrival_s`` is seconds after the first ``submitted_time`` in the file, counted over ALL jobs
  before any filter.
* ``gpus`` is the number of GPUs of the job's last attempt that has valid times, summed over all
  machines in ``detail``.
* ``duration_s`` is the sum of (end - start) over all attempts with valid times. This is the
  same as Synergy's ``sum_attempts`` mode.
* ``job_id`` is 0..N-1 in arrival order. The Philly id of each job is written to
  ``<output>.ids.csv``.
* ``model`` and ``priority`` are left empty. The trace does not have them.

Every job a filter removes is counted. The table is printed and also written to
``<output>.filters.txt``.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from datetime import datetime
from pathlib import Path

COLUMNS = ["job_id", "arrival_s", "gpus", "duration_s", "model", "team", "priority", "status"]
TIME_FMT = "%Y-%m-%d %H:%M:%S"
STRICT_MAX_GPUS = 8


def parse_time(value) -> datetime | None:
    if not isinstance(value, str) or value.strip() in ("", "None"):
        return None
    try:
        return datetime.strptime(value.strip(), TIME_FMT)
    except ValueError:
        return None


def _valid_attempts(job: dict) -> list[tuple[datetime, datetime, int]]:
    out = []
    for a in job.get("attempts") or []:
        start, end = parse_time(a.get("start_time")), parse_time(a.get("end_time"))
        if start is None or end is None:
            continue
        gpus = sum(len(d.get("gpus") or []) for d in (a.get("detail") or []))
        out.append((start, end, gpus))
    return out


class FilterTable:
    def __init__(self, n_input: int):
        self.n_input = n_input
        self.rows: list[tuple[str, int, int]] = []
        self.remaining = n_input

    def add(self, name: str, removed: int) -> None:
        self.remaining -= removed
        self.rows.append((name, removed, self.remaining))

    def render(self) -> str:
        width = max([len("filter"), len("input jobs")] + [len(r[0]) for r in self.rows])
        lines = [f"{'filter':<{width}}  {'removed':>8}  {'remaining':>9}",
                 f"{'input jobs':<{width}}  {'-':>8}  {self.n_input:>9}"]
        lines += [f"{name:<{width}}  {removed:>8}  {remaining:>9}"
                  for name, removed, remaining in self.rows]
        lines.append(f"{'kept':<{width}}  {'':>8}  {self.remaining:>9}")
        return "\n".join(lines)

    def as_dict(self) -> dict[str, int]:
        return {name: removed for name, removed, _ in self.rows}


def prepare(jobs: list[dict], strict: bool = False) -> tuple[list[dict], list[str], FilterTable]:
    """Apply the filters. Returns (rows, philly ids in row order, filter table)."""
    table = FilterTable(len(jobs))

    submitted = [parse_time(j.get("submitted_time")) for j in jobs]
    valid_times = [t for t in submitted if t is not None]
    if not valid_times:
        raise ValueError("no job in the trace has a valid submitted_time")
    t0 = min(valid_times)

    # 1. status: one row per non-Pass status, so killed and failed are counted separately.
    removed_by_status: dict[str, int] = {}
    pool = []
    for job, sub in zip(jobs, submitted):
        status = str(job.get("status"))
        if status != "Pass":
            removed_by_status[status] = removed_by_status.get(status, 0) + 1
        else:
            pool.append((job, sub))
    for status in sorted(removed_by_status):
        table.add(f"status {status} (not Pass)", removed_by_status[status])

    def apply(name: str, keep) -> None:
        nonlocal pool
        before = len(pool)
        pool = [item for item in pool if keep(item)]
        table.add(name, before - len(pool))

    apply("bad or missing submitted_time", lambda it: it[1] is not None)

    enriched = []
    for job, sub in pool:
        enriched.append((job, sub, _valid_attempts(job)))
    pool = enriched
    apply("no attempt with valid start/end times", lambda it: len(it[2]) > 0)
    apply("requests 0 GPUs", lambda it: it[2][-1][2] > 0)
    apply("duration <= 0 s", lambda it: sum((e - s).total_seconds() for s, e, _ in it[2]) > 0)
    if strict:
        apply(f"more than {STRICT_MAX_GPUS} GPUs (--strict)",
              lambda it: it[2][-1][2] <= STRICT_MAX_GPUS)

    pool.sort(key=lambda it: (it[1], str(it[0].get("jobid"))))
    rows, philly_ids = [], []
    for new_id, (job, sub, attempts) in enumerate(pool):
        duration = sum((e - s).total_seconds() for s, e, _ in attempts)
        rows.append({
            "job_id": new_id,
            "arrival_s": int((sub - t0).total_seconds()),
            "gpus": attempts[-1][2],
            "duration_s": int(duration),
            "model": "",
            "team": job.get("vc") or "",
            "priority": "",
            "status": "Pass",
        })
        philly_ids.append(str(job.get("jobid")))
    return rows, philly_ids, table


def write_outputs(out: Path, rows: list[dict], philly_ids: list[str], table: FilterTable) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(rows)
    with open(f"{out}.ids.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["job_id", "philly_jobid"])
        w.writerows((r["job_id"], pid) for r, pid in zip(rows, philly_ids))
    Path(f"{out}.filters.txt").write_text(table.render() + "\n")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("trace", type=Path, help="Philly cluster_job_log (JSON)")
    ap.add_argument("-o", "--out", type=Path, required=True, help="output job CSV")
    ap.add_argument("--strict", action="store_true",
                    help=f"also drop jobs requesting more than {STRICT_MAX_GPUS} GPUs")
    args = ap.parse_args(argv)

    try:
        with open(args.trace) as fh:
            jobs = json.load(fh)
        if not isinstance(jobs, list):
            raise ValueError("expected a JSON list of jobs")
        rows, ids, table = prepare(jobs, strict=args.strict)
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    write_outputs(args.out, rows, ids, table)
    print(table.render())
    print(f"wrote {len(rows)} jobs to {args.out} (filters: {args.out}.filters.txt, "
          f"Philly ids: {args.out}.ids.csv)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
