import json

import pandas as pd
import pytest

import prep_philly as pp
from conftest import DATA_DIR

SAMPLE = DATA_DIR / "philly_sample.json"

# First submitted_time in the file is the FAILED job at 2017-09-30 23:50:00, so every kept
# arrival is shifted by +600 s. Rows are sorted by arrival.
# (philly id, arrival_s, gpus, duration_s, team)
EXPECTED = [
    ("application_0001", 600, 1, 3600, "vcA"),
    ("application_0004", 1500, 16, 7200, "vcB"),
    ("application_0005", 2100, 8, 4200, "vcC"),   # 2 attempts: 600 s + 3600 s, last one 8 GPUs
    ("application_0006", 3000, 4, 600, "vcA"),
    ("application_0007", 4200, 1, 1800, "vcC"),
    ("application_0008", 6000, 2, 7200, "vcB"),
    ("application_0009", 7800, 8, 1800, "vcA"),
    ("application_0010", 11400, 1, 600, "vcD"),
]

BASE_FILTERS = {
    "status Failed (not Pass)": 1,
    "status Killed (not Pass)": 1,
    "bad or missing submitted_time": 0,
    "no attempt with valid start/end times": 0,
    "requests 0 GPUs": 0,
    "duration <= 0 s": 0,
}


def load():
    return json.loads(SAMPLE.read_text())


def check_rows(rows, ids, expected):
    assert ids == [e[0] for e in expected]
    for i, (row, (_, arrival, gpus, dur, team)) in enumerate(zip(rows, expected)):
        assert row == {"job_id": i, "arrival_s": arrival, "gpus": gpus, "duration_s": dur,
                       "model": "", "team": team, "priority": "", "status": "Pass"}


def test_sample_has_ten_jobs():
    assert len(load()) == 10


def test_default_keeps_pass_jobs():
    rows, ids, table = pp.prepare(load(), strict=False)
    check_rows(rows, ids, EXPECTED)
    assert table.as_dict() == BASE_FILTERS
    assert table.remaining == 8


def test_strict_drops_16_gpu_job():
    rows, ids, table = pp.prepare(load(), strict=True)
    check_rows(rows, ids, [e for e in EXPECTED if e[0] != "application_0004"])
    assert table.as_dict() == {**BASE_FILTERS, "more than 8 GPUs (--strict)": 1}
    assert table.remaining == 7


def test_counts_add_up():
    for strict in (False, True):
        rows, _, table = pp.prepare(load(), strict=strict)
        assert table.n_input == sum(table.as_dict().values()) + len(rows)


@pytest.mark.parametrize("strict,expected_rows", [(False, 8), (True, 7)])
def test_main_writes_csv_and_filter_table(tmp_path, capsys, strict, expected_rows):
    out = tmp_path / "philly.csv"
    argv = [str(SAMPLE), "-o", str(out)] + (["--strict"] if strict else [])
    assert pp.main(argv) == 0

    df = pd.read_csv(out, keep_default_na=False)
    assert list(df.columns) == pp.COLUMNS
    assert len(df) == expected_rows
    assert (df["status"] == "Pass").all()

    filters = (tmp_path / "philly.csv.filters.txt").read_text()
    printed = capsys.readouterr().out
    assert filters.strip() in printed
    assert "status Failed (not Pass)" in filters and "status Killed (not Pass)" in filters
    assert ("more than 8 GPUs (--strict)" in filters) == strict

    ids = pd.read_csv(tmp_path / "philly.csv.ids.csv")
    assert len(ids) == expected_rows


def test_zero_gpu_and_none_times_are_counted():
    jobs = load()
    jobs.append({"status": "Pass", "vc": "vcE", "jobid": "z1", "submitted_time": "2017-10-01 04:00:00",
                 "attempts": [{"start_time": "2017-10-01 04:00:00", "end_time": "2017-10-01 05:00:00",
                               "detail": []}]})
    jobs.append({"status": "Pass", "vc": "vcE", "jobid": "z2", "submitted_time": "2017-10-01 04:00:00",
                 "attempts": [{"start_time": "None", "end_time": "None", "detail": []}]})
    rows, _, table = pp.prepare(jobs)
    d = table.as_dict()
    assert d["requests 0 GPUs"] == 1
    assert d["no attempt with valid start/end times"] == 1
    assert len(rows) == 8
