# Synergy validation (Phase 4)

Reproduces the 30 reference runs of Synergy's simulator (msr-fiddle/synergy, commit `1bb2bed`) that
`presets/synergy-validation/` holds, and checks our simulator against them job by job.

| File | What it does |
|---|---|
| `queue.txt` | The 30 configurations: name, Synergy scheduler, jobs/hour, class split, CPUs per server, `--multigpu` |
| `run_synergy.sh` | Runs Synergy for one configuration on a private copy of its code; keeps the recorded job trace and the exact JCTs |
| `build_suite.py` | Turns finished runs into `presets/synergy-validation/<name>/{jobs.csv, ref_jct.csv, exp.yaml}` and `manifest.csv` |
| `../synergy_record_to_jobs.py` | Converts Synergy's `--record_trace` file into our job CSV |

## Steps

```bash
# 1. a Python environment for Synergy (any recent Python; 3.14 was used)
python3 -m venv $WORK/venv
$WORK/venv/bin/pip install numpy pandas matplotlib psutil cvxpy grpcio grpcio-tools protobuf

# 2. the 30 Synergy runs, four at a time (about an hour on 16 cores)
export SYNERGY_SIM=<synergy repo>/simulator WORK=<scratch dir> PY=$WORK/venv/bin/python
xargs -P 4 -L 1 tools/synergy_validation/run_synergy.sh < tools/synergy_validation/queue.txt

# 3. store traces and reference JCTs
$PY tools/synergy_validation/build_suite.py .

# 4. compare (all 30 take about an hour; "subset" runs six)
mvn -pl modules/cloudsim-cosched test -Dtest=SynergyValidationSuiteTest -DargLine="-Dcosched.validate=all -Xmx3g"
```

## What we learned running Synergy

- Each run replays the trace **recorded by that same run**. Synergy's job *generation* is not reproducible
  across processes for multi-GPU traces (two runs of one configuration generated different arrivals), but
  scheduling is deterministic: replaying a recorded trace with `--replay_trace` gives identical results.
- Synergy measures jobs 4000-4999 and stops when they finish; the recorded trace has every job generated up to
  then, including unfinished ones, which is what a replay needs.
- Its results go to `cache/stats_<run>.pickle` (needs a `cache/` folder); the column called `time (hours)`
  holds seconds.
- Debugging aid: our broker writes every round's plan when run with `-Dcosched.planTrace=<file>`; an
  instrumented copy of Synergy that prints `running_jobs` and their `res_map` after each round gives a
  file to compare round by round.
