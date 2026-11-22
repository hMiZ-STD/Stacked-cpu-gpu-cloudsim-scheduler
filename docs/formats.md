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
