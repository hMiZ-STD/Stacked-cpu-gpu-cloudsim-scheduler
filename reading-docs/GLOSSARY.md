# Glossary of Terms, Concepts & Topics

This glossary explains every domain term, scheduling policy, CloudSim mechanism, workload/trace concept, and software engineering pattern used across the project—written in plain language first, followed by exact technical details and code references.

---

## 1. Hardware, Cluster & Organization Terms

| Term | Plain-Language Meaning | Technical Details & Where Used |
|---|---|---|
| **Server / Host** | One physical GPU machine in the cluster. | Modeled as `Server` (L4/Core) and `GpuHost` (L1), holding whole GPUs, CPU cores, and RAM. |
| **Server Group (`ServerGroup`)** | A batch of identical servers in the cluster config. | YAML `server_groups`: `name`, `count`, `gpus`, `gpu_type`, `cpus`, `mem_gb`, `storage_mbps`, `net_gbps`. `ClusterSpec` expands groups into numbered servers `0..N-1`. |
| **V100 / A100 (`gpu_type`)** | Specific NVIDIA GPU models. | Synergy's reference cluster uses `16` servers × `8 V100` GPUs, `24` CPU cores, `500 GB` RAM (`128` GPUs total). Profiles are looked up strictly by `gpu_type` (never auto-substituted). |
| **Fixed Share (`m:n` ratio / `+fair`)** | Traditional GPU-proportional CPU & memory slice. | On a server with $C$ CPUs, $M$ GB RAM, and $G$ GPUs, a $g$-GPU job gets $(g,\ \frac{C}{G}g,\ \frac{M}{G}g)$. On Synergy's default server ($24$ CPUs, $500$ GB, $8$ GPUs), 1 GPU = `3` CPUs and `62.5` GB RAM. |
| **Storage Bandwidth (`storage_mbps`)** | Disk/storage throughput per server. | Present in `ServerGroup` and Synergy profiles, where Synergy sets storage demand numerically equal to memory demand (`500` storage vs `500 GB` RAM). Because it never binds separately, our core `ResourceVector` tracks `(gpus, cpus, memGb)` and validates equality on Synergy profiles. |
| **NUMA Socket (`socket`)** | Physical CPU socket on a multi-socket motherboard. | `Gpu` keeps a `socket` field (always `0` in v1) as a hook for future NUMA-aware placement; `ServerGroup` keeps `racks` and `net_gbps` similarly. |
| **Organisation (`Organisation`)** | Multi-tenant teams and priority classes. | Optional YAML `organisation` block defining `teams` (with optional `gpu_quota`) and `priority_classes`. Defaults to one `default` team with no quota. |
| **Busy GPU Fraction (Cluster)** | Fraction of total cluster GPUs currently allocated. | Exposed on `ClusterState` (for future quota preemption policies such as Philly's 90% threshold). |

---

## 2. DNN Training & Performance Modeling Terms

| Term | Plain-Language Meaning | Technical Details & Where Used |
|---|---|---|
| **DNN Training Job** | Running a deep learning model (e.g., ResNet-18, GNMT) over batches of data. | Represented by `JobRecord` (input), `Job` / `JobInfo` (broker/policy view), and `TrainingJob` (L1). |
| **Iteration & `iter_time_s`** | One forward + backward training step on a batch of data, and how long it takes at the fixed share. | Synergy workloads specify work in `iterations` + `iter_time_s`. Trace workloads (Philly/Alibaba) specify `duration_s` at the fixed share (`1 work unit = 1 s`). |
| **CPU Data-Loading Bottleneck** | GPUs stalling while CPUs decode/augment training data. | Vision/speech models (`res18`, `alexnet`, `m5`, `deepspeech`) need up to `9+` CPU cores/GPU; language models (`gnmt`, `lstm`, `transformer`) often need only `1`. |
| **Throughput Matrix (`tput` / Profile JSON)** | Benchmark lookup table mapping `(cpus/GPU, mem/GPU)` → relative training speed. | Converted from Synergy's `.py` models into JSON (`presets/profiles/synergy-v100/`). CPU axis: `[1, 2, 3, 4, 5, 6, 9, 12, 24]`; memory axis: `[20.83, 62.5, 125, 187.5, 250]` GB/GPU. Trailing all-zero rows in multi-GPU files mean "not measured". |
| **Ideal Demand (`ideal_cpus_per_gpu`, `ideal_mem_gb_per_gpu`)** | The CPU cores & memory per GPU where a model hits peak profiled speed. | Scaled by `job.gpus()` and capped at one server's capacity (`ProfileTableModel.idealDemand`). If capped, speedup is linearly re-interpolated by cores; if `gpus > server.gpus`, falls back to fixed share (`speedup = 1`). |
| **`ideal_speedup`** | How much faster a job runs at its ideal demand vs the server's fixed share. | Example: `res18` has `ideal_speedup = 2.4` at `9` cores, `62.5` GB (`2.4×` faster than at `3` cores). In Synergy's `models-4/5/6` (32/40/48-CPU servers), `ideal_speedup` is normalized to `4/5/6` cores/GPU respectively. |
| **`throughput_mode`: `table` vs `synergy_two_point`** | How speedup is computed from a profile. | `table`: linearly interpolates CPU points (no extrapolation past max measured) and takes the largest measured memory column $\le m$, normalized by $T(c_{\text{fair}}, m_{\text{fair}})$. `synergy_two_point`: returns `ideal_speedup` if holding $\ge$ ideal demand, else `1.0`. |
| **`scaling`: `linear` vs `per_gpu_count`** | How profiles are selected for multi-GPU jobs. | `linear` (Synergy default): uses the 1-GPU profile (`<model>_1`) for any GPU count. `per_gpu_count`: looks up `<model>_<gpus>`. |
| **GPU Busy Fraction (`gpuBusyFraction`)** | Approximate fraction of time the GPU is actively computing rather than waiting on CPU/memory. | Computed by `RateCalculator` as $\min(1, \text{speedup} / \text{ideal\_speedup})$ (e.g., `1 / 2.4 ≈ 42%` for `res18` at fixed share). Drives GPU power in `GpuHost`. |
| **Locality Penalty (`LocalityModel`)** | Slowdown factor in `(0, 1]` when a multi-GPU job spans multiple servers (network sync overhead). | `1.0` if single-server or in Synergy's profiles (`placement_penalty = 1`); configurable via YAML `locality_penalty`. |
| **Interference (`InterferenceModel`)** | Slowdown caused by neighboring jobs sharing PCIe/network/memory on the same server. | Named seam in `cosched.model`, currently `InterferenceModel.NONE` (`1.0`). Real clusters like Philly measured up to `12.1%` slowdown with co-located jobs. |
| **Rate (`RateCalculator`)** | Final execution speed in **iterations per second**. | $\text{rate} = (\text{speedup} \times \text{locality} \times \text{interference}) / \text{iter\_time\_s}$. Computed in L2/L4; L1 only executes the resulting rate. |

---

## 3. Scheduling & Allocation Policies

| Term | Plain-Language Meaning | Technical Details & Where Used |
|---|---|---|
| **Gang Allocation** | A job must receive **all** of its requested GPUs simultaneously—never partial GPUs. | Enforced in `RoundPlan.assign(...)` and `GpuProvisioner.allocate(...)`. |
| **Scheduling Trigger (`SchedulingTrigger`)** | *When* the scheduler runs a decision round. | `periodic` (every `round_s` seconds, default `300 s`) or `event_driven` (on every arrival and job end). |
| **Ordering Policy (`OrderingPolicy`)** | *Which* waiting/active job gets considered first. | `fifo` (arrival time), `srtf` (shortest remaining time first), `las` (least attained service in GPU-seconds), `ftf` (finish-time fairness). |
| **Finish-Time Fairness (`ftf`)** | Fair share metric balancing waiting time against estimated total runtime. | Minimizes $(\text{time since arrival} + \text{remaining time}) / \text{total time at fixed share}$. |
| **Starvation Guard (`Starvation`)** | Prevents long or large jobs from waiting forever behind shorter jobs. | In Synergy's `SRTF`, `LAS`, and `FTF`, any job unscheduled for $> 1000\text{ hours}$ (`lastRunTime`) gets priority (`starved = true`) from the next round onward. |
| **Remaining Estimate (`RemainingEstimate`)** | Which speed assumption `SRTF` and `FTF` use when estimating remaining runtime. | `fixed_share` (for `+fair` schedulers) or `current_or_ideal` (for Greedy and Tune, using current allocation if running, else ideal speedup—matching Synergy sorting before resetting speedups). |
| **Allocation Policy (`AllocationPolicy`)** | *How many* CPU cores and GB of RAM a job asks for per GPU. | `gpu_proportional` (`+fair` fixed share) or `synergy_greedy` (ideal demand; skipped for the round if it doesn't fit). |
| **Joint Allocation & Placement (`JointAllocationPlacement`)** | Allocation + placement combined when placing one job can modify other jobs' allocations. | Implemented by `SynergyTune` (`synergy_tune`): tries ideal demand → falls back to fixed share → shrinks co-located peers on underutilized servers (fewest parts, then most CPUs/GPU, then most mem/GPU) back to fixed share. |
| **Placement Policy (`PlacementPolicy`)** | *Which servers* a job's GPUs are placed onto. | `consolidated` (`STRICT` = single server only, fullest fitting first; `SOFT` = Synergy `SYNERGY_PLACEMENT`, fullest server if whole job fits, else 1 GPU at a time from fullest servers) or `first_fit` (Synergy's `SYNERGY_RANDOM`: lowest-index servers/GPUs first, `SOFT` by default). |
| **Proportional Multi-Server Split (`Shares`)** | Rule when a job spans multiple servers: each server's part gets CPU and RAM strictly proportional to its GPUs. | Enforced by `Shares.split(...)` and `Shares.fixedShareOf(...)` (Synergy paper §4.2). |
| **Lifecycle Controller (`LifecycleController`)** | Whether running jobs stay put or are re-planned every round. | `non_preemptive` (`CARRY_RUNNING` plan: running jobs stay put) vs `round_based` (`EMPTY` plan: every job re-allocated from scratch each round; free preemption). |
| **`synergy_rounds`** | Exact discrete-round time semantics from Synergy's simulator. | Option on `round_based`: quantizes progress to $\lfloor \text{round\_s} / t_{\text{iter}} \rfloor$ whole iterations per round and records mid-round completions at the round boundary. |
| **`DRF` & `Tetris`** | Classic multi-resource schedulers (Dominant Resource Fairness & multi-resource packing). | Referenced in `docs/validation-plan.md` (Synergy Fig. 13) and `ClusterSpec.totalCapacity()`; **not yet implemented** in v1 (planned for Phase 5). |

---

## 4. CloudSim 7G & Simulation Mechanics

| Term | Plain-Language Meaning | Technical Details & Where Used |
|---|---|---|
| **Discrete-Event Simulation (DES)** | Time jumps directly from one event to the next instead of ticking every millisecond. | Core engine in `modules/cloudsim` (`CloudSim.java`). Allows simulating 1,500+ hours of cluster time in seconds of wall time. |
| **`SimEntity` & `SimEvent`** | Active simulation actors (`Datacenter`, `CoSchedulingBroker`) and timestamped messages between them. | `CoSchedulingBroker` extends `SimEntity` directly (not `DatacenterBroker`) so `GpuPlanExecutor` controls VM/cloudlet events. |
| **Rate-to-Speed Split** | Clean boundary: L2/L4 compute speed (`rate`), L1 just runs at that speed. | Agreed in Check 3 so `cloudsim-gpu` has zero knowledge of throughput tables or policies. |
| **Work in Iterations (vs MIPS)** | Measuring job progress in `double` training iterations instead of CloudSim's integer MI/MIPS. | `TrainingCloudlet` + `GpuCloudletScheduler` track `double` iterations directly, eliminating rounding errors (0.000000 s worst error across 300 random resizes). |
| **Synchronous Reservations** | Reserving/releasing GPUs, CPUs, and RAM immediately inside `GpuPlanExecutor.apply()`. | Prevents same-round capacity races while CloudSim's async `VM_CREATE_ACK` / `VM_DESTROY` events are still queued. |
| **CloudSim `0.01 s` & `0.02 s` Timing Limits** | Built-in `Datacenter` event throttling thresholds. | `Datacenter` delays `VM_CREATE_ACK` by `minTimeBetweenEvents` (`0.01 s`), ignores updates within `0.01 s` of its last update, and schedules next updates at least `0.02 s` apart. Handled in `GpuPlanExecutor` by submitting cloudlets immediately and scheduling wake-ups at new predicted finish times. |
| **`RoundPlan` & `PlanDiff`** | Mutable draft of one round's allocations, then diffed against active jobs. | `PlanDiff` compares the finished `RoundPlan` to running jobs and emits minimal `Start`, `Resize`, and `Stop` commands. |
| **Admission Control Seam** | Hook where a broker could reject incoming jobs under overload. | Named `admit(Job)` method in `CoSchedulingBroker` (currently returns `true` for all jobs). |

---

## 5. Workloads, Metrics & Validation Terms

| Term | Plain-Language Meaning | Technical Details & Where Used |
|---|---|---|
| **JCT (Job Completion Time)** | Total time from job submission (`arrival_s`) to completion (`finish_s`), **including queue wait**. | $\text{JCT} = \text{finish\_s} - \text{arrival\_s}$. Primary comparison metric (`mean_jct_h`, `p99_jct_h`, `p99.9_jct_h`). |
| **Queueing Delay (`queue_s`)** | Time spent waiting in the queue before first starting on GPUs. | $\text{queue\_s} = \text{first\_start\_s} - \text{arrival\_s}$. |
| **Makespan (`makespan_s`)** | Time when the very last job in the workload finishes. | $\max(\text{finish\_s})$. |
| **p99 / p99.9 JCT** | Tail latency: 99th and 99.9th percentile JCT across jobs. | Computed via linear interpolation between sorted ranks (`JobMetrics.percentile`), matching pandas/NumPy and Synergy. |
| **Attained Service (GPU-seconds)** | Cumulative GPU-time a job has received so far. | $\sum (\text{allocated GPUs} \times \Delta t)$. Used as the sorting key in `LasOrdering` (Least Attained Service). |
| **Steady-State Window (jobs `4000–4999`)** | Ignoring warm-up and cool-down jobs so measurements reflect a busy, steady cluster. | Synergy generates ~5,400–22,900 total jobs and measures JCTs strictly on job IDs `4000–4999` (1,000 jobs). |
| **Poisson Arrivals** | Memoryless random arrival process parameterized by average arrival rate $\lambda$ (e.g., `9 jobs/hr`). | Used in Synergy's synthetic load sweeps (inter-arrival times ~ Exponential($\lambda$)). |
| **Class Split (`(20, 70, 10)`)** | Target percentage of `image`, `language`, and `speech` jobs. | `ClassSplitLabeler`: `(20, 70, 10)` = 20% image, 70% language, 10% speech (Synergy's default paper split; `runner.py` default is `34, 33, 33`). |
| **Philly & Alibaba PAI Traces** | Real-world production GPU cluster job logs from Microsoft (Philly, ATC '19) and Alibaba (`cluster-trace-gpu-v2020`). | `tools/prep_philly.py` cleans Philly's `cluster_job_log` into our Job CSV format. |
| **`LinearPowerModel` & SPECpower** | Models converting server utilization into Watts of power. | `LinearPowerModel`: $P(u) = P_{\text{idle}} + (P_{\text{max}} - P_{\text{idle}})u$ (always on at $u=0$). SPECpower uses CloudSim's real measured server wattage tables. |
| **`RunManifest` (`manifest.json`)** | Self-contained provenance receipt written next to every `jobs.csv`. | Records git commit (`+dirty`), SHA-256 of config & workload CSV, resolved plugin parameters, cluster spec, assumptions, warnings, and summary metrics. |

---

## 6. Software Engineering & Verification Terms

| Term | Plain-Language Meaning | Technical Details & Where Used |
|---|---|---|
| **Java Records & Sealed Interfaces** | Modern Java 21 features for immutable data carriers and exhaustive `switch` checks. | `ResourceVector`, `Allocation`, `PartSpec`, `JobCommand` (`Start` \| `Resize` \| `Stop`). |
| **ArchUnit (`ArchitectureTest`)** | Automated JUnit test that inspects bytecode dependencies to forbid architectural violations. | Enforces the 5 dependency rules across `core`, `model`, `policy`, `policy.impl`, `broker`, and `StandardPlugins`. |
| **Contract Testing (`PlanExecutorContractTest`)** | Abstract test suite defining the behavioral contract any `GpuLayer` implementation must satisfy. | Written in Phase 1a for `gpu.stub`, inherited in Phase 1b by `CloudSimGpuLayerTest`. |
| **Check 1 Prototypes (`CheckA`–`CheckE`)** | Five small throwaway CloudSim experiments written before building the modules to de-risk key mechanics. | Preserved in `prototypes/` as historical record. |
| **`Params` Fail-Fast Validation** | Strict config parsing: defaults, ranges, and unused-key typo checks on startup. | Catches misspelled YAML keys (e.g., `round_sec`) or invalid values before a long simulation runs. |
