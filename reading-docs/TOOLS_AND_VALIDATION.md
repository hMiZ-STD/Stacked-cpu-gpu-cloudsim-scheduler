# Python Tools, Presets & Phase 4 Validation Guide

This guide explains the Python data-preparation/comparison scripts in `tools/`, the Python test suite in `tests/`, all checked-in `presets/`, and how Phase 4 proved an exact **1,000/1,000 per-job match** against Synergy's simulator.

---

## 1. Python Tools (`tools/`)

The Java simulator only reads three clean formats documented in [docs/formats.md](../docs/formats.md): **Job CSV**, **Profile JSON**, and **Experiment YAML**. All messy parsing of external traces or Synergy's Python codebase lives in `tools/` (Python 3.10+).

### Setup
```bash
pip install -r tools/requirements.txt
pytest tests -v
```

### Script Reference

| Script | Input | Output | Purpose |
|---|---|---|---|
| `tools/convert_synergy_profiles.py` | Synergy repo (`simulator/models*/*.py`) | Profile JSONs + `fair-share-exceptions.md` | AST-parses Synergy's `tput` matrices without importing or executing Synergy code. |
| `tools/extract_synergy_jobs.py` | Synergy `out.log` (+ optional `--jct-csv` or `--cache-pickle`) | Job CSV (`job_id` 4000–4999) | Extracts measured jobs and exact JCTs from a Synergy run log (warned if JCTs come only from rounded `0.01 h` log prints). |
| `tools/synergy_record_to_jobs.py` | Synergy `--record_trace` output file | Full Job CSV (0..N, including unfinished jobs) | Converts every job Synergy generated up to run termination so our simulator can replay exact steady-state load. |
| `tools/prep_philly.py` | Philly `cluster_job_log` JSON | Job CSV + `.filters.txt` + `.ids.csv` | Filters `status == Pass`, valid timestamps, `>0` GPUs and duration; `--strict` drops jobs requesting `>8` GPUs. |
| `tools/compare_runs.py` | `ours.csv`, `ref.csv` | Terminal report + `<ours>.compare.json` | Compares mean, p99, p99.9 JCT (hours), share within `1 s`, max difference, and `PASS`/`FAIL` ($\le 10\%$ error). Exit codes: `0` PASS, `2` FAIL, `1` bad input. |

### `tools/synergy_validation/` (Phase 4 Suite Generator)
- `queue.txt` — Lists all **30** validation configurations (`name`, Synergy scheduler, arrival rate, class split, CPUs/server, `--multigpu`).
- `run_synergy.sh` — Runs Synergy (`commit 1bb2bed`) in an isolated copy with Python 3.14 compatibility shims, keeping `--record_trace` and exact JCTs from `cache/stats_<run>.pickle`.
- `build_suite.py` — Converts those 30 runs into `presets/synergy-validation/<name>/{jobs.csv, ref_jct.csv, exp.yaml}` + `manifest.csv`.

