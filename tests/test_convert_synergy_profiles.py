import json
import textwrap

import pytest

import convert_synergy_profiles as conv
from conftest import SYNERGY_REPO, require

FAKE_MODEL = textwrap.dedent("""
    from models.model_stats import ModelStats
    import numpy as np

    class fake_2(ModelStats):
        def __init__(self, name):
          super().__init__(name, 2)

        def update_stats(self):
            self.cpus = 9
            self.mem = 62.5*2
            self.speed = int(62.5/3)
            self.batch = 512
            self.speedup = 2.0
            self.iter_time = 0.5
            self.tput = np.array([
                 [0.1, 0.3, 0.3, 0.3, 0.3],
                 [0.1, 0.6, 0.6, 0.6, 0.6],
                 [0.1, 1, 1, 1, 1],
                 [0.1, 1.2, 1.2, 1.2, 1.2],
                 [0.1, 1.4, 1.4, 1.4, 1.4],
                 [0.1, 1.6, 1.6, 1.6, 1.6],
                 [0.1, 2, 2, 2, 2],
                 [0.1, 2, 2, 2, 2],
                 [0.1, 2, 2, 2, 2]
                 ])
            #[9, 9, 9]
""")


# --- unit tests (no Synergy checkout needed) ----------------------------------------------------
def test_parse_fake_model_evaluates_expressions():
    raw = conv.parse_model_source(FAKE_MODEL)
    assert raw.class_name == "fake_2"
    assert raw.gpu_count == 2
    assert raw.fields["mem"] == 125.0
    assert raw.fields["speed"] == 20
    assert len(raw.fields["tput"]) == 9


def test_build_profile_axes_and_defaults():
    warnings = []
    p = conv.build_profile(conv.parse_model_source(FAKE_MODEL), "simulator/models/fake_2.py",
                           warnings)
    assert p["model"] == "fake" and p["gpu_count"] == 2 and p["gpu_type"] == "V100"
    assert p["cpus"] == [1, 2, 3, 4, 5, 6, 9, 12, 24]
    assert p["mem_gb_per_gpu"] == [20.83, 62.5, 125, 187.5, 250]
    assert p["mem_fractions"][-1] == 1.0
    assert p["placement_penalty"] == 1 and "placement_penalty" in p["defaults_used"]
    assert p["iter_time_base_s"] is None
    assert conv.throughput_at(p, 9, 1.0) == 2.0
    assert p["fair_share"]["throughput"] == 1.0
    assert warnings == []


def test_unsafe_expression_is_rejected():
    src = FAKE_MODEL.replace("self.batch = 512", "self.batch = __import__('os').getpid()")
    with pytest.raises(conv.ProfileParseError):
        conv.parse_model_source(src)


def test_four_column_matrix_uses_legacy_mapping():
    src = FAKE_MODEL.replace("[0.1, ", "[")
    p = conv.build_profile(conv.parse_model_source(src), "simulator/models/fake_2.py", [])
    assert p["mem_gb_per_gpu"] == [62.5, 125, 187.5, 250]
    assert p["fair_share"]["throughput"] == 1.0
    assert p["used_by_synergy"] is False


def test_wrong_row_count_is_an_error():
    src = FAKE_MODEL.replace("[0.1, 2, 2, 2, 2]\n", "", 1)
    with pytest.raises(conv.ProfileParseError, match="rows"):
        conv.build_profile(conv.parse_model_source(src), "x.py", [])


# --- tests against the real Synergy checkout (skipped if it is not present) --------------------
@pytest.fixture(scope="module")
def converted(tmp_path_factory):
    repo = require(SYNERGY_REPO, "Synergy repo")
    out = tmp_path_factory.mktemp("profiles")
    profiles, written, _ = conv.convert_all(repo, out)
    return repo, out, profiles, written


def test_res18_ideal_full_memory_is_2_4(converted):
    _, out, _, _ = converted
    p = json.loads((out / "models" / "res18_1.json").read_text())
    assert p["ideal_cpus_per_gpu"] == 9
    assert conv.throughput_at(p, p["ideal_cpus_per_gpu"], 1.0) == pytest.approx(2.4)
    assert p["ideal_speedup"] == pytest.approx(2.4)


def test_every_profile_is_1_at_fixed_share(converted):
    _, _, profiles, _ = converted
    # Profiles that are not 1.0 at 3 CPUs/GPU (full memory, or the fair 62.5 GB column).
    # Keep this list in sync with the README; at Synergy commit 1bb2bed it is empty.
    known_exceptions = set()
    found = {e["source"] for e in conv.fair_share_exceptions(profiles)}
    assert found == known_exceptions
    for p in profiles:
        assert conv.throughput_at(p, 3, 1.0) == pytest.approx(1.0) or p["source"] in found


def test_all_model_files_convert(converted):
    repo, out, profiles, written = converted
    sources = conv.model_files(repo / "simulator")
    assert len(sources) > 0
    assert len(written) == len(sources) == len(profiles)
    assert len(list(out.rglob("*.json"))) == len(sources)
