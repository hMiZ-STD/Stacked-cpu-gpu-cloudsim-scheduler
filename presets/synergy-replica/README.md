# synergy-replica

Reproduces Synergy's reference run (`FIFO+tune`, 9 jobs/hour, split 20/70/10) and its `FIFO+fair` and
`FIFO` (Greedy) counterparts, with every setting locked to Synergy's simulator
(`docs/validation-plan.md`, sections 1 and 2).

Result (Phase 4): all 1000 measured jobs (ids 4000-4999) have the same JCT as in Synergy, to the second,
for both `FIFO+tune` (mean 21.8766 h, p99 156.933 h) and `FIFO+fair` (mean 81.0886 h).

## Files

| File | What it is |
|---|---|
| `synergy-replica.yaml` | The experiment: three candidates, Synergy's cluster and settings |
| `jobs.csv` | Every job of Synergy's run (6925, ids 0-6924), recorded by Synergy itself |
| `../reference/synergy_ref_fifo_tune_9jph.csv` | Synergy's JCTs for jobs 4000-4999, `FIFO+tune` |
| `../reference/synergy_ref_fifo_fair_9jph.csv` | The same for `FIFO+fair` |

## How `jobs.csv` was made

Synergy's simulator (commit `1bb2bed`, `simulator/`) can record every job it generates, including those
still unfinished when it stops (`--record_trace`, `runner.py` `record_job`):

```bash
cd synergy/simulator
# runner.py ships with schedulers = ['FIFO+fair']; that run records the longer trace (6925 jobs; FIFO+tune
# stops earlier with 6376, which are the same first 6376 jobs)
python runner.py --cluster_job_log trace/cluster_job_log --no_use_cache --record_trace --rec_trace ./record
python <repo>/tools/synergy_record_to_jobs.py record9.0_fair -o <repo>/presets/synergy-replica/jobs.csv
```

Synergy needed four compatibility changes to run on Python 3.14, none of which touch scheduling:
`collections.Mapping` to `collections.abc.Mapping` (`resources/cluster.py`, `jobs/job.py`), matplotlib's
`basex` to `base` (`metrics/stats.py`), gRPC stubs generated from `deployment/runtime/protobuf/*.proto`
(as its `deployment/Makefile` does), and a stand-in `numa` module (NUMA is only used for physical
deployment). Synergy also needs a `cache/` folder to save its results. Its reference JCTs are read from
`cache/stats_<run>.pickle`.

## Settings and their Synergy source

| Setting | Value | Source |
|---|---|---|
| Rounds | 300 s, from t = 0, leases end each round | `round_duration=300`, `runner.py:305` |
| Progress | whole iterations per round, finish at round end | `jobs/job.py:164-189`, `job_lease_end_event.py` |
| Placement | first fit over free GPUs in index order | `SYNERGY_RANDOM`, `runner.py:419-427`, `cluster.py:435-548` |
| Speed | ideal speedup at the ideal demand, else 1 | `jobs/job.py:171-172`, `cluster.py:733-746` |
| Locality penalty | 1 | every `models/*_1.py` |
| Profiles | `<model>_1` for any GPU count | `jobs/model.py:171` |
| Iteration time | the profile's `iter_time` for every job | `jobs/model.py:180` |
