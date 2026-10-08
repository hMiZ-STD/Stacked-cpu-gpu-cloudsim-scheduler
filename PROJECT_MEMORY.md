# PROJECT_MEMORY.md: Living project memory (agent: update after every meaningful step)

## 1. Project
- Title: Hybrid CPU-GPU Resource Scheduling and Utilization Analysis for AI Workloads
- Course: Cloud Computing research project. Team of 2: [name A], [name B]
- Brief: see docs/Plan.md (source of truth for requirements)
- Deliverables: IEEE report (5-7 pages, PDF), code + experiment scripts (GitHub), results/graphs/tables, slides (12-15 min), individual viva, peer evaluation form if requested
- Grading (15): Literature & Formulation 3 | Technical Depth/Design 4 | Implementation & Experiments 4 | Report 2 | Presentation + Viva 2
- Deadline: UNKNOWN — planning for 4-week worst-case. Feature freeze: end of Week 3.

## 2. Constraints
- Hardware: Lenovo laptop, i7 13th gen, 16 GB RAM, no GPU
- Languages: Java 21 LTS (simulator core), Python 3.11 (workloads, runner, analysis)
- Goal: industry/resume-worthy (not grade-optimized). CI, clean code, reproducibility, defensible design.
- Simulation only — no real cloud, no real GPU required

## 3. Stack (decided)
- CloudSim Plus 8.5.7 + Maven 3.9+ + JUnit 5.10+ | pandas, numpy, matplotlib, PyYAML, pytest | Git/GitHub + GitHub Actions CI | Overleaf (IEEEtran)
- Python tools: ruff + black (linting), pytest-cov (coverage)
- Java tools: Checkstyle + Spotless via Maven plugins
- Java 21 features in use: Records (immutable value types for Job/Placement/Snapshot), Sealed interfaces (SchedulerPolicy hierarchy), Pattern matching for switch (resource-type dispatch)

## 4. Team tracks
- DEFERRED — tracks will be assigned just before Phase 1 implementation starts
- NOTE: Both students must understand ALL components for the individual viva

## 5. Decisions log (date | decision | why | source)
- 2026-10-08 | Use CloudSim Plus 8.5.7, NOT old CloudSim or iFogSim | CSP is actively maintained, Java 17+/21 compatible, extensible | VERIFIED https://github.com/cloudsimplus/cloudsimplus
- 2026-10-08 | Use Java 21 LTS | Student confirmed comfort; Java 21 gives Records, Sealed classes, Pattern matching (all production-ready LTS features) | VERIFIED https://openjdk.org/projects/jdk/21/
- 2026-10-08 | Do NOT port GPUCloudSim | GPUCloudSim targets CloudSim 3 — architecturally incompatible with CloudSim Plus | VERIFIED
- 2026-10-08 | GPU model = 2 sub-resources: gpuCount + gpuVramGb | VRAM is a hard scheduling constraint in real production (AWS/GCP/Azure); adds technical depth and industry realism | VERIFIED: cloud provider GPU scheduling docs, USENIX research
- 2026-10-08 | Synthetic workloads with TraceSource interface abstraction | Allows swapping synthetic → real trace (Alibaba/Philly CSV) with zero scheduler-layer changes | ASSUMPTION (design pattern — clean abstraction)
- 2026-10-08 | Track assignment deferred | Student's instruction | N/A
- 2026-10-08 | Deadline unknown — plan 4-week worst case | Student's instruction | ASSUMPTION
- 2026-10-08 | Goal = industry/resume-worthy, not grade-optimized | Student's explicit instruction | N/A
- 2026-10-08 | Proposed scheduler = FRADS (Fragmentation-and-Resource-Aware Dynamic Scheduler) | Deep research on state-of-art; hybrid of FGD fragmentation scoring + Tetris alignment + DRF fairness + aging anti-starvation | See PROPOSED_PLAN.md §5 for full design
- 2026-10-08 | Baselines = First Fit (FF) + DRF-Simplified (DRF-S) | FF = trivial greedy baseline; DRF-S = principled fairness baseline from real schedulers (Apache Mesos) | VERIFIED: DRF (NSDI'11), Tetris (SIGCOMM'14)

