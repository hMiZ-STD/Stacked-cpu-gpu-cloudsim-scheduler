# File formats

The simulator reads three kinds of files. The Python tools in `tools/` produce the first two from
raw traces and Synergy's code; the experiment YAML is written by hand.

## 1. Job CSV

One row per job. The header names the columns; their order does not matter, and columns not
listed here (for example `jct_s` in a reference file) are ignored.

| Column | Required | Meaning |
|---|---|---|
| `job_id` | yes | Unique whole number |
| `arrival_s` | yes | Arrival time in seconds, >= 0 |
| `gpus` | yes | GPUs requested (gang: all at once) |
| `iterations` | one of these two | Work in training iterations (Synergy-style) |
| `duration_s` | one of these two | Run time at the fixed CPU:GPU share (trace-style); converted to iterations with the model's profile |
| `iter_time_s` | no, only with `iterations` | Seconds per iteration at the fixed share; if absent, the model's profile gives it |
| `model` | no | Model name, matching a profile (e.g. `res18`); required by the `from_trace` labeller |
| `team` | no | Team or virtual cluster, for quotas and fairness |
| `priority` | no | Priority class name |

Example (illustrative values; `tools/extract_synergy_jobs.py` writes these columns):

```
job_id,arrival_s,gpus,model,iterations,iter_time_s,jct_s
1,0,2,res18,12000,0.64,
2,420.5,1,gnmt,30000,0.12,
```

Every error names the line, e.g. `jobs.csv line 3: gpus must be > 0`.

## 2. Profile JSON

One file per (model, GPU type, GPU count), written by `tools/convert_synergy_profiles.py`. Unknown
fields are ignored.

| Field | Meaning |
|---|---|
| `model`, `gpu_type`, `gpu_count` | Lookup key |
| `iter_time_s` | Seconds per iteration at the fixed share |
| `ideal_cpus_per_gpu`, `ideal_mem_gb_per_gpu` | The job's ideal demand per GPU (Synergy's `cpus`, `mem`) |
| `ideal_speedup` | Speedup at the ideal demand over the fixed share of the profiled cluster |
| `placement_penalty` | Synergy's per-model penalty when spread (1 in every profile Synergy uses) |
| `cpus` | CPU axis: cores per GPU, strictly increasing (Synergy: 1, 2, 3, 4, 5, 6, 9, 12, 24) |
| `mem_gb_per_gpu` | Memory axis: GB per GPU, strictly increasing (Synergy: 20.83, 62.5, 125, 187.5, 250) |
| `throughput` | `throughput[i][j]`: relative speed at `cpus[i]` and `mem_gb_per_gpu[j]`. Trailing all-zero rows mean "not measured" |
| `used_by_synergy` | Whether Synergy's simulator loads this file (it loads only `<model>_1`) |
| `source` | File it was converted from |

How the simulator uses a profile:
- Speedup is the table value at the job's cores and memory per GPU, divided by the table value at
  the fixed share of the server it runs on (server cores / GPUs, server memory / GPUs). So 1.0
  always means "as fast as the fixed share" on that server.
- CPU points are linearly interpolated. The memory column is the largest listed value at or below
  the job's memory per GPU.
- Beyond the last measured CPU point the speed stays at the last value and the run log warns
  once per model. Nothing is extrapolated.
- A missing profile stops the run; there is no fallback to another GPU type.
- `throughput_mode: synergy_two_point` instead uses exactly Synergy's rule: `ideal_speedup` when the
  job holds at least its ideal demand per GPU, else 1.0.

## 3. Experiment YAML

Unknown keys are errors. Relative paths are relative to the YAML file.

