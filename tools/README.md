# Validation tools

These are small Python scripts that turn Synergy's files and the Philly trace into the plain
formats the simulator reads. They also compare our results with Synergy's reference run. They
need Python 3.10+.

```bash
pip install -r tools/requirements.txt
pytest tools/tests -v
```

Local data is not in git. By default the tests look for it next to the repo:
`<parent>/synergy/repo` and `<parent>/synergy/synergy_ref_fifo_tune_9jph.csv`. You can point
them at other places with environment variables:

| variable | what |
|---|---|
| `SYNERGY_REPO` | Synergy checkout (the folder that contains `simulator/`) |
| `SYNERGY_REF` | `synergy_ref_fifo_tune_9jph.csv` |
| `SYNERGY_OUT_LOG` | `out.log` of the FIFO+tune run (see task 2) |
| `SYNERGY_JCT_CSV` / `SYNERGY_CACHE_PICKLE` | exact per-job JCTs of that run (see task 2) |

Tests whose data is missing are **skipped**, not failed. The unit tests use small inputs built
into the tests and always run.

---

## 1. `convert_synergy_profiles.py`

```bash
python tools/convert_synergy_profiles.py --synergy-repo ../synergy/repo -o out/profiles \
    [--report out/profiles/fair_share_exceptions.md]
```

This reads every `simulator/{models,models-4,models-5,models-6}/*.py` file and parses it with
`ast`. Nothing is imported or run. It writes `out/profiles/<dir>/<model>_<gpus>.json`, one
file per model file.

**How Synergy indexes `tput`** (from `jobs/job.py` and `opt_algo/synergy_opt.py`; the full
notes are at the top of the script): `tput[cpu_idx][mem_idx]`.

- Rows are **CPU cores per GPU**: `1, 2, 3, 4, 5, 6, 9, 12, 24` (`Job.cpu_val`). Row 6 is 9
  cores. That is why ResNet-18 reaches 2.4x at row index 6.
- Columns are **GB of memory per GPU**: `20.83, 62.5, 125, 187.5, 250` (`Job.mem_val`).
  The JSON keeps these as `mem_gb_per_gpu`. `mem_fractions` is GB divided by 250, so the last
  column is 1.0 ("fully cached").
- The 4-column files (`*_2.py`, `*_4.py`, `shufflenet_12.py`) are an older format. Their
  columns are `62.5, 125, 187.5, 250` GB, which matches the map in `cvxpy_test.py`. Synergy's
  simulator only ever loads `<model>_1`, so these files are marked `"used_by_synergy": false`.
- Fixed share: 3 CPUs and 62.5 GB per GPU (24 CPUs, 500 GB, 8 GPUs), which is cell `[2][1]`.

**Fair-share check:** at Synergy commit `1bb2bed`, every profile is 1.0 at 3 CPUs per GPU,
both at 62.5 GB and at full memory. There are no exceptions. The script prints the list again
on every run (and writes it with `--report`). The test fails if the list changes. Note that
column 0 (20.83 GB) is 0.1 for the image and speech models. That cell is below the fair share,
not at it.

Extra JSON fields beyond the brief: `iter_time_base_s`, `ideal_mem_gb_per_gpu`,
`ideal_storage_speed_per_gpu`, `mem_gb_per_gpu`, `fair_share`, `column_mapping`,
`used_by_synergy`, `defaults_used` (fields that `update_stats` did not set, so they were taken
from the `ModelStats` defaults).

## 2. `extract_synergy_jobs.py`

**Produce the run first.** The checkout's `simulator/runner.py` defaults to `FIFO+fair` (lines
393–394), even though `ae-readme.md` says the default is FIFO+tune. Change those two lines to
`schedulers = ['FIFO+tune']` and `scheduler_name = ['FIFO-Tune']`. Leave the load (9 jobs/h),
split `(20,70,10)` and seed 42 at their defaults. Then run:

