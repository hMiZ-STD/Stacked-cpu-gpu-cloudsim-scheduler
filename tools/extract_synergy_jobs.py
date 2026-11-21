#!/usr/bin/env python3
"""Extract the measured jobs (ids 4000..4999) of one Synergy simulator run into a job CSV.

Output columns: job_id,arrival_s,gpus,model,iterations,iter_time_s,jct_s

What Synergy's ``out.log`` contains (simulator/runner.py at commit 1bb2bed). The logger format is
``%(module)s - %(funcName)s - %(levelname)s - %(message)s``.

* ``Runner.start_job`` logs a start line::

      runner - start_job - INFO - [job:<id>:<model>:<gpus>:<cpu>:<mem> (<arrival_s> s,<iters>)] :
          Starting at <wall>s, arr = <arrival_h:.2f>, dur = <iter_time*iters:.2f>

  The ``<arrival_s>`` inside ``str(job)`` is the exact arrival time in seconds.

* ``Runner.finish_job`` logs a finish line::

      runner - finish_job - INFO - [<id>] : Finished <model> at <finish_h:.2f>hrs,
          arrival:<arrival_h:.2f>hrs, iter=<iter_time:.2f>s, num_iter=<iters>

  The finish time is rounded to 0.01 h (36 s), so a JCT computed from the log alone can be up
  to about 18 s off. Synergy also stores the exact per-job JCTs (``self.time -
  job_arrival_time``). Pass one of these sources to get them:

  * ``--jct-csv``: ``graphs/per_run/<run>/job_completion.csv``, written when runner.py runs with
    ``--plot``. Its columns are ``,job id,time (hours),cdf`` and the times are in hours.
  * ``--cache-pickle``: ``cache/stats_<run>.pickle``, written unless ``--no_cache_result``. It is
    loaded with a restricted unpickler, so no Synergy code is imported.

The iteration time comes from the start line as ``dur / iters``. The finish line's ``iter=`` is
rounded to 2 decimals and is used only when the start line is missing.
"""
from __future__ import annotations

import argparse
import csv
import importlib
import math
import pickle
import re
import sys
from dataclasses import dataclass
from pathlib import Path

FIRST_ID, LAST_ID = 4000, 4999
COLUMNS = ["job_id", "arrival_s", "gpus", "model", "iterations", "iter_time_s", "jct_s"]

MODEL_CLASS = {
    "alexnet": "image", "res18": "image", "res50": "image", "mobilenet": "image",
    "shufflenet": "image", "ssd": "image",
    "gnmt": "language", "transformer": "language", "lstm": "language", "bert": "language",
    "vae": "language",
    "m5": "speech", "deepspeech": "speech",
}

_NUM = r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"
START_RE = re.compile(
    r"\[job:(?P<id>\d+):(?P<model>[^:\]]+):(?P<gpus>" + _NUM + r"):[^:\]]*:[^ \]]* "
    r"\((?P<arr>" + _NUM + r") s,(?P<iters>" + _NUM + r")\)\] : Starting at .*?"
    r"dur = (?P<dur>" + _NUM + r")")
FINISH_RE = re.compile(
    r"\[(?P<id>\d+)\] : Finished (?P<model>\S+) at (?P<fin>" + _NUM + r")hrs, "
    r"arrival:(?P<arr>" + _NUM + r")hrs, iter=(?P<iter>" + _NUM + r")s, "
    r"num_iter=(?P<iters>" + _NUM + r")")


class ExtractError(Exception):
    pass


@dataclass
class Start:
    arrival_s: float
    gpus: int
    model: str
    iterations: int
    dur_s: float


@dataclass
class Finish:
    finish_h: float
    arrival_h: float
    model: str
    iter_time_s: float
    iterations: int


def _int(text: str) -> int:
    value = float(text)
    if not value.is_integer():
        raise ExtractError(f"expected an integer, got {text}")
    return int(value)


def parse_log(lines) -> tuple[dict[int, Start], dict[int, Finish]]:
    starts: dict[int, Start] = {}
    finishes: dict[int, Finish] = {}
    dup_starts, dup_finishes = set(), set()
    for line in lines:
        m = START_RE.search(line)
        if m:
            jid = int(m["id"])
            if jid in starts:
                dup_starts.add(jid)
            starts[jid] = Start(float(m["arr"]), _int(m["gpus"]), m["model"],
                                _int(m["iters"]), float(m["dur"]))
            continue
        m = FINISH_RE.search(line)
        if m:
            jid = int(m["id"])
            if jid in finishes:
                dup_finishes.add(jid)
            finishes[jid] = Finish(float(m["fin"]), float(m["arr"]), m["model"],
                                   float(m["iter"]), _int(m["iters"]))
    if dup_starts or dup_finishes:
        some = sorted(dup_starts | dup_finishes)[:5]
        raise ExtractError(
            f"the log has more than one start or finish line for {len(dup_starts | dup_finishes)} "
            f"job(s), e.g. {some}. It probably holds several runs; split it so one run is left.")
    return starts, finishes


# --- exact JCT sources -------------------------------------------------------------------------
def read_jct_csv(path: Path) -> dict[int, float]:
    """Read Synergy's job_completion.csv (hours) or any CSV with job id + jct seconds."""
    with open(path, newline="") as fh:
        reader = csv.DictReader(fh)
        cols = reader.fieldnames or []
        id_col = next((c for c in ("job id", "job_id") if c in cols), None)
        if "time (hours)" in cols:
            val_col, scale = "time (hours)", 3600.0
        else:
            val_col = next((c for c in ("jct_seconds", "jct_s") if c in cols), None)
            scale = 1.0
        if id_col is None or val_col is None:
            raise ExtractError(f"{path}: need a job id column and a JCT column, found {cols}")
        return {int(float(r[id_col])): float(r[val_col]) * scale for r in reader}


