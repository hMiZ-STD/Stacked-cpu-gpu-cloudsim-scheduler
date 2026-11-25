# Phase 3 and Phase 4: what Synergy does and how we validate against it

Sources:
- the paper: Mohan et al., *Looking Beyond GPUs for DNN Scheduling on Multi-Tenant Clusters*, OSDI '22
  (USENIX page numbers);
- Synergy's simulator, `msr-fiddle/synergy` commit `1bb2bed` (file:line below), which produced the
  reference run we must match.

Where the paper and the code differ, **we follow the code**, because the reference numbers come from it.

## 1. How Synergy's simulator schedules (read from the code)

### 1.1 Rounds and time
- Rounds every 300 s starting at t = 0 (`round_duration=300`; `runner.py:305`; `events/schedule_event.py:76-86`).
- Events at the same time run in this order: job arrival, job end, **lease end**, **schedule**
  (`events/event_type.py`, `events/cluster_event.py:18-22`).
- Every running job gets a lease to the end of the round (`schedulers/fifo_synergy_new.py:101`). At lease
  end every job is deallocated (`events/job_lease_end_event.py`), and the schedule event re-allocates all
  runnable jobs from scratch. Preemption is implicit and free; there is no restart cost.

### 1.2 Progress and finish time (settles open question 3)
- When a job is allocated for a round, its progress for the **whole round** is added at once:
  `floor(300 / iteration time)` whole iterations, where iteration time = base iteration time
  / placement penalty (if spread) / speedup (if not fair) (`jobs/job.py:164-189`).
- A job is finished at the **lease end** of the round in which its iterations reach the total
  (`job_lease_end_event.py`, `jobs/job.py:470`). JCT = that time - arrival (`runner.py:372`).
  **Finish times are therefore always round boundaries**; resources a job frees mid-round are not used
  until the next round anyway, because nothing is scheduled between rounds.

Effect against our continuous progress: rounding the finish up to the next round adds 0-300 s per job (about
150 s on average, 0.2% of the 78,756 s mean JCT), and the floor loses less than one iteration per round
(at most 0.26% for the slowest model, 0.78 s per iteration). So the mean JCT moves by well under 1%, far inside
the 10% pass rule, **but job-by-job comparison would be off by up to one round per job**.

Decision: add a `synergy_rounds` option for validation presets (round-based lifecycle only):
1. rate = `floor(round_s / iteration time) / round_s`, so each full round advances exactly Synergy's
   number of iterations;
2. a job whose work ends inside a round is recorded as finishing at that round's end.
Since rates only change at round boundaries, this reproduces Synergy's finish rounds exactly. Our own
experiments keep continuous progress.

### 1.3 Ordering and the runnable set (settles open question 1)
- FIFO sorts jobs by arrival only (`fifo_synergy_new.py:39`) and tries each job whose **GPU** need fits
  the free GPUs (`:89-94`). **There is no sort by GPU, CPU and memory**; the paper's §4.2 describes one, but
  the code does not have it. The runnable set is therefore "by GPUs only", implicitly.
- Jobs not run for over 1000 hours get `job_priority = 1` (`:53`); FIFO's sort does not use it, but SRTF, LAS
  and FTF sort starved jobs first (all three set the flag; found in Phase 4, where FTF had first been missed).
- **LAS + Tune only** (`schedulers/las_synergy_new.py`): the jobs that fit the free GPU count are chosen in LAS
  order, then *placed largest GPU demand first* (stable sort on `-gpu_deficit`). No other Synergy ordering does
  this and the paper does not mention it; our `las` ordering has it as the option `largest_first`. It changes
  nothing for single-GPU traces and shows up only with multi-GPU jobs (found in Phase 4).

### 1.4 Placement: which one each scheduler name uses
`runner.py:419-427` sets `placement`, `fair` and `tune` **only from the scheduler's name**, overriding
the command-line flags. `placement` is true only if the name contains `place`, so `FIFO+tune`, `FIFO+fair`
and plain `FIFO` all use `SYNERGY_RANDOM` (`schedulers/scheduler.py:63-67`), which despite its name is
**not random**:
- `allocate_synergy_random` / `_top_synergy_gpus` (`resources/cluster.py:435-548`): walk the free GPUs **in
  index order** (server 0 GPU 0, ..., server 1 GPU 0, ...), and take each GPU whose server still has room
  for the job's **per-GPU share** of cores, memory and storage. First fit, no preference for consolidation.
  A 1-GPU job lands on the **lowest-numbered server that fits**; a multi-GPU job may be spread over any
  servers.