## 6. Verified facts (fact | source URL | date verified)
- CloudSim Plus 8.5.7 on Maven Central, requires Java 17+ (21 works) | https://cloudsimplus.org | 2026-10-08
- Java 21 Records, Sealed Classes, Pattern Matching are all production-ready LTS features | https://openjdk.org/projects/jdk/21/ | 2026-10-08
- GPUCloudSim: Siavashi & Momtazpour, J. Supercomputing 2019, vol.75:2535-2561 | https://github.com/ahmad-siavashi/gpucloudsim | 2026-10-08
- GPUCloudSim incompatible with CloudSim Plus (targets CloudSim 3) | multiple sources | 2026-10-08
- VRAM is a first-class scheduling constraint in AWS/GCP/Azure GPU instances (e.g. A100=80GB, H100=80GB, L4=24GB) | cloud provider docs + GPU scheduling literature | 2026-10-08
- FGD paper: USENIX ATC 2023 | https://www.usenix.org/conference/atc23/presentation/weng | 2026-10-08
- Tetris paper: ACM SIGCOMM 2014 (NOT NSDI) | https://dl.acm.org/doi/10.1145/2619239.2626334 | 2026-10-08
- Tiresias: USENIX NSDI 2019 | https://www.usenix.org/conference/nsdi19/presentation/gu | 2026-10-08
- Gandiva: USENIX OSDI 2018 | https://www.usenix.org/conference/osdi18/presentation/xiao | 2026-10-08
- Firmament: USENIX OSDI 2016, Gog et al. (Cambridge/MIT/Google); min-cost max-flow | https://www.usenix.org/conference/osdi16/presentation/gog | 2026-10-08
- Optimus: EuroSys 2018, Peng et al. (HKU); online resource-perf model for DNN training | 2026-10-08
- Philly trace paper: USENIX ATC 2019 (NOT NSDI) | https://www.usenix.org/conference/atc19/presentation/jeon | 2026-10-08
- Alibaba cluster-trace-gpu-v2020: 6500+ GPUs, 1800 machines, 2-month trace | https://github.com/alibaba/clusterdata | 2026-10-08
- CloudSim Plus paper: IFIP/IEEE IM 2017 | 2026-10-08
- Horus: IEEE TPDS 2022 Vol.33 No.1 | 2026-10-08
- DRF: USENIX NSDI 2011 | https://www.usenix.org/conference/nsdi11 | 2026-10-08

## 7. Assumptions (not yet verified — verify in Week 1)
- ASSUMPTION: CloudSim Plus 8.5.7 works correctly with Java 21 (should — backward compatible but unconfirmed)
- ASSUMPTION: 500 jobs on 10 hosts runs in <10s wall time in CloudSim Plus
- ASSUMPTION: Synthetic workloads acceptable to instructor
- ASSUMPTION: FRADS design is novel enough for top technical depth marks
- ASSUMPTION: Deadline ≈ 4 weeks from project start

## 8. Definitions (locked at Phase 0 — change requires updating this file + approval)
- **JFR (Joint Fragmentation Ratio)**: avg_t[ queued_unplaceable_t / queued_total_t ]. ∈ [0,1].
- **gpuCount**: Integer GPU device slots per job/host.
- **gpuVramGb**: Integer GB VRAM per job demand / host total.
- **Job (GpuCloudlet)**: (cpuCores, gpuCount, gpuVramGb, durationSec). CPU-only = gpuCount=0, gpuVramGb=0.
- **TraceSource**: Java interface. Implementations: SyntheticTraceSource (YAML config) and CsvTraceSource (real trace CSV). Schedulers only see List<Job>.
- **Simulation time unit**: 1 second.
- **JCT**: avg(finish_j − submit_j).
- **Baselines**: First Fit (FF), DRF-Simplified (DRF-S).
- **Proposed**: FRADS (Fragmentation-and-Resource-Aware Dynamic Scheduler) — see PROPOSED_PLAN.md §5.

## 9. Literature digest status
- docs/phase0/LITERATURE_DIGEST.md — 10 papers (P1-P10). Firmament (P11) + Optimus (P12) added to PROJECT_MEMORY; will be written into LITERATURE_DIGEST in next session.

## 10. Status
- Current phase: Phase 0 — Round 2 complete, awaiting student go-ahead for Phase 1
- Last completed step: Updated all docs based on student clarifications (session 2)
- Next step: Phase 1 — Maven project scaffold, GPU model, TraceSource interface, FRADS skeleton
- Blockers: Exact deadline unknown; track assignment deferred

## 11. Session log (newest first)
- 2026-10-08 | Session 2: Applied student answers — Java 21, no track split, TraceSource abstraction, FRADS scheduler design, VRAM model, Firmament+Optimus added to knowledge base, industry-first goal | No code (Phase 0) | All docs updated
- 2026-10-08 | Session 1: Phase 0 — 10 papers verified, LITERATURE_DIGEST.md + PROPOSED_PLAN.md created | No code | Done