> [!WARNING]
> **Known Tool Caveats:**
> 1. **`tools/compare_runs.py` mean mismatch:** It computes `ours` mean over *all* finished jobs in our CSV (e.g., 6,925 jobs) while `ref` only has the 1,000 measured steady-state jobs (`4000–4999`), reporting ~3.9% mean difference even on an exact 1,000/1,000 match. Its job-by-job checks (`share_common_within_1s`, `largest_difference`) compare common IDs accurately. (See [EXTENDING.md](./EXTENDING.md#5-open-improvement-roadmap) to fix this.)
> 2. **`models-4/5/6` `fair_share` block:** The converter's `fair_share` metadata block still lists `3` cores/GPU in `models-4/5/6`, though the simulator ignores that metadata and normalizes against the server's actual fixed share (`4/5/6`).
> 3. **`models-6/alexnet_1.json` & `shufflenet_1.json`:** Synergy changed `ideal_speedup` and `iter_time` in those two files without updating `tput`. Always use `throughput_mode: synergy_two_point` when replicating Synergy on `models-6`.

---

## 2. Python Test Suite (`tests/`)

| File | Coverage |
|---|---|
| `tests/conftest.py` | Shared paths and environment variables (`SYNERGY_REPO`, `SYNERGY_REF`, `SYNERGY_OUT_LOG`, `SYNERGY_JCT_CSV`, `SYNERGY_CACHE_PICKLE`). Live-Synergy tests **skip** cleanly when local Synergy data is absent. |
| `tests/test_convert_synergy_profiles.py` | Profile conversion structure, ResNet-18 fidelity (`1.0` at 3 cores, `2.4` at 9 cores), fair-share checks. |
| `tests/test_extract_synergy_jobs.py` | Log parsing, missing-field/multi-run error checks, optional verification against `synergy_ref_fifo_tune_9jph.csv`. |
| `tests/test_prep_philly.py` | Filtering `tests/data/philly_sample.json` (10 jobs → 8 default / 7 `--strict`). |
| `tests/test_compare_runs.py` | Column aliases, percentile math, `PASS`/`FAIL`/exit-code behavior, duplicate-ID errors. |

---

## 3. Presets Overview (`presets/`)

| Path | Contents | Purpose |
|---|---|---|
| `presets/smoke/` | 20 jobs on two 8-V100 servers (`periodic` 300s vs `event_driven`) | Fast (~1 s) end-to-end sanity check (`ExperimentRunnerTest`). |
| `presets/profiles/synergy-v100/` | 69 converted JSON profiles (`models/`, `models-4/`, `models-5/`, `models-6/`) | Real V100 throughput tables from Synergy (MIT license). |
| `presets/reference/` | `synergy_ref_fifo_tune_9jph.csv`, `synergy_ref_fifo_fair_9jph.csv` | Reference per-job JCTs for jobs `4000–4999` from Synergy. |
| `presets/synergy-replica/` | `synergy-replica.yaml` + 6,925-job `jobs.csv` | Step-0 baseline: `FIFO+tune`, `FIFO+fair`, `FIFO` (Greedy) at 9 jobs/hr. |
| `presets/synergy-validation/` | 30 subdirectories + `manifest.csv` | Full Phase 4 suite covering load sweeps, Greedy, SRTF/LAS/FTF, CPU ratios, splits, and multi-GPU. |

---

## 4. Phase 4 Validation Results

Every configuration below replayed Synergy's recorded trace with `round_based` (`synergy_rounds: true`), `first_fit`, `synergy_two_point`, and `locality_penalty: 1.0`, comparing all **1,000 measured jobs (`4000–4999`)** individually (`< 1 s` tolerance; observed max difference **`0.0 s`** across all 31 runs):

| Family | Configurations Tested | Exact Jobs | Mean JCT Range (Ours == Synergy) |
|---|---|---|---|
| **Step 0 (`synergy-replica`)** | `FIFO+tune` & `FIFO+fair` at 9 jobs/hr | `1000/1000` each | `21.8766 h` (`FIFO+tune`), `81.0886 h` (`FIFO+fair`) |
| **Load Sweep (Paper Fig. 9)** | `FIFO+tune` & `FIFO+fair` at 3, 6, 8 jobs/hr | `1000/1000` each | `15.2479 h`–`27.4251 h` |
| **Greedy & Orderings (9 jph)** | `FIFO` (Greedy); `SRTF`, `LAS`, `FTF` × (`+tune`, `+fair`) | `1000/1000` each | `16.6031 h` (`SRTF+tune`) to `613.5850 h` (`FTF+fair`) |
| **CPU:GPU Ratio (Paper Fig. 12)** | 32, 40, 48 CPUs/server × (`FIFO+tune`, `FIFO+fair`) | `1000/1000` each | `32.0849 h`–`54.1124 h` (`+tune`), `81.01 h` (`+fair`) |
| **Workload Splits (Paper Fig. 11)** | `(33,33,33)` & `(50,0,50)` × (`+tune`, `+fair`, Greedy) | `1000/1000` each | `31.0394 h`–`158.8366 h` (Greedy collapses on CPU-heavy 50/0/50) |
| **Multi-GPU (Paper Figs. 7, 8)** | `LAS` 4 jph (`+tune`, `+fair`), `SRTF` 5.5 jph (`+tune`, `+fair`), `FIFO+tune` 4 jph | `1000/1000` each | `26.7152 h`–`59.7479 h` |

### Running Validation Tests
- **Default (runs on every `mvn install` / `mvn test`, ~30 s):** `SynergyValidationTest` checks `FIFO+tune` and `FIFO+fair` in `presets/synergy-replica/`.
- **Opt-in 30-config suite:**
  ```bash
  # Subset of 6 representative configs:
  mvn -pl modules/cloudsim-cosched test -Dtest=SynergyValidationSuiteTest -DargLine="-Dcosched.validate=subset -Xmx3g"

  # All 30 configs (~1 hour):
  mvn -pl modules/cloudsim-cosched test -Dtest=SynergyValidationSuiteTest -DargLine="-Dcosched.validate=all -Xmx3g"
  ```
