# DRAFT PLAN (from a chat with Claude): UNVERIFIED, revision 2

> **Status: hypothesis only.** This draft was written in a chat before any research by the project team. Treat every paper name, design choice, metric definition and tool claim below as something to **verify or reject in Phase 0**. Do not copy anything from here into the report, code comments or `PROJECT_MEMORY.md` as a fact. The authoritative requirements are in `docs/Plan.md`.
> **Revision 2 (2026-10-08):** added Section 0 (findings from a quick web check that change the plan), corrected the base-framework assumption, and split the reading list into "existence confirmed by search" and "still unconfirmed". Existence confirmed means the paper or repo page was found online, **not** that its contents have been read or that the claims below about it are right.

## 0. Findings from a quick web check (read this first)
1. **GPUCloudSim already exists.** It is a GPU extension of CloudSim, with a public repo at https://github.com/ahmad-siavashi/gpucloudsim. The repo describes support for multi-GPU cards, GPU-enabled VMs, GPU applications, virtualization overhead and interference between co-running GPU applications, and says it packages **CloudSim 4.0** with the extension. Paper: Siavashi and Momtazpour, Journal of Supercomputing 75, 2019, DOI 10.1007/s11227-018-2636-7.
   - Consequence: the brief's "extend CloudSim with GPU resources" is partly done by prior work. Phase 0 must decide between (a) extending GPUCloudSim (CloudSim 4.0 based), (b) a smaller purpose-built GPU model on CloudSim Plus, or (c) another route. The report must state precisely what this project adds beyond GPUCloudSim.
   - My earlier recommendation of CloudSim Plus was made before knowing this and is **not** settled.
