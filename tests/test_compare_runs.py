import json

import pandas as pd
import pytest

import compare_runs as cr
from conftest import SYNERGY_REF


@pytest.fixture
def ref_csv(tmp_path):
    """The real reference if present, else a synthetic one in the same format."""
    if SYNERGY_REF is not None and SYNERGY_REF.exists():
        return SYNERGY_REF
    path = tmp_path / "ref.csv"
    pd.DataFrame({
        "job id": range(4000, 4100),
        "jct_seconds": [1000 + 37 * i for i in range(100)],
        "model": "res18",
        "num_iter": 100,
        "iter_time_s": 0.64,
    }).to_csv(path, index=False)
    return path


def ours_from_ref(ref_csv, tmp_path, scale=1.0, drop=0):
    ref = pd.read_csv(ref_csv)
    ours = pd.DataFrame({"job_id": ref["job id"], "arrival_s": 0, "gpus": 1,
                         "jct_s": ref["jct_seconds"] * scale})
    dropped = sorted(ours["job_id"].iloc[[0, len(ours) // 2, len(ours) - 1]].tolist())[:drop]
    ours = ours[~ours["job_id"].isin(dropped)]
    path = tmp_path / "ours.csv"
    ours.to_csv(path, index=False)
    return path, dropped


def test_reference_against_itself_passes(ref_csv, tmp_path):
    out = tmp_path / "self.json"
    assert cr.main([str(ref_csv), str(ref_csv), "--json", str(out)]) == 0
    result = json.loads(out.read_text())
    assert result["relative_error_mean"] == 0
    assert result["verdict"] == "PASS"
    assert result["jobs_only_in_ours"] == [] and result["jobs_only_in_ref"] == []
    assert result["share_common_within_1s"] == 1.0
    assert result["largest_difference"]["abs_diff_s"] == 0
    assert result["ours"] == result["ref"]


def test_scaled_by_1_2_fails(ref_csv, tmp_path):
    ours, _ = ours_from_ref(ref_csv, tmp_path, scale=1.2)
    out = tmp_path / "scaled.json"
    assert cr.main([str(ours), str(ref_csv), "--json", str(out)]) == 2
    result = json.loads(out.read_text())
    assert result["relative_error_mean"] == pytest.approx(0.2)
    assert result["verdict"] == "FAIL"
    assert result["ours"]["p99_h"] == pytest.approx(1.2 * result["ref"]["p99_h"])


def test_three_jobs_removed_are_reported(ref_csv, tmp_path):
    ours, dropped = ours_from_ref(ref_csv, tmp_path, drop=3)
    assert len(dropped) == 3
    result = cr.compare(cr.load_jcts(ours), cr.load_jcts(ref_csv))
    assert result["jobs_only_in_ref"] == dropped
    assert result["jobs_only_in_ours"] == []
    assert result["jobs_common"] == result["ref"]["jobs"] - 3
    assert result["ours"]["jobs"] == result["ref"]["jobs"] - 3


def test_largest_difference_names_the_job(tmp_path):
    ref = tmp_path / "r.csv"
    ours = tmp_path / "o.csv"
    pd.DataFrame({"job id": [1, 2, 3], "jct_seconds": [100, 200, 300]}).to_csv(ref, index=False)
    pd.DataFrame({"job_id": [1, 2, 3], "jct_s": [100.5, 260, 300]}).to_csv(ours, index=False)
    result = cr.compare(cr.load_jcts(ours), cr.load_jcts(ref))
    assert result["largest_difference"]["job_id"] == 2
    assert result["largest_difference"]["abs_diff_s"] == pytest.approx(60)
    assert result["share_common_within_1s"] == pytest.approx(2 / 3)


def test_missing_columns_is_an_error(tmp_path):
    bad = tmp_path / "bad.csv"
    pd.DataFrame({"id": [1], "time": [2]}).to_csv(bad, index=False)
    with pytest.raises(cr.CompareError):
        cr.load_jcts(bad)