class _DataSeriesStub:
    """Stand-in for Synergy's metrics.stats.DataSeries when unpickling."""

    def __setstate__(self, state):
        self.__dict__.update(state)


class _RestrictedUnpickler(pickle.Unpickler):
    _NUMPY_OK = {
        ("numpy", "dtype"), ("numpy", "ndarray"),
        ("numpy.core.multiarray", "scalar"), ("numpy._core.multiarray", "scalar"),
        ("numpy.core.multiarray", "_reconstruct"), ("numpy._core.multiarray", "_reconstruct"),
    }

    def find_class(self, module, name):
        if name == "DataSeries" and module.split(".")[-1] == "stats":
            return _DataSeriesStub
        if (module, name) in self._NUMPY_OK:
            return getattr(importlib.import_module(module), name)
        raise pickle.UnpicklingError(f"refusing to load {module}.{name} from the cache pickle")


def read_cache_pickle(path: Path) -> dict[int, float]:
    with open(path, "rb") as fh:
        data = _RestrictedUnpickler(fh).load()
    if not (isinstance(data, tuple) and len(data) == 2):
        raise ExtractError(f"{path}: expected (total_gpu_demand, job_completion_times)")
    series = getattr(data[1], "data_series", None)
    if series is None:
        raise ExtractError(f"{path}: job_completion_times has no data_series")
    return {int(jid): float(jct) for jid, jct in series}


# --- building the rows -------------------------------------------------------------------------
def build_rows(starts, finishes, exact_jct: dict[int, float] | None,
               first_id: int = FIRST_ID, last_id: int = LAST_ID) -> list[dict]:
    wanted = range(first_id, last_id + 1)
    no_finish = [j for j in wanted if j not in finishes]
    no_start = [j for j in wanted if j not in starts]
    problems = []
    if no_finish:
        problems.append(f"{len(no_finish)} job(s) never finished, e.g. {no_finish[:5]}")
    if no_start:
        problems.append(f"{len(no_start)} job(s) have no start line, e.g. {no_start[:5]}")
    if exact_jct is not None:
        no_jct = [j for j in wanted if j not in exact_jct]
        if no_jct:
            problems.append(f"{len(no_jct)} job(s) missing from the JCT source, e.g. {no_jct[:5]}")
    if problems:
        raise ExtractError("; ".join(problems))

    rows = []
    for jid in wanted:
        s, f = starts[jid], finishes[jid]
        if s.iterations != f.iterations:
            print(f"warning: job {jid}: start line says {s.iterations} iterations, finish line "
                  f"says {f.iterations}; using the start value", file=sys.stderr)
        iter_time = round(s.dur_s / s.iterations, 6) if s.iterations else f.iter_time_s
        if exact_jct is not None:
            jct = exact_jct[jid]
        else:
            jct = f.finish_h * 3600.0 - s.arrival_s
        rows.append({
            "job_id": jid,
            "arrival_s": round(s.arrival_s, 3),
            "gpus": s.gpus,
            "model": f.model,
            "iterations": s.iterations,
            "iter_time_s": iter_time,
            "jct_s": round(jct, 3),
        })
    for r in rows:
        for k in COLUMNS:
            v = r[k]
            if v is None or v == "" or (isinstance(v, float) and math.isnan(v)):
                raise ExtractError(f"job {r['job_id']}: missing {k}")
    return rows


def write_csv(rows: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(rows)


def summary(rows: list[dict]) -> str:
    import pandas as pd  # only needed for the summary percentiles
    jct_h = pd.Series([r["jct_s"] for r in rows]) / 3600.0
    classes: dict[str, int] = {}
    for r in rows:
        c = MODEL_CLASS.get(r["model"], "unknown")
        classes[c] = classes.get(c, 0) + 1
    split = ", ".join(f"{k}={v}" for k, v in sorted(classes.items()))
    return (f"{len(rows)} jobs (ids {rows[0]['job_id']}..{rows[-1]['job_id']}); "
            f"mean JCT {jct_h.mean():.4f} h, p99 {jct_h.quantile(0.99):.3f} h; classes: {split}")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("log", type=Path, help="Synergy out.log of ONE run")
    ap.add_argument("-o", "--out", type=Path, required=True, help="output job CSV")
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--jct-csv", type=Path,
                     help="exact JCTs: graphs/per_run/<run>/job_completion.csv")
    src.add_argument("--cache-pickle", type=Path,
                     help="exact JCTs: cache/stats_<run>.pickle")
    ap.add_argument("--first-id", type=int, default=FIRST_ID)
    ap.add_argument("--last-id", type=int, default=LAST_ID)
    args = ap.parse_args(argv)

    try:
        with open(args.log, errors="replace") as fh:
            starts, finishes = parse_log(fh)
        exact = None
        if args.jct_csv:
            exact = read_jct_csv(args.jct_csv)
        elif args.cache_pickle:
            exact = read_cache_pickle(args.cache_pickle)
        else:
            print("warning: no --jct-csv/--cache-pickle given; JCTs come from the log's finish "
                  "time, which is rounded to 0.01 h (up to ~18 s error)", file=sys.stderr)
        rows = build_rows(starts, finishes, exact, args.first_id, args.last_id)
    except (OSError, ExtractError, pickle.UnpicklingError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    write_csv(rows, args.out)
    print(f"wrote {args.out}: {summary(rows)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