```yaml
name: example                      # output goes to out/example unless output_dir is set
cluster:
  server_groups:
    - { name: legacy-v100, count: 16, gpus: 8, gpu_type: V100, cpus: 24, mem_gb: 500, storage_mbps: 500, net_gbps: 40 }
  racks: 2                         # optional
organisation:                      # optional; default is one team, no quotas
  teams: [ { name: vision, gpu_quota: 32 }, { name: nlp } ]
  priority_classes: [ high, low ]
workload:
  csv: jobs.csv
  labeller: { name: class_split, params: { split: [20, 70, 10] } }   # or { name: from_trace }
models:
  profiles_dir: ../presets/profiles/synergy-v100/models
  scaling: linear                  # linear (Synergy) | per_gpu_count
  throughput_mode: table           # table | synergy_two_point
  locality_penalty: 1.0            # speed factor when a job spans servers
candidates:                        # the schedulers to compare
  - name: fifo-fair
    trigger:    { name: periodic, params: { round_s: 300 } }
    ordering:   { name: fifo }
    allocation: { name: gpu_proportional }
    placement:  { name: consolidated, params: { locality: strict } }
    lifecycle:  { name: non_preemptive }
seeds: [42]
output_dir: out/example            # optional
```

Each `{ name, params }` is a plug-in looked up by name. Every parameter has a default with a stated
source and a valid range; an unknown name, unknown parameter or out-of-range value stops the run
before it starts.

### 3.1 Energy (optional)

Energy is modelled only when the YAML has an `energy` section, and then **every** server group needs a
`power` section. There are no defaults: each number must be written, and `source` must say where the
numbers come from (write "placeholder" if they are not real). Sources are printed in every decision report.

```yaml
cluster:
  server_groups:
    - name: v100-server
      count: 4
      gpus: 8
      gpu_type: V100
      cpus: 24
      mem_gb: 500
      storage_mbps: 500
      net_gbps: 0
      power:
        cpu_model: linear            # or a CloudSim SPECpower table, e.g. PowerModelSpecPowerIbmX3550XeonX5675
        cpu_idle_w: 100              # linear only: whole-CPU power at 0% and 100% of cores allocated
        cpu_max_w: 300
        gpu_idle_w: 50               # one GPU, idle (also while unallocated)
        gpu_max_w: 300               # one GPU, fully busy
        base_w: 200                  # fans, disks, NICs: constant
        source: "where these numbers come from"
energy:
  pue: 1.54                          # facility energy = IT energy x PUE, in [1, 5]
  pue_source: "..."
  grid_g_co2_per_kwh: 727            # CO2 = facility energy x this, in [0, 2000]
  grid_source: "..."
```

Server power is `P_cpu(cores allocated / cores) + sum over GPUs (idle + (max - idle) x busy) + base`. A GPU's busy
fraction is its job's speedup divided by its speedup at the (server-capped) ideal allocation. Idle servers stay on.

### 3.2 Workload: generated, and the measured jobs

Instead of `csv`, a workload can be generated from each run's seed. Every candidate runs on the same jobs for a
seed; the jobs are written to the run's `workload.csv`, so a run can be replayed with `csv: <that file>`.

```yaml
workload:
  generator:
    name: poisson
    params:
      jobs_per_hour: 9               # required: Poisson arrivals in whole seconds, as Synergy draws them
      jobs: 6000                     # required
      source: synthetic              # synthetic (default) | resample
      gpus: single                   # synthetic only: single (default) | synergy_mix (1: 70%, 2: 10%, 4: 15%, 8: 5%)
      # resample_csv: ../synergy-replica/jobs.csv   # resample only: each job copies a random row's GPUs, work and model
  labeller: { name: class_split, params: { split: [20, 70, 10] } }
  measure: { from_job: 4000, to_job: 4999 }   # optional: JCT statistics over these job ids (both included)
```

`synthetic` jobs are given as `duration_s`, Synergy's synthetic recipe: `60 x 10^U(1.5, 3)` s with probability
0.8, else `60 x 10^U(3, 4)` s. They have no model, so use the `class_split` labeller.

### 3.3 Sweeps (what-if questions)

