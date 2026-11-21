# PROPOSED_PLAN.md — v2.0 (Post-Clarification)
## Hybrid CPU-GPU Resource Scheduling — Implementation Plan
*Updated: 2026-10-08 | Status: Awaiting Final Approval Before Phase 1*

---

## Plain-Language Preamble (Read First — Viva-Ready Explanations)

**What is a scheduler?** It's an algorithm that decides which job runs on which server at what time. In a cloud cluster with GPUs, a bad scheduler wastes expensive GPU memory by placing jobs poorly, leaving "holes" nobody can use.

**What is VRAM?** Video RAM — the memory inside a GPU chip itself. An NVIDIA A100 has 80 GB of VRAM. If your AI training job needs 40 GB of VRAM, it *must* go to a host with a GPU that has at least 40 GB free. This is a hard, real-world constraint that our model will capture.

**What is the TraceSource pattern?** Imagine a power socket — you plug either a synthetic workload generator or a real CSV trace into it, and the rest of the system (scheduler, metrics) doesn't care which one it is. That's the abstraction.

**What is FRADS?** Our proposed scheduler — a hybrid algorithm that combines three ideas from top-tier systems research: fragmentation scoring (from FGD/ATC'23), multi-dimensional alignment packing (from Tetris/SIGCOMM'14), and fairness-weighted priority ordering (from DRF/NSDI'11). No single prior paper combines all three for joint CPU+GPU+VRAM scheduling.

---

## 1. What Changed Since v1.0

| Item | v1.0 | v2.0 (This Version) |
|---|---|---|
| Java version | 17 | **21 LTS** (Records, Sealed types, Pattern matching) |
| Track assignment | Assigned to A/B | **Deferred** — assign just before Phase 1 |
| Workload source | Synthetic only | **TraceSource interface** — synthetic now, real CSV later |
| GPU model | gpuCount only | **gpuCount + gpuVramGb** (industry-realistic) |
| Proposed scheduler | HCBFD (simple bin-pack) | **FRADS** (deep research hybrid — see §5) |
| Baseline 2 | Round Robin (trivial) | **DRF-Simplified** (principled, publishable) |
| Goal | Top marks | **Industry + resume-worthy** (marks are a side effect) |

---

## 2. Critical Review of Draft Plan (docs/Plan.md) — Updated

The 28-line draft plan is a brief only. Issues (beyond what was listed in v1.0):

- **No GPU memory modelling** — real cloud GPU scheduling *always* models VRAM (AWS/GCP/Azure). Omitting it would be technically naive.
- **Round Robin as baseline 2** — too trivial for a research paper. DRF-Simplified is the correct academic baseline (used by Apache Mesos in production).
- **No mention of workload source abstraction** — locking in synthetic traces makes the codebase less valuable on a resume. The TraceSource pattern fixes this at design time with minimal extra effort.
- **"Extend CloudSim"** — still insufficiently specific. §3 gives precise Java class hierarchy.

**What is realistic for ~4 weeks, laptop, no GPU:**
- ✅ Java 21 Maven project with full CI
- ✅ GPU model (gpuCount + gpuVramGb) in CloudSim Plus extension
- ✅ TraceSource interface + SyntheticTraceSource + CsvTraceSource stub
- ✅ 3 schedulers (FF, DRF-S, FRADS) with full unit tests
- ✅ 5 experiments, 7 metrics, publication-quality graphs
- ✅ IEEE-format 5-7 page report
- ❌ Actual RL/ML scheduler (training loop out of scope)
- ❌ Gang scheduling with preemption (too much edge-case complexity)
- ❌ Real trace replay at full scale (data wrangling; use CsvTraceSource stub as proof-of-concept)

---

## 3. Architecture

### 3.1 Full Component Map

```
┌──────────────────────────────────────────────────────────────────────┐
│                    CloudSim Plus 8.5.7 Core                          │
│  (Simulation, Datacenter, Host, Vm, Cloudlet, DatacenterBroker)      │
└────────────────────────┬─────────────────────────────────────────────┘
                         │ extends
┌────────────────────────▼─────────────────────────────────────────────┐
│                  GPU Resource Layer  [Java 21]                        │
│                                                                      │
│  record GpuSpec(int gpuCount, int gpuVramGb)        ← Java 21 Record │
│  record JobDemand(int cpu, GpuSpec gpu, int durSec) ← Java 21 Record │
│                                                                      │
│  GpuHost          extends Host                                       │
│    + GpuSpec totalGpu                                                │
│    + GpuSpec usedGpu                                                 │
│    + boolean canFit(JobDemand d)                                     │
│    + void allocate(JobDemand d)  / void release(JobDemand d)         │
│                                                                      │
│  GpuVm            extends Vm                                         │
│    + GpuSpec requestedGpu                                            │
│                                                                      │
│  GpuCloudlet      extends Cloudlet                                   │
│    + JobDemand demand                                                │
└────────────────────────┬─────────────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────────────┐
│               Workload / Trace Layer  [Java 21 + Python]             │
│                                                                      │
│  sealed interface TraceSource permits                                │
│      SyntheticTraceSource,   ← reads YAML config, Poisson arrivals  │
│      CsvTraceSource          ← reads Alibaba/Philly CSV              │
│                                                                      │
│  Python WorkloadGenerator:                                           │
│    configs/default.yaml  →  traces/workload.csv                      │
│                              (GpuCloudlets ready for simulation)     │
└────────────────────────┬─────────────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────────────┐
│                Scheduler Layer  [Java 21]                             │
│                                                                      │
│  sealed interface SchedulerPolicy permits                            │
│      FirstFitScheduler,       ← Baseline 1                          │
│      DrfSimplifiedScheduler,  ← Baseline 2                          │
│      FradsScheduler           ← Proposed (FRADS)                    │
│                                                                      │
│  record Placement(GpuCloudlet job, GpuHost host)   ← Java 21 Record  │
│  List<Placement> allocate(List<GpuCloudlet> queue,                   │
│                            List<GpuHost> cluster)                    │
└────────────────────────┬─────────────────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────────────────┐
│               Metrics Layer  [Java 21 + Python]                      │
│                                                                      │
│  record MetricsSnapshot(double cpuUtil, double gpuUtil,              │
│      double vramUtil, int queueLen, int unplaceable,                 │
│      double tick)                                 ← Java 21 Record   │
│                                                                      │
│  MetricsCollector: captures snapshot every tick → results.csv        │
│  analyse_results.py: reads CSV, plots 7 metrics, all 5 experiments  │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 Java 21 Design Choices (Explained Simply)

| Feature | Where Used | Why |
|---|---|---|
| **Records** | `GpuSpec`, `JobDemand`, `Placement`, `MetricsSnapshot` | Immutable, zero-boilerplate data carriers. No getters/setters needed. Readable. Signals modern Java on resume. |
| **Sealed interface** | `SchedulerPolicy`, `TraceSource` | Compiler enforces that only our 3 schedulers/2 trace sources can exist. Exhaustive switch on scheduler type = no hidden bugs. |
| **Pattern matching for switch** | `SimulationRunner` dispatching on scheduler type | Eliminates long if-else chains; compiler warns if a scheduler case is missing. |
| **var** | Local variables in verbose loops | Reduces noise without losing type safety. |

---

## 4. The FRADS Scheduler — Design (Industry-Ready Hybrid)

### 4.1 Why Each Prior Scheduler Is Insufficient Alone

| Scheduler | What It Gets Right | What It Misses |
|---|---|---|
| **First Fit** | Simple, fast | Causes fragmentation; ignores VRAM |
| **Round Robin** | Spreads load | Ignores resource fit; wastes GPU slots |
| **DRF** | Fair; handles multi-resources | Maximises fairness, not cluster-wide efficiency |
| **Tetris** | Multi-dimensional packing; reduces fragmentation | No fairness control; CPU+memory only in original |
| **Gandiva** | Time-slices GPU; great for DL | Needs real GPU hardware introspection; not simulatable |
| **Tiresias** | Spatial + temporal awareness; great JCT | Designed for DDL only; ignores CPU-only jobs |
| **FGD** | Best fragmentation metric | Stateless greedy; no fairness, no VRAM awareness |

**Gap**: No scheduler combines fragmentation-minimising placement + VRAM as a hard constraint + fairness-based priority + anti-starvation aging. **FRADS fills this gap.**

### 4.2 FRADS Algorithm (Plain Language + Pseudocode)

FRADS runs every scheduling tick and does three things:

**Step 1 — Priority Ordering** (fairness, inspired by DRF)
- Sort the job queue by *priority score*: `priority = dominant_share + age_penalty`
  - `dominant_share` = max(cpu_demand/total_cpu, gpu_demand/total_gpu, vram_demand/total_vram) — the resource this job dominates
  - `age_penalty` = waiting_ticks × α (α is a small constant; prevents starvation of low-priority jobs)
- Jobs with lower dominant share get scheduled first (fairness), but old jobs get a boost (anti-starvation).

**Step 2 — Placement Scoring** (efficiency, inspired by Tetris + FGD)
- For each (job, host) pair where the host can physically fit the job (gpuCount ≤ free, gpuVramGb ≤ free, cpu ≤ free):
  - Compute **alignment score**: dot-product of job demand vector with host free-resource vector, normalized.
  - Compute **fragmentation penalty**: how much the remaining host resources after placement would be un-usable for any other queued job (simplified FGD term).
  - `host_score = alignment_score - β × fragmentation_penalty`
- Place job on highest-scoring host.

**Step 3 — Batch Commit**
- Process the entire queue in priority order. Each successful placement updates host state immediately (greedy, no backtracking). Jobs that don't fit stay in queue.

**In pseudocode:**
```
FRADS.allocate(queue, cluster):
  sorted_queue = sort_by(queue, key = dominant_share(j) + age(j) × α)
  placements = []
  for job in sorted_queue:
    candidates = [h for h in cluster if h.canFit(job)]
    if candidates is empty: continue  // job stays in queue
    best_host = max(candidates, key = score(job, h, queue))
    placements.append(Placement(job, best_host))
    best_host.allocate(job)
  return placements

score(job, host, queue):
  align = dot(job.demandVector(), host.freeVector()) /
          (norm(job.demandVector()) × norm(host.freeVector()))
  frag  = fragmentation_after_placement(job, host, queue)
  return align - β × frag

fragmentation_after_placement(job, host, queue):
  // after placing job on host, how many OTHER queued jobs
  // still can't fit on host? normalized.
  remaining_free = host.freeAfter(job)
  unplaceable = count(q in queue\{job} : NOT remaining_free.canFit(q))
  return unplaceable / max(1, len(queue) - 1)
```

### 4.3 Configurable Parameters

| Parameter | Symbol | Default | Range | Meaning |
|---|---|---|---|---|
| Age weight | α | 0.05 | [0, 0.5] | How much waiting time boosts priority |
| Fragmentation weight | β | 0.3 | [0, 1.0] | How much fragmentation penalty reduces host score |

Both are YAML-configurable so you can run sensitivity experiments.

### 4.4 Why FRADS Is Resume-Worthy

- **Grounded in 3 top-tier papers** (ATC'23, SIGCOMM'14, NSDI'11) — you can cite the lineage
- **Novel combination**: no prior paper combines all three for joint CPU+GPU+VRAM scheduling
- **Industry-realistic**: models VRAM (as AWS/GCP/Azure do), uses DRF's dominant-share concept (used by Mesos), uses FGD's fragmentation term (published by Alibaba)
- **Tunable**: α and β are knobs — experiments varying them make for rich result analysis
- **Implementable in ~1 week** by one person, testable with JUnit 5

---

## 5. Workload Design (TraceSource Abstraction)

### 5.1 Why TraceSource Is the Right Pattern

The scheduler doesn't care *where* jobs come from — it sees a `List<GpuCloudlet>`. The TraceSource interface makes this explicit:

```java
// Java 21 sealed interface
sealed interface TraceSource permits SyntheticTraceSource, CsvTraceSource {
    List<GpuCloudlet> load(ClusterConfig cluster);
}
```

**To switch from synthetic to Alibaba trace:** Change one line in the YAML config. Zero scheduler code changes.

### 5.2 Synthetic Workload Parameters

```yaml
# configs/default.yaml
cluster:
  num_hosts: 10
  cpu_per_host: 32
  ram_per_host_gb: 256
  gpu_per_host: 4           # GPU device slots
  vram_per_host_gb: 80      # e.g., 4 × A100-equivalent

workload:
  source: synthetic          # or: csv (uses CsvTraceSource)
  num_jobs: 500
  gpu_job_fraction: 0.30     # 30% need GPUs
  arrival_process: poisson
  arrival_rate_per_min: 10
  seed: 42
  job_types:
    cpu_only:
      prob: 0.70
      cpu: [2, 4, 8]         # uniform random choice
      gpu: 0
      vram_gb: 0
      duration_s: {dist: exponential, mean: 120}
    gpu_small:
      prob: 0.20
      cpu: 4
      gpu: 1
      vram_gb: 16
      duration_s: {dist: exponential, mean: 300}
    gpu_large:
      prob: 0.10
      cpu: 16
      gpu: 4
      vram_gb: 64
      duration_s: {dist: exponential, mean: 900}

scheduler:
  name: FradsScheduler
  alpha: 0.05
  beta: 0.30

output_dir: results/
```

**Why these parameters?**
- 30% GPU job fraction: consistent with Philly trace analysis (mixed multi-tenant clusters) — VERIFIED
- Poisson arrivals: standard queuing theory model for inter-arrival times — ASSUMPTION (widely used)
- Exponential duration: standard for job service times in cluster scheduling literature — ASSUMPTION

### 5.3 CsvTraceSource (Real Trace Stub)

```java
// Maps Alibaba cluster-trace-gpu-v2020 columns to GpuCloudlet
// Columns used: job_id, submit_time, duration, num_gpu, plan_gpu_type → vram_gb, num_cpu
public final class CsvTraceSource implements TraceSource {
    // Column mapping documented in docs/TRACE_FORMAT.md
    // To activate: change workload.source: csv in YAML
}
```

When you fill this in (optional, Week 3/4), you get real-trace credibility with zero scheduler changes.

---

## 6. Experiment Design

### 6.1 Recommended Research Question (RQ1 — Recommended for Report)
> **RQ1**: In a simulated heterogeneous cloud cluster with mixed CPU-only and CPU+GPU AI workloads, does FRADS reduce joint fragmentation (JFR) and improve GPU+VRAM utilization compared to First Fit and DRF-Simplified, and at what cost to mean job completion time and fairness?

### 6.2 Secondary Questions (for depth in viva)
- **RQ2**: How does varying the GPU job fraction (10%–70%) affect fragmentation under each scheduler?
- **RQ3**: How sensitive is FRADS's performance to the α (aging) and β (fragmentation weight) parameters?

### 6.3 Five Experiments

| Exp | Variable | Fixed | Primary Metric |
|---|---|---|---|
| **E1** Scheduler comparison | Scheduler (FF, DRF-S, FRADS) | 30% GPU, medium load | JFR, GPU util, JCT |
| **E2** GPU fraction sensitivity | GPU fraction (10%, 30%, 50%, 70%) | FRADS, medium load | JFR, GPU util, VRAM util |
| **E3** Load stress test | Arrival rate (light/med/heavy) | FRADS, 30% GPU | Mean wait time, throughput |
| **E4** Cluster scale | Num hosts (5, 10, 20, 50) | FRADS, 30% GPU, med | All metrics (scalability) |
| **E5** Parameter sensitivity | α ∈ {0, 0.05, 0.2}, β ∈ {0, 0.3, 0.7} | 30% GPU, medium | JFR, JCT (surface plot) |

### 6.4 Metrics (7 total, with formulas)

| Metric | Formula | Unit | Why It Matters |
|---|---|---|---|
| CPU Utilization | `avg_t(Σ alloc_cpu / total_cpu)` | % | Core efficiency measure |
| GPU Utilization | `avg_t(Σ alloc_gpu / total_gpu)` | % | Main resource of interest |
| VRAM Utilization | `avg_t(Σ alloc_vram / total_vram)` | % | Industry-realistic metric |
| Mean JCT | `avg_j(finish_j − submit_j)` | seconds | User-visible performance |
| Mean Wait Time | `avg_j(start_j − submit_j)` | seconds | Queuing efficiency |
| JFR (Fragmentation) | `avg_t(unplaceable_t / queued_t)` | [0,1] | Our key novelty metric |
| Throughput | `n_completed / makespan` | jobs/s | Cluster-level efficiency |

---

## 7. Repository Structure

```
cpu-gpu-cloudsim-scheduler/
├── AGENTS.md
├── PROJECT_MEMORY.md
├── README.md                        ← Updated each week; deployment badge
├── .github/workflows/ci.yml         ← mvn test + pytest on every push
├── docs/
│   ├── Plan.md                      ← Original brief (read-only)
│   ├── TRACE_FORMAT.md              ← CsvTraceSource column mapping doc
│   └── phase0/
│       ├── LITERATURE_DIGEST.md
│       └── PROPOSED_PLAN.md         ← This file
├── simulator/                       ← Java 21, CloudSim Plus
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/cpugpusim/
│       │   ├── model/               ← GpuHost, GpuVm, GpuCloudlet, GpuSpec, JobDemand
│       │   ├── trace/               ← TraceSource, SyntheticTraceSource, CsvTraceSource
│       │   ├── scheduler/           ← SchedulerPolicy, FirstFitScheduler,
│       │   │                            DrfSimplifiedScheduler, FradsScheduler
│       │   ├── metrics/             ← MetricsCollector, MetricsSnapshot
│       │   └── runner/              ← SimulationRunner (main entry point, reads YAML)
│       └── test/java/com/cpugpusim/
│           ├── model/               ← 15+ unit tests
│           ├── trace/               ← TraceSource tests
│           ├── scheduler/           ← 30+ unit tests (all 3 schedulers × all edge cases)
│           └── metrics/             ← MetricsCollector tests
├── workloads/
│   ├── configs/                     ← YAML configs for each experiment
│   │   ├── default.yaml
│   │   ├── e1_scheduler_comparison.yaml
│   │   ├── e2_gpu_fraction.yaml
│   │   ├── e3_load_stress.yaml
│   │   ├── e4_cluster_scale.yaml
│   │   └── e5_parameter_sensitivity.yaml
│   ├── generator/
│   │   ├── workload_gen.py          ← SyntheticTraceSource Python mirror
│   │   └── tests/
│   └── traces/                      ← Generated CSVs (git-ignored)
├── experiments/
│   ├── run_experiments.py           ← Loops configs, calls Java JAR, collects results
│   ├── analyse_results.py           ← All 7 metrics × 5 experiments → graphs + tables
│   └── results/                     ← Output CSVs + PNGs (git-ignored)
├── report/
│   ├── main.tex                     ← IEEEtran format
│   ├── IEEEtran.cls
│   └── figures/                     ← PNGs (committed; generated by analyse_results.py)
└── .gitignore
```

---

## 8. Week-by-Week Schedule (4-Week Worst Case)

> **Deadline unknown → treat every week as if it's the last safe week for that phase.**

### Week 1 — Foundation
*Milestone: Both tracks have scaffolding with passing CI.*

**Java (Simulator):**
- [ ] Maven project with Java 21, CloudSim Plus 8.5.7 dependency, Checkstyle/Spotless configured
- [ ] `GpuSpec` record, `JobDemand` record
- [ ] `GpuHost` extends Host (gpuCount, gpuVramGb fields + canFit/allocate/release)
- [ ] `GpuVm`, `GpuCloudlet` extending CSP base classes
- [ ] `TraceSource` sealed interface + `SyntheticTraceSource` (reads YAML)
- [ ] `CsvTraceSource` stub (constructor only, throw NotImplemented)
- [ ] Unit tests: all edge cases for GpuHost (zero GPU, over-capacity, VRAM overflow, concurrent allocation)
- [ ] `mvn test` passes ✓

**Python (Workloads):**
- [ ] `pyproject.toml`, `requirements.txt`, ruff/black config
- [ ] `WorkloadGenerator` class (reads YAML → writes job CSV)
- [ ] `ExperimentConfig` YAML parser with validation
- [ ] Unit tests for generator (zero jobs, bad seed, all CPU-only, all GPU)
- [ ] `pytest` passes ✓

**Shared:**
- [ ] `.github/workflows/ci.yml` — runs both
- [ ] README first draft (what the project does, how to run)

---

### Week 2 — Baselines + Metrics
*Milestone: E1 can run end-to-end and produce a result CSV.*

**Java:**
- [ ] `SchedulerPolicy` sealed interface
- [ ] `FirstFitScheduler` + 10+ unit tests (edge: no hosts, all GPU-needing jobs, VRAM overflow)
- [ ] `DrfSimplifiedScheduler` + tests (DRF: compute dominant share per resource dimension, schedule lowest first)
- [ ] `MetricsCollector` → `MetricsSnapshot` record → results CSV (all 7 metrics per tick)
- [ ] `SimulationRunner` (reads YAML config, runs CSP simulation, writes results CSV)
- [ ] End-to-end integration test: 10 jobs, 3 hosts → CSV exists, columns valid, all values in range

**Python:**
- [ ] `run_experiments.py` v1: calls `java -jar simulator.jar --config e1.yaml`
- [ ] `analyse_results.py` v1: reads E1 CSV, prints 7 metric summaries
- [ ] E1 + E2 config files complete

---

### Week 3 — FRADS + All Experiments
*Milestone: All 5 experiments run; all 7 graphs generated.*

**Java:**
- [ ] `FradsScheduler` full implementation (α, β configurable from YAML)
- [ ] 15+ FRADS-specific tests (starvation prevention, fragmentation reduction, VRAM-aware placement)
- [ ] Determinism test: same seed → identical CSV (byte-for-byte if possible)
- [ ] Resource conservation invariant test: after all jobs finish, free_resources == initial_resources

**Python:**
- [ ] `analyse_results.py` complete: bar charts (E1), line charts (E2, E3, E4), 3D surface (E5)
- [ ] All 5 experiments run; graphs saved to `report/figures/`
- [ ] Begin report §III Design + §IV Experiments in LaTeX

---

### Week 4 — Report + Slides + Viva Prep
*Milestone: Submission-ready.*

**Both:**
- [ ] Report §I Introduction, §II Related Work (from LITERATURE_DIGEST.md), §V Results, §VI Conclusion
- [ ] Proofread, check 5-7 page limit
- [ ] 12-15 min slides (1 slide per major component + results)
- [ ] Practice viva: each person explains every design decision in their own words
- [ ] `git tag v1.0` — clean repo, no secrets, all tests green
- [ ] Final README: badges, setup instructions, reproduce-in-one-command

---

## 9. Test Strategy

### Categories (write tests BEFORE implementing each component)

| Test Category | Examples |
|---|---|
| Happy path | 10 jobs, 3 GPU hosts → all placed, metrics valid |
| Zero jobs | Empty queue → 0 placements, no crash |
| Zero GPU hosts | All CPU-only hosts → GPU jobs stay queued |
| VRAM overflow | Job needs 80 GB VRAM, host has 16 GB free → not placed |
| Over-capacity | Job needs 100 GPUs, cluster max is 32 → queued, never silently dropped |
| Starvation check | Large job waiting 200 ticks must eventually be placed (α > 0) |
| Resource conservation | All jobs complete → free_resources == initial_resources exactly |
| Fragmentation detection | 4 hosts × 1 GPU, job needs 2 GPUs → JFR = 1.0 |
| Reproducibility | Same seed → same result CSV |
| Single host, single job | 1 host, 1 job that fits → placed immediately |
| VRAM tie-breaking | Two equal-score hosts → deterministic choice (by host ID, ascending) |
| Mixed workload | 70% CPU-only + 30% GPU → both types correctly handled |

### Java: JUnit 5 + Mockito (for CSP dependencies)
- Coverage target: ≥ 80% line coverage on all non-runner classes
- Run: `mvn test`

### Python: pytest + pytest-cov
- Run: `python -m pytest --cov`

### Integration: end-to-end smoke test
- Run: `java -jar simulator.jar --config configs/default.yaml`
- Assert: result CSV exists, schema valid, JFR ∈ [0,1], utils ∈ [0,1], all JCT > 0

### CI: GitHub Actions on every push + PR
- Java job: setup Java 21, `mvn test`
- Python job: setup Python 3.11, `pip install -r requirements.txt && pytest --cov`

---

## 10. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| CSP 8.5.7 incompatible with Java 21 | Low | High | Test `mvn test` in Week 1 Day 1; downgrade to 8.x that works if needed |
| FRADS is too complex to finish in 1 week | Medium | High | FRADS Step 2 (placement scoring) can be simplified to pure dot-product (Tetris only) if time runs short, then add fragmentation term |
| One student stuck on CloudSim Plus API | Medium | Medium | Both students read CSP examples in Week 1; pair-program the model layer first |
| Deadline is actually 3 weeks, not 4 | Medium | High | Week 3 milestone covers all experiments — if Week 4 report writing disappears, move LaTeX to Week 3 |
| Report exceeds 7 pages | Medium | Medium | Section budgets: §I 0.5p, §II 1.5p, §III 1p, §IV 2p, §V 1.5p, §VI 0.5p |
| Simulation is too slow | Low | Medium | CSP runs in-process; 500 jobs on 10 hosts should be <10s. If slow: reduce to 200 jobs |
| Fragmentation metric gives always-zero | Low | High | Unit test JFR=1.0 case first; if CSP event loop doesn't support per-tick hooks, implement a custom Listener |
| α/β parameter space too large for E5 | Low | Low | Fix α=0.05, sweep β (3 values); swap α sweep for different β if time allows |
| Git conflicts | Low | Medium | Separate directories per track; shared files (`PROJECT_MEMORY.md`) updated in pair |

---

## 11. Rubric Mapping

| Criterion | Weight | How We Score Full Marks |
|---|---|---|
| **Literature Survey & Problem Formulation** | 3/15 | LITERATURE_DIGEST.md → 12 verified papers; clear RQ with cited gap; JFR metric formally defined |
| **Technical Depth / Algorithm / Design** | 4/15 | FRADS: novel hybrid from 3 top papers; Java 21 design patterns; VRAM modelling; full formal pseudocode |
| **Implementation & Experiments** | 4/15 | Working CSP extension; TraceSource abstraction; 3 schedulers; 5 experiments; 7 metrics; reproducible |
| **Report Quality** | 2/15 | IEEE format; 5-7 pages; proper figures; every claim cited; written by both students |
| **Presentation + Viva** | 2/15 | Each student explains ALL components; all design decisions documented with rationale |

---

*Awaiting go-ahead to begin Phase 1. See PROJECT_MEMORY.md for all decisions and verified facts.*