```bash
cd ../synergy/repo/simulator
python runner.py --cluster_job_log trace/cluster_job_log --plot --no_use_cache 2>&1 | tee out.log
```

Use `--no_use_cache`. Without it, a cached result is reused and the log has no per-job lines.
Keep `out.log` outside git.

```bash
python tools/extract_synergy_jobs.py out.log -o out/synergy_jobs.csv \
    --jct-csv "graphs/per_run/FIFO-Tune_9.0_(20, 70, 10)/job_completion.csv"
#   or: --cache-pickle "cache/stats_FIFO-Tune_9.0_(20, 70, 10).pickle"
```

The output columns are `job_id,arrival_s,gpus,model,iterations,iter_time_s,jct_s`, for ids
4000..4999 only. The script stops with an error if a job is missing, if a value is missing, or
if the log holds more than one run.

The log prints finish times rounded to 0.01 h. A JCT from the log alone can therefore be up to
about 18 s off, and the script warns when you use it that way. For JCTs that match
`synergy_ref_fifo_tune_9jph.csv`, pass `--jct-csv` (written by `--plot`) or `--cache-pickle`
(written by default). The pickle is read with a restricted unpickler, so no Synergy code is
imported.

The arrival time and iteration time come from the start line, which has the exact arrival in
seconds and `dur = iter_time × iters`.

Real-run test: `SYNERGY_OUT_LOG=… SYNERGY_JCT_CSV=… pytest tools/tests/test_extract_synergy_jobs.py`.
It checks:
- 1000 rows with no missing values;
- a 200/700/100 class split;
- every JCT within 1 s of the reference;
- mean 21.8766 h and p99 156.933 h.

## 3. `prep_philly.py`

```bash
python tools/prep_philly.py ~/data/philly/cluster_job_log -o out/philly.csv [--strict]
```

The input is `cluster_job_log` from https://github.com/msr-fiddle/philly-traces. Keep it outside
git. The output columns are `job_id,arrival_s,gpus,duration_s,model,team,priority,status`
(`model` and `priority` are empty). The script also writes:

- `out/philly.csv.filters.txt`: the same filter table it prints. Every removed job is counted
  there.
- `out/philly.csv.ids.csv`: maps `job_id` (0..N-1 in arrival order) to the Philly `jobid`.

Rules, applied in this order:
1. `status == Pass` only. Each other status gets its own row in the table.
2. Drop jobs with a bad `submitted_time`.
3. Drop jobs with no attempt that has valid start and end times.
4. Drop jobs that request 0 GPUs.
5. Drop jobs whose duration is 0 s or less.
6. With `--strict`, drop jobs that request more than 8 GPUs.

How the columns are filled:
- `gpus`: the GPUs of the last valid attempt, summed over its machines.
- `duration_s`: the sum over all valid attempts (Synergy's `sum_attempts`).
- `team`: the job's `vc`.
- `arrival_s`: seconds after the earliest `submitted_time` in the file, counted before any
  filter.

The test uses the hand-made `tests/data/philly_sample.json`, which has 10 jobs, including one
Failed, one Killed and one 16-GPU job. It expects 8 rows by default and 7 with `--strict`.

## 4. `compare_runs.py`

```bash
python tools/compare_runs.py out/synergy_jobs.csv ../synergy/synergy_ref_fifo_tune_9jph.csv \
    [--json out/compare.json]
```

Each file can use either column format. Our format is `job_id`,`jct_s`; the reference uses
`job id`,`jct_seconds`. All other columns are ignored. The script prints, and writes as JSON
(default `<ours>.compare.json`):
- mean, p99 and p99.9 JCT in hours for each run;
- the relative error of the mean;
- job counts, and the jobs found in only one file;
- the share of common jobs within 1 s;
- the largest difference and the job it belongs to;
- `PASS` if the relative error is 0.10 or less, else `FAIL`.

Percentiles use pandas' linear interpolation, as Synergy's own report does. Exit code: 0 for
PASS, 2 for FAIL, 1 for bad input.