Each sweep sets one value in the YAML tree (keys separated by dots, list positions as numbers, `*` for every
element) to each of its values in turn; several sweeps multiply. Settings that must change together go in one
sweep with `paths`, and each value is then a list with one entry per path (the first names the point).

```yaml
sweeps:
  - { name: load, path: workload.generator.params.jobs_per_hour, values: [6, 9, 12] }
  - name: cpus                       # more cores need profiles normalised to that server's fixed share
    paths: [cluster.server_groups.0.cpus, models.profiles_dir]
    values: [[24, ../profiles/synergy-v100/models], [32, ../profiles/synergy-v100/models-4]]
```

Every combination is validated before the first run. `sweeps`, `report`, `seeds` and `output_dir` cannot be swept.

### 3.4 Decision report

```yaml
report:
  metrics: [mean_jct_h, p99_jct_h, kwh_per_job]   # optional: what the tables show (default: every metric measured)
  objectives:                                       # optional: the weighted ranking (none: per-metric ranks only)
    - { metric: mean_jct_h, weight: 2 }
    - { metric: p99_jct_h, weight: 1 }
    - { metric: kwh_per_job, weight: 1 }
```

| Metric | Unit | Better | Meaning |
|---|---|---|---|
| `mean_jct_h`, `p99_jct_h`, `p99.9_jct_h` | h | lower | job completion time (finish - arrival), over the measured jobs |
| `mean_queue_s` | s (shown in h) | lower | arrival to first start |
| `makespan_s` | s (shown in h) | lower | until the last job finished |
| `gpu_busy_util` | fraction | higher | time-averaged share of GPUs computing |
| `gpu_alloc_util`, `cpu_alloc_util` | fraction | shown only | share of GPUs / cores allocated (slow jobs hold GPUs longer, so higher is not better) |
| `facility_energy_kwh`, `it_energy_kwh` | kWh | lower | energy over the whole run, with and without PUE |
| `co2_kg` | kg | lower | facility energy x grid factor |
| `kwh_per_job`, `j_per_sample` | kWh, J | lower | facility energy per finished job, per training sample (iterations x profile `batch_size`) |
| `stalled_gpu_energy_share` | fraction | lower | IT energy drawn by allocated GPUs waiting for data |
| `idle_gpu_energy_share`, `cpu_energy_share`, `gpu_energy_share`, `mean_it_power_kw` | | shown only | context |

The weighted score is the weighted mean *regret*, how much worse a candidate's mean is than the best
candidate's, relative to the best: 0% means best on every objective. A lead is **clear** if the paired 95%
interval of the gap over seeds excludes 0, **same order on every seed** if the leader wins every seed but the
gap's size is uncertain, and **within seed noise** otherwise.

## 4. Outputs

| File | Content |
|---|---|
| `<run>/jobs.csv` | `job_id, model, gpus, arrival_s, start_s, finish_s, jct_s, queue_s, starts, cpus_per_gpu, mem_gb_per_gpu` |
| `<run>/timeline.csv` | `time_s, used_gpus, busy_gpus, used_cpus[, it_power_w]`, one row per change of cluster state |
| `<run>/workload.csv` | generated workloads only: the jobs, in job CSV format |
| `<run>/manifest.json` | inputs and their SHA-256, git commit, every resolved parameter, sweep point, energy settings, assumptions, summary |
| `report/decision-report.tex` | standalone LaTeX report; `report/tables/*.tex` are its tables (need `booktabs`; wide ones `graphicx`) |
| `report/runs.csv` | one row per run: point, candidate, seed, sweep values, every metric |
| `report/summary.csv` | per point, candidate and metric: n, mean, sd, 95% CI, min, max |
| `report/ranking.csv` | per point and candidate: rank per metric, weighted score, its rank, seeds won, Pareto-optimal |
| `report/stability.csv` | per point and metric: leader, seeds led, runner-up, gap and its 95% CI, verdict |
| `report/sweep_stability.csv` | leader of each metric at every sweep point, and whether it changes |
