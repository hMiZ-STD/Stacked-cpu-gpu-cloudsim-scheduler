#!/usr/bin/env python3
"""Turn finished Synergy runs (run_synergy.sh) into the stored validation suite presets/synergy-validation/.

Run with the Python that has Synergy's dependencies (it unpickles Synergy's result files):
    WORK=<scratch dir> python build_suite.py <repo root>

Per configuration <name>, writes
    jobs.csv     every job Synergy generated in that run, in our job-CSV format (tools/synergy_record_to_jobs.py)
    ref_jct.csv  Synergy's own JCT in seconds for the measured jobs 4000-4999 ("job id,jct_seconds")
    exp.yaml     the matching experiment (relative paths)
and presets/synergy-validation/manifest.csv listing every configuration.
"""
import csv, glob, os, pickle, subprocess, sys

REPO = os.path.abspath(sys.argv[1])
WORK = os.environ["WORK"]
QUEUE = os.path.join(REPO, "tools/synergy_validation/queue.txt")
OUT = os.path.join(REPO, "presets/synergy-validation")


def experiment(name, sched, cpus):
    base, _, suffix = sched.partition("+")
    ordering = {"FIFO": "fifo", "SRTF": "srtf", "LAS": "las", "FTF": "ftf"}[base]
    allocation = {"tune": "synergy_tune", "fair": "gpu_proportional", "": "synergy_greedy"}[suffix]
    oparams = ""
    if ordering == "las" and suffix == "tune":
        oparams = ", params: { largest_first: true }"      # Synergy's LAS+tune places the largest GPU demand first
    if ordering in ("srtf", "ftf"):
        oparams = ", params: { estimate: %s }" % ("fixed_share" if suffix == "fair" else "current_or_ideal")
    models_dir = "models" if cpus == 24 else f"models-{cpus // 8}"
    return f"""# Synergy's {sched} run, replayed (docs/validation-plan.md). jobs.csv and ref_jct.csv come from the same Synergy run.
name: {name}
cluster:
  server_groups:
    - {{ name: synergy-v100, count: 16, gpus: 8, gpu_type: V100, cpus: {cpus}, mem_gb: 500, storage_mbps: 500, net_gbps: 0 }}
workload:
  csv: jobs.csv
  labeller: {{ name: from_trace }}
models:
  profiles_dir: ../../profiles/synergy-v100/{models_dir}
  scaling: linear
  throughput_mode: synergy_two_point
  locality_penalty: 1.0
candidates:
  - name: {name}
    trigger:    {{ name: periodic, params: {{ round_s: 300 }} }}
    ordering:   {{ name: {ordering}{oparams} }}
    allocation: {{ name: {allocation} }}
    placement:  {{ name: first_fit }}
    lifecycle:  {{ name: round_based, params: {{ synergy_rounds: true }} }}
seeds: [42]
output_dir: ../../../out/synergy-validation/{name}
"""


manifest = [["name", "scheduler", "jobs_per_hour", "split", "cpus_per_server", "multigpu", "jobs_in_trace", "measured_jobs", "mean_jct_h"]]
for line in open(QUEUE):
    parts = line.split()
    if not parts:
        continue
    name, sched, jph, split, cpus = parts[:5]
    multi = "--multigpu" in parts
    sim = os.path.join(WORK, "runs", name, "simulator")
    rec = [p for p in glob.glob(os.path.join(sim, "record*")) if os.path.getsize(p) > 0]
    assert len(rec) == 1, (name, rec)
    d = os.path.join(OUT, name)
    os.makedirs(d, exist_ok=True)
    subprocess.run([sys.executable, os.path.join(REPO, "tools/synergy_record_to_jobs.py"), rec[0], "-o", os.path.join(d, "jobs.csv")],
                   check=True, capture_output=True)
    sys.path.insert(0, sim)
    pk = glob.glob(os.path.join(sim, "cache", "stats_*.pickle"))
    assert len(pk) == 1, (name, pk)
    df = pickle.load(open(pk[0], "rb"))[1].get_df()      # column 'time (hours)' holds seconds, as in Synergy's own files
    rows = sorted(zip(df["job id"], df["time (hours)"]))
    with open(os.path.join(d, "ref_jct.csv"), "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(["job id", "jct_seconds"]); w.writerows((int(j), int(t)) for j, t in rows)
    open(os.path.join(d, "exp.yaml"), "w").write(experiment(name, sched, int(cpus)))
    n_jobs = sum(1 for _ in open(os.path.join(d, "jobs.csv"))) - 1
    manifest.append([name, sched, jph, split, cpus, multi, n_jobs, len(rows), round(sum(t for _, t in rows) / len(rows) / 3600, 4)])
    sys.path.pop(0)
    print("built", name, file=sys.stderr)
with open(os.path.join(OUT, "manifest.csv"), "w", newline="") as fh:
    csv.writer(fh).writerows(manifest)
