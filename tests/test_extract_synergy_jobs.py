import csv

import pandas as pd
import pytest

import extract_synergy_jobs as ex
from conftest import (SYNERGY_CACHE_PICKLE, SYNERGY_JCT_CSV, SYNERGY_OUT_LOG, SYNERGY_REF,
                      require)


def start_line(jid, model, gpus, arrival_s, iters, iter_time):
    return (f"runner - start_job - INFO - [job:{jid}:{model}:{gpus}:-1:-1 ({arrival_s} s,{iters})] "
            f": Starting at 0.12s, arr = {arrival_s / 3600:.2f}, dur = {iter_time * iters:.2f}\n")


def finish_line(jid, model, finish_s, arrival_s, iters, iter_time):
    return (f"runner - finish_job - INFO - [{jid}] : Finished {model} at {finish_s / 3600:.2f}hrs, "
            f"arrival:{arrival_s / 3600:.2f}hrs, iter={iter_time:.2f}s, num_iter={iters}\n")


# job_id: (model, gpus, arrival_s, iters, iter_time, finish_s)
JOBS = {
    3999: ("res18", 1, 100.0, 1000, 0.64, 900.0),
    4000: ("lstm", 1, 3600.5, 212982, 0.011, 3600.5 + 15371.0),
    4001: ("m5", 2, 7200.25, 8703, 0.6, 7200.25 + 14242.0),
}


def sample_log():
    lines = ["Running scheduler FIFO+tune - ('FIFO-Tune', 9.0, (20, 70, 10))\n",
             "scheduler - schedule - INFO - noise line [job:x]\n"]
    for jid, (model, gpus, arr, iters, it, _) in JOBS.items():
        lines.append(start_line(jid, model, gpus, arr, iters, it))
    for jid, (model, gpus, arr, iters, it, fin) in JOBS.items():
        lines.append(finish_line(jid, model, fin, arr, iters, it))
    return lines


def test_parse_log_reads_start_and_finish():
    starts, finishes = ex.parse_log(sample_log())
    assert set(starts) == set(finishes) == set(JOBS)
    assert starts[4000].arrival_s == 3600.5
    assert starts[4001].gpus == 2
    assert finishes[4000].model == "lstm"


def test_rows_from_log_only_are_close():
    starts, finishes = ex.parse_log(sample_log())
    rows = ex.build_rows(starts, finishes, None, 4000, 4001)
    assert [r["job_id"] for r in rows] == [4000, 4001]
    assert rows[0]["iter_time_s"] == pytest.approx(0.011, abs=1e-6)
    assert rows[0]["iterations"] == 212982
    # finish time is rounded to 0.01 h in the log -> error at most 18 s
    assert abs(rows[0]["jct_s"] - 15371.0) <= 18.0


def test_rows_with_exact_jct_csv(tmp_path):
    jct_csv = tmp_path / "job_completion.csv"
    with open(jct_csv, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["", "job id", "time (hours)", "cdf"])
        w.writerow([0, 4000, 15371.0 / 3600, 0.5])
        w.writerow([1, 4001, 14242.0 / 3600, 1.0])
    starts, finishes = ex.parse_log(sample_log())
    rows = ex.build_rows(starts, finishes, ex.read_jct_csv(jct_csv), 4000, 4001)
    assert rows[0]["jct_s"] == pytest.approx(15371.0, abs=1e-3)
    assert rows[1]["jct_s"] == pytest.approx(14242.0, abs=1e-3)


def test_missing_finish_is_an_error():
    lines = [l for l in sample_log() if "[4001] : Finished" not in l]
    starts, finishes = ex.parse_log(lines)
    with pytest.raises(ex.ExtractError, match="never finished"):
        ex.build_rows(starts, finishes, None, 4000, 4001)


def test_two_runs_in_one_log_is_an_error():
    with pytest.raises(ex.ExtractError, match="more than one"):
        ex.parse_log(sample_log() + sample_log())


def test_main_writes_csv(tmp_path):
    log = tmp_path / "out.log"
    log.write_text("".join(sample_log()))
    out = tmp_path / "jobs.csv"
    assert ex.main([str(log), "-o", str(out), "--first-id", "4000", "--last-id", "4001"]) == 0
    df = pd.read_csv(out)
    assert list(df.columns) == ex.COLUMNS
    assert len(df) == 2 and not df.isna().any().any()


# --- the real run (skipped unless out.log and an exact JCT source are available) --------------
def test_real_run_matches_reference(tmp_path):
    log = require(SYNERGY_OUT_LOG, "Synergy out.log (SYNERGY_OUT_LOG)")
    ref_path = require(SYNERGY_REF, "reference CSV (SYNERGY_REF)")
    if SYNERGY_JCT_CSV and SYNERGY_JCT_CSV.exists():
        args = ["--jct-csv", str(SYNERGY_JCT_CSV)]
    elif SYNERGY_CACHE_PICKLE and SYNERGY_CACHE_PICKLE.exists():
        args = ["--cache-pickle", str(SYNERGY_CACHE_PICKLE)]
    else:
        pytest.skip("set SYNERGY_JCT_CSV or SYNERGY_CACHE_PICKLE for exact JCTs")
    out = tmp_path / "synergy_jobs.csv"
    assert ex.main([str(log), "-o", str(out), *args]) == 0

    df = pd.read_csv(out)
    assert len(df) == 1000
    assert sorted(df["job_id"]) == list(range(4000, 5000))
    assert not df.isna().any().any()

    classes = df["model"].map(ex.MODEL_CLASS).value_counts().to_dict()
    assert classes == {"image": 200, "language": 700, "speech": 100}

    ref = pd.read_csv(ref_path).set_index("job id")["jct_seconds"]
    ours = df.set_index("job_id")["jct_s"]
    diff = (ours - ref.loc[ours.index]).abs()
    bad = diff[diff >= 1.0]
    assert bad.empty, f"{len(bad)} jobs differ from the reference by >= 1 s:\n{bad.head(20)}"

    jct_h = df["jct_s"] / 3600
    assert jct_h.mean() == pytest.approx(21.8766, abs=1e-3)
    assert jct_h.quantile(0.99) == pytest.approx(156.933, abs=1e-2)