2. **A 2026 paper questions part of the fragmentation premise.** The OSDI '26 paper "Heterogeneity at Hyperscale" (Alibaba, six-month trace of 155,410 GPUs) reports that fractional-GPU fragmentation is now negligible there because GPU sharing is rarely used, and describes a defragmentation algorithm and a scheduling framework for idle resources. https://www.usenix.org/conference/osdi26/presentation/li-suyi
   - Consequence: do not base the motivation only on fractional-GPU fragmentation. The CPU-GPU co-scheduling angle in `docs/Plan.md` is a different angle and still needs its own evidence (the NSDI '22 paper mentions a potential CPU bottleneck).
3. **Alibaba publishes several GPU traces**, not one: cluster-trace-gpu-v2020 (about 6,500 GPUs, two months), v2023 (about 6,200 GPUs, heterogeneous) and a 2026 trace. https://github.com/alibaba/clusterdata
   - Consequence: pick one deliberately, check field definitions, size and license, and plan sampling for a 16 GB laptop.
4. **The FGD authors released a simulator** (Go, Kubernetes-scheduler based) with FGD and baselines such as best-fit, dot-product and random-fit: https://github.com/hkust-adsl/kubernetes-scheduler-simulator. Use as design and baseline reference only; do not copy code.

## 1. Core strategy (hypotheses)
- **Java simulator core, Python around it.** CloudSim variants are Java, so GPU support lives in Java. Python handles trace preprocessing, experiment orchestration and analysis.
- **Base framework: OPEN DECISION** (see Section 0, item 1). Compare GPUCloudSim (CloudSim 4.0 fork, already has GPU models) against CloudSim Plus (maintained, cleaner extension points, no GPU model assumed). Criteria: effort to get running on Java 17, extensibility for a custom joint scheduler, maintenance status, licensing, and how clearly our contribution stands out.
- **Candidate research question:** *Does a fragmentation-aware, CPU-GPU-coupled placement policy improve GPU utilization, job completion time and fragmentation compared with first-fit, best-fit and EASY backfilling on mixed AI workloads?* To be refined after Phase 0 in light of Section 0.

## 2. Intuition for the problem
A host has CPUs, RAM and GPUs. A GPU job also needs CPU cores and RAM on the same host. If CPU-only jobs consume the CPUs, GPUs on that host can sit idle because no GPU job can use them. That stranded capacity is one kind of fragmentation. Naive placement can cause it. A scheduler that considers CPU and GPU together may reduce it. (Hypothesis: confirm with sources, and note the OSDI '26 caveat.)

## 3. Architecture (proposed)
```
[Workload generator + trace preprocessing (Python)] --> workload.csv
[Cluster + experiment config (YAML)]               --> config
                    |
                    v
[Simulator core (Java; base framework TBD)]
   Host / GPU device model -> Job model -> Scheduler interface
   Schedulers: FCFS-FirstFit | BestFit | EASY-Backfill | Proposed (fragmentation-aware)
   Metrics collector (event listeners)
                    |
                    v
          results/*.csv (per run, per seed)
                    |
                    v
[Analysis (Python): pandas -> statistics -> plots/tables] --> IEEE report
```

### Components
- **Host/GPU model:** host with CPU cores, RAM and N GPU devices (each with memory, optional fractional share). Jobs request cores, RAM, GPU count or fraction, GPU memory. Keep it simple and document every assumption.
- **Job classes:** training (long, multi-GPU, gang-scheduled, needs CPU for data loading), inference (short, latency-sensitive, fractional GPU), CPU-only. Each job has arrival time, duration, resource request, optional deadline/SLO.
- **Workloads:** one Alibaba GPU trace (version TBD) to derive distributions, plus a synthetic generator with fixed seeds. Fallback: fit distributions from the trace. Check Philly and Google traces as alternatives.
- **Schedulers:** baselines first-fit, best-fit, EASY backfilling. Proposed: score feasible hosts by expected fragmentation after placement and CPU:GPU balance. All behind one scheduler interface. Stretch (optional): learned runtime predictor or small RL variant.

## 4. Metrics (to be defined precisely in Phase 0)
GPU and CPU utilization (time-averaged), makespan, mean and p95 job completion time, queueing delay, SLO violation rate (inference), fragmentation. The FGD paper proposes a statistical fragmentation measure (found via search; read it and decide whether to adopt, adapt or replace it). **Any definition in this draft is a placeholder.**

## 5. Experiments
- Sweep cluster load (about 50% to 120%), job mix, cluster size, job-size distribution.
- Ablations of scheduler components.
- At least 10 seeds per configuration with confidence intervals.
- One-command reproducibility.

## 6. Validation of the simulator
Unit tests with hand-computable cases (single-job completion time equals its duration, no negative or over-capacity allocation, everything released on completion, tiny cluster matches a hand simulation), plus the edge cases listed in `AGENTS.md`.

## 7. Tools (proposed)
Java 17, Maven, JUnit 5, base simulator TBD; Python 3.11 with pandas, numpy, matplotlib, PyYAML; Git/GitHub with GitHub Actions CI; Makefile or `just`; Overleaf with IEEEtran; draw.io or Mermaid; Docker optional.

## 8. Team split (2 members)
- **Track A:** simulator, GPU/host/job model, schedulers, metrics hooks, JUnit tests (Java-heavy).
- **Track B:** trace preprocessing, synthetic generator, YAML configs, experiment runner, statistics, plots, CI, Makefile (Python-heavy).
- **Shared:** literature review, report, slides. Weekly cross-walkthrough, because the viva is individual.

## 9. Timeline (deadline under about 4 weeks; exact dates go in PROJECT_MEMORY.md)
- **Week 1:** literature and design, including the base-framework decision.
- **Week 2:** core implementation: GPU model, job model, baselines, metrics, tests, synthetic generator.
- **Week 3:** proposed scheduler, trace-based workloads, full experiment matrix, ablations. Feature freeze at the end.
- **Week 4:** analysis, IEEE report, slides (12-15 min), viva rehearsal.

## 10. Risks (initial)
- Base-framework choice (GPUCloudSim vs CloudSim Plus) turns out costly: timebox the comparison in week 1.
- Novelty overlaps prior work: state the delta explicitly and cite GPUCloudSim and FGD.
- Proposed scheduler does not beat baselines: analyze where and why; report honestly.
- Trace preprocessing too slow: use fitted distributions.
- Java memory pressure on 16 GB: cap job counts, stream results to CSV.
- Agent-written code subtly wrong: tests and specs first, review every diff.

## 11. Reading list

### Existence confirmed by web search (contents NOT yet read; verify claims by reading)
- Weng et al., "Beware of Fragmentation: Scheduling GPU-Sharing Workloads with Fragmentation Gradient Descent", USENIX ATC 2023. Open access PDF: https://www.usenix.org/system/files/atc23-weng.pdf
- Weng et al., "MLaaS in the Wild: Workload Analysis and Scheduling in Large-Scale Heterogeneous GPU Clusters", NSDI 2022. https://www.usenix.org/conference/nsdi22/presentation/weng
- Siavashi and Momtazpour, "GPUCloudSim", J. Supercomputing 75, 2019. Repo: https://github.com/ahmad-siavashi/gpucloudsim
- Ye et al., "Deep Learning Workload Scheduling in GPU Datacenters: A Survey", ACM Computing Surveys 2023, DOI 10.1145/3638757; arXiv version https://arxiv.org/abs/2205.11913; paper list https://github.com/S-Lab-System-Group/Awesome-DL-Scheduling-Papers
- Calheiros et al., original CloudSim paper (PDF link given on Wikipedia: http://www.buyya.com/papers/CloudSim2010.pdf)
- "Heterogeneity at Hyperscale" (Alibaba), OSDI 2026: https://www.usenix.org/conference/osdi26/presentation/li-suyi
- "Power- and Fragmentation-aware Online Scheduling for GPU Datacenters", arXiv 2412.17484
- Jun et al., "An extension of CloudSim toolkits for GPGPU-based cloud computing simulation", Information, vol. 17 no. 11B, 2014
- Alibaba traces: https://github.com/alibaba/clusterdata

### Still unconfirmed (agent must locate and verify, or report not found)
- CloudSim Plus paper (Silva Filho et al., 2017) and its official docs
- Philly cluster analysis (Jeon et al., USENIX ATC 2019)
- EASY backfilling (Mu'alem and Feitelson)
- Gandiva, Tiresias, Themis, Gavel, Pollux (details via the survey above)
- Borg (Verma et al., 2015)
- "Cloudy: A Pythonic cloud simulator" (Siavashi and Momtazpour, 2024), seen only in a reference list
- "GPU cluster dynamics: insights from Alibaba's 2023 trace release" (Siavashi and Momtazpour, Computing 2024), seen only in a reference list

## 12. Open questions for Phase 0 to resolve
- Which base framework (GPUCloudSim, CloudSim Plus, other), and what exactly is our contribution beyond GPUCloudSim and FGD?
- Is a learned (ML/RL) scheduler expected by the instructor, or is a heuristic enough? (Ask the instructor.)
- Which Alibaba trace version, and is it practical on a 16 GB laptop?
- Precise, source-backed fragmentation definition and an efficient way to compute it inside the simulator.
- How to frame the motivation given the OSDI '26 finding about fractional-GPU fragmentation.