- `allocate_synergy_placement` (`:382-431`, only for names with `place`): if the job has more than one GPU
  (`prefers_consolidation`, `jobs/job.py:477`), the server **with the fewest free GPUs** that fits the whole
  job; otherwise (or if none fits) one GPU at a time from the servers with the fewest free GPUs.

Our `ConsolidatedPlacement` (fullest server) matches the `place` variant, not the reference run. Phase 3
adds a `first_fit` placement that matches `SYNERGY_RANDOM`.

### 1.5 Splitting a job over servers (settles open question 2)
Both placements work with a **per-GPU demand vector** and add one GPU's share at a time, so every part of
a job gets cores, memory and storage **exactly proportional to its GPUs** (`cluster.py:480-548`), as the
paper requires (§4.2). The reference run has single-GPU jobs only (no `--multigpu`), so splitting does not
occur there; it matters for the multi-GPU figures.

### 1.6 Allocation variants
| Name | `fair` | `tune` | Demand used | If it does not fit |
|---|---|---|---|---|
| `FIFO+fair` | true | false | fixed share x GPUs | skipped this round |
| `FIFO` (Greedy) | false | false | ideal demand | skipped this round |
| `FIFO+tune` | false | true | ideal demand, then Tune | see 1.7 |

Fixed share: CPU = `floor(server cores x GPUs / server GPUs)`, memory and storage proportional, not rounded
(`cluster.py:267-276`).

### 1.7 Tune, step by step (`cluster.py:575-746`)
1. Try the job at its ideal demand.
2. If it does not fit and its demand is above the fixed share in **any** dimension, switch cores, memory and
   storage to the fixed share and set its speedup to 1 (`_make_fair_share`); retry.
3. If it still does not fit (or was already at or below the fixed share), choose servers
   (`_get_underutilized_servers`):
   - more than 1 GPU: the first server, in index order, with enough free GPUs;
   - otherwise: servers taken from a heap by fewest free GPUs (ties by server id), until enough GPUs.
4. On each chosen server, compute what is missing and shrink peers to the fixed share (`_reallocate_peer`).
   Peers are tried in this order: **jobs on fewer servers first, then most cores per GPU, then most memory per
   GPU** (`:707`). Each shrunk peer is deallocated and re-allocated on the same GPUs at the fixed share,
   with its round's progress recomputed at speedup 1.
5. Retry the job. If it still does not fit, **Synergy exits** (`sys.exit(1)`, `:595-608`); the reference run
   never reached this, so our version treats it as a bug and stops the run.

The paper's description (§4.2) is close, with two differences: no GPU/CPU/memory sort (1.3), and
best fit is not used by the reference run (1.4).

### 1.8 A fourth resource that never binds
Synergy's demand vector also has storage speed. In every config (`configs/*.ini`: 500 storage, 500 GB per
server) and every profile (`models*/`: storage demand = memory demand), storage is numerically identical to
memory, so it never changes a decision. We leave it out, and the profile loader checks the equality for
Synergy presets.

## 2. Phase 3 work list
1. `RoundBasedLifecycle` (plan starts empty every round) with the `synergy_rounds` option (1.2).
2. `first_fit` placement matching `SYNERGY_RANDOM` (1.4, 1.5): GPUs in index order, per-GPU shares.
3. SOFT locality for `consolidated` matching the `place` variant (1.4), per-GPU proportional parts.
4. Greedy: ideal demand, skip if no fit (1.6).
5. Tune as a joint allocation-placement policy following 1.7 exactly, through `RoundPlan` revisions.
6. Orderings SRTF, LAS, FTF (then DRF, Tetris; `schedulers/*_synergy_new.py`, `drf.py`, `tetris.py`).
7. Preset `presets/synergy-replica/` = FIFO + Tune + first fit + round-based + `synergy_rounds` +
   `synergy_two_point` throughput + class split (20, 70, 10).

## 3. Phase 4: validation targets

### 3.1 Figure numbers
Synergy's artifact readme (`ae-readme.md`) numbers figures differently from the OSDI paper; the
architecture document (final.pdf) follows the readme. Both are listed here.

