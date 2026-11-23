"""Shared paths for the tool tests.

Local data is not in git. Defaults assume the layout  <Experiment>/cloudsim/tools  next to
<Experiment>/synergy/{repo, synergy_ref_fifo_tune_9jph.csv}. Override with environment variables:

  SYNERGY_REPO          Synergy checkout (folder that contains simulator/)
  SYNERGY_REF           synergy_ref_fifo_tune_9jph.csv
  SYNERGY_OUT_LOG       out.log of the FIFO+tune, 9 jobs/h, (20,70,10), seed 42 run
  SYNERGY_JCT_CSV       graphs/per_run/<run>/job_completion.csv of that run (exact JCTs), or
  SYNERGY_CACHE_PICKLE  cache/stats_<run>.pickle of that run (exact JCTs)

Tests that need data that is missing are skipped, not failed.
"""
import os
import sys
from pathlib import Path

import pytest

TOOLS_DIR = Path(__file__).resolve().parents[1]
EXPERIMENT_DIR = TOOLS_DIR.parents[1]
DATA_DIR = Path(__file__).resolve().parent / "data"

sys.path.insert(0, str(TOOLS_DIR))


def _env_path(name: str, default: Path | None) -> Path | None:
    value = os.environ.get(name)
    if value:
        return Path(value).expanduser()
    return default


SYNERGY_REPO = _env_path("SYNERGY_REPO", EXPERIMENT_DIR / "synergy" / "repo")
SYNERGY_REF = _env_path("SYNERGY_REF", EXPERIMENT_DIR / "synergy" / "synergy_ref_fifo_tune_9jph.csv")
SYNERGY_OUT_LOG = _env_path("SYNERGY_OUT_LOG", EXPERIMENT_DIR / "synergy" / "out.log")
SYNERGY_JCT_CSV = _env_path("SYNERGY_JCT_CSV", None)
SYNERGY_CACHE_PICKLE = _env_path("SYNERGY_CACHE_PICKLE", None)


def require(path: Path | None, what: str) -> Path:
    if path is None or not path.exists():
        pytest.skip(f"{what} not found ({path}); set the env var to run this test")
    return path