| Experiment | Readme / final.pdf | OSDI paper | Target |
|---|---|---|---|
| FIFO, single-GPU, load sweep (incl. our reference run) | Fig 8 | **Fig 9** | 9 jobs/hr: Tune 22 h, proportional 81 h, OPT 20 h. Our reference: 21.8766 h mean, 156.933 h p99 |
| Multi-GPU jobs | Fig 7 | **Figs 7 (LAS), 8 (SRTF)** | Curves |
| Workload splits | Fig 9 | **Fig 11** | (20,70,10), (33,33,33), (50,0,50) |
| CPU:GPU ratios 4, 5, 6 | (config change) | **Fig 12** | 9 jobs/hr: 3.4x, 3x, 2.2x, 1.8x for ratios 3, 4, 5, 6 |
| DRF and Tetris | Fig 12 | **Fig 13** | W2 (50,0,50): DRF-Synergy 7.2x, Tetris-Synergy 1.8x |
| Real Philly arrivals | Fig 6 | **Fig 6, Table 6a** | Avg JCT (h), prop. vs Synergy: SRTF 30/26, LAS 32/28, FIFO 71/62 |
| Intro result | Fig 1 | **Fig 1** | SRTF and LAS, multi-GPU, 128 GPUs |

When a report cites a figure, use the paper's number.

### 3.2 Extra targets
- `FIFO+fair` at 9 jobs/hr should be about 81 h (paper Fig 9). A second Synergy run gives an exact
  reference file for it.
- Table 5 (p.587), 32 GPUs: simulated FIFO makespan 15.67 h proportional / 11.33 h Tune; SRTF average JCT
  4.52 / 3.19 h; SRTF 99th percentile 16.85 / 8.54 h (deployment traces in `simulator/deploy_trace/`).

### 3.3 Set-up (§5.1, p.587)
128 GPUs (16 servers) for load sweeps, 512 GPUs (64 servers) for the Philly run; servers of 8 V100, 24 cores,
500 GB. Metric: average JCT of 1000 jobs in steady state (ids 4000-4999). Derived traces: GPU demand from
Philly, model by split; duration at the fixed share 10^x minutes, x uniform in [1.5, 3] with probability 0.8 and
[3, 4] with 0.2; Poisson arrivals.

### 3.4 Pass criteria (unchanged)
Average JCT within 10% of the reference, the same policy ranking and trends. With `synergy_rounds` and the
regenerated labels (exact, see Phase 1c report) we also aim for a job-by-job match.

## 4. Phase 4 results

Our simulator replays the job trace Synergy recorded in each run, and every measured job (ids 4000-4999) gets
the same JCT as in Synergy, to the second, in all 30 configurations plus the step-0 preset
(`presets/synergy-validation/`, `tools/synergy_validation/`, report `reports/phase-4-validation.tex`):

| Family | Configurations | Result |
|---|---|---|
| Step 0, FIFO at 9 jobs/hr | Tune, fixed share (`presets/synergy-replica`) | 1000/1000 exact; 21.8766 h and 81.0886 h |
| Load sweep (paper Fig 9) | FIFO Tune and fixed share at 3, 6, 8 jobs/hr | 1000/1000 exact each |
| Greedy | FIFO at 9 jobs/hr, three splits | exact |
| Orderings | SRTF, LAS, FTF x Tune, fixed share at 9 jobs/hr | exact |
| CPU:GPU ratio (Fig 12) | 32, 40, 48 cores, Tune and fixed share | exact |
| Splits (Fig 11) | (33,33,33), (50,0,50) x Tune, fixed share, Greedy | exact |
| Multi-GPU (Figs 7, 8) | LAS 4 jobs/hr, SRTF 5.5 jobs/hr (Tune, fixed share), FIFO Tune 4 jobs/hr | exact |

Not done: DRF and Tetris (Fig 13; not implemented), the real Philly arrival run (Fig 6, 512 GPUs), Part B
(our own Poisson generator; replaying Synergy's own traces made it unnecessary for validating the scheduler).

## 5. Still open
- ~~Replay input~~ **Resolved:** Synergy's own `--record_trace` writes every generated job, unfinished ones
  included; `tools/synergy_record_to_jobs.py` converts it (see `presets/synergy-replica/README.md`).
- **`tools/compare_runs.py` compares means over different job sets.** It takes "ours" over every job in our file
  but the reference over its 1000 measured jobs, so a replay that matches every job exactly still reports a
  3.9% error (21.02 h vs 21.88 h). It should restrict both sides to the common jobs (a change for its author).
  Its job-by-job figures ("within 1 s", "largest difference") are correct.
- How Synergy generates Poisson arrivals and samples GPU counts and durations, for Part B
  (`jobs/workload.py`); only needed if we generate traces instead of replaying them.
- Paper vs profile: ResNet-18 at 9 cores per GPU is 2.3x in the text (p.581) and 2.4 in the profile;
  we follow the profile